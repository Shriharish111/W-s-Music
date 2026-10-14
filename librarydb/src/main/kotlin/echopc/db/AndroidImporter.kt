package echopc.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteStatement
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportResult(
    val songsAdded: Int,
    val likedSongs: Int,
    val playlistsAdded: Int,
    val playlistSongsAdded: Int,
    val playsAdded: Int,
    /** Songs that exist only as files on the phone and so can't be played here. */
    val skippedLocalSongs: Int,
    val sourceVersion: Int,
) {
    fun summary(): String = buildString {
        append("Imported $likedSongs liked songs, $playlistsAdded playlists ($playlistSongsAdded entries) and $playsAdded plays")
        append(" — $songsAdded new songs in total.")
        if (skippedLocalSongs > 0) append(" Skipped $skippedLocalSongs on-device-only songs.")
    }
}

class ImportException(message: String) : Exception(message)

/**
 * Copies likes, library songs, playlists and play history out of an Android app `song.db`
 * (or a backup .zip that contains one) into this desktop database.
 *
 * The source is attached read-only to a private temp copy and rows are matched by column *name*,
 * so any Android schema version works as long as the core columns exist. Importing is
 * additive and safe to repeat: existing rows are never overwritten or duplicated, and a like
 * from the phone is merged in (a like is never removed).
 */
class AndroidImporter(private val db: LibraryDatabase) {

    suspend fun import(file: File): ImportResult = withContext(Dispatchers.IO) {
        if (!file.isFile) throw ImportException("File not found: ${file.name}")
        val workDir = java.nio.file.Files.createTempDirectory("wsmusic-import").toFile()
        try {
            val source = stageSource(file, workDir)
            runImport(source)
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Copies the database (extracting it from a zip if needed) so the original is never touched. */
    private fun stageSource(file: File, workDir: File): File {
        val target = File(workDir, "source.db")
        val isZip = file.inputStream().use { s -> ByteArray(2).also { s.read(it) } }.let { it[0] == 'P'.code.toByte() && it[1] == 'K'.code.toByte() }
        if (isZip) {
            ZipFile(file).use { zip ->
                val entry = zip.entries().asSequence().filter { !it.isDirectory }
                    .firstOrNull { it.name.substringAfterLast('/').equals("song.db", true) }
                    ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(".db", true) }
                    ?: throw ImportException("No song.db found inside ${file.name}")
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                // Some backups also carry the write-ahead log with the newest rows.
                zip.getEntry(entry.name + "-wal")?.let { wal ->
                    zip.getInputStream(wal).use { input -> File(workDir, "source.db-wal").outputStream().use { input.copyTo(it) } }
                }
            }
        } else {
            file.copyTo(target, overwrite = true)
            File(file.path + "-wal").takeIf { it.isFile }?.copyTo(File(workDir, "source.db-wal"), overwrite = true)
        }
        return target
    }

    private suspend fun runImport(source: File): ImportResult {
        var songsAdded = 0; var liked = 0; var playlistsAdded = 0; var plsAdded = 0; var plays = 0; var skipped = 0; var version = 0
        db.useWriterConnection { conn ->
            conn.usePrepared("ATTACH DATABASE ? AS src") { it.bindText(1, source.absolutePath); it.step() }
            try {
                suspend fun <T> query(sql: String, read: (SQLiteStatement) -> T): List<T> =
                    conn.usePrepared(sql) { st -> buildList { while (st.step()) add(read(st)) } }
                suspend fun exec(sql: String): Int {
                    conn.usePrepared(sql) { it.step() }
                    return conn.usePrepared("SELECT changes()") { it.step(); it.getLong(0).toInt() }
                }
                suspend fun count(sql: String): Int = conn.usePrepared(sql) { it.step(); it.getLong(0).toInt() }
                suspend fun columns(schema: String, table: String): List<String> = query("PRAGMA $schema.table_info($table)") { it.getText(1) }

                val srcTables = query("SELECT name FROM src.sqlite_master WHERE type = 'table'") { it.getText(0) }.toSet()
                val required = listOf("song", "playlist", "playlist_song_map", "event")
                if (!required.all { it in srcTables }) {
                    throw ImportException("This doesn't look like a database from the Android app (missing: ${required.filterNot { it in srcTables }.joinToString()}).")
                }
                version = count("PRAGMA src.user_version")

                /** Column list "a, b, c" common to both databases, with optional replacement expressions. */
                suspend fun common(table: String, exclude: Set<String> = emptySet(), override: Map<String, String> = emptyMap()): Pair<String, String> {
                    val src = columns("src", table).toSet()
                    val cols = columns("main", table).filter { it in src && it !in exclude }
                    if (cols.isEmpty()) throw ImportException("Unsupported database layout (table '$table').")
                    fun q(c: String) = "`$c`"
                    return cols.joinToString(", ") { q(it) } to cols.joinToString(", ") { override[it] ?: "s.${q(it)}" }
                }
                val songCols = columns("src", "song").toSet()
                val hasLocal = "isLocal" in songCols

                conn.immediateTransaction {
                    // Which songs to bring over: liked, in the library, or used by a playlist / the history.
                    exec("DROP TABLE IF EXISTS temp.import_ids")
                    exec(
                        "CREATE TEMP TABLE import_ids AS SELECT id FROM src.song s WHERE (" +
                            "liked = 1 OR inLibrary IS NOT NULL OR id IN (SELECT songId FROM src.playlist_song_map) OR id IN (SELECT songId FROM src.event))" +
                            (if (hasLocal) " AND isLocal = 0" else ""),
                    )
                    skipped = if (hasLocal) count("SELECT COUNT(*) FROM src.song WHERE isLocal = 1") else 0

                    // Songs (downloads don't exist on this machine).
                    val (sc, sv) = common("song", override = mapOf("isDownloaded" to "0", "dateDownload" to "NULL"))
                    songsAdded = exec("INSERT OR IGNORE INTO main.song ($sc) SELECT $sv FROM src.song s WHERE s.id IN (SELECT id FROM temp.import_ids)")

                    // Likes made on the phone also apply to songs that already exist here.
                    exec(
                        "UPDATE main.song SET liked = 1, " +
                            "likedDate = (SELECT s.likedDate FROM src.song s WHERE s.id = main.song.id), " +
                            "inLibrary = COALESCE(main.song.inLibrary, (SELECT s.inLibrary FROM src.song s WHERE s.id = main.song.id)) " +
                            "WHERE liked = 0 AND id IN (SELECT id FROM src.song WHERE liked = 1)",
                    )
                    liked = count("SELECT COUNT(*) FROM main.song WHERE liked = 1 AND id IN (SELECT id FROM src.song WHERE liked = 1)")

                    // Artists and albums those songs point at.
                    if ("song_artist_map" in srcTables && "artist" in srcTables) {
                        val (ac, av) = common("artist")
                        exec("INSERT OR IGNORE INTO main.artist ($ac) SELECT $av FROM src.artist s WHERE s.id IN (SELECT artistId FROM src.song_artist_map WHERE songId IN (SELECT id FROM temp.import_ids))")
                        val (mc, mv) = common("song_artist_map")
                        exec("INSERT OR IGNORE INTO main.song_artist_map ($mc) SELECT $mv FROM src.song_artist_map s WHERE s.songId IN (SELECT id FROM temp.import_ids) AND s.artistId IN (SELECT id FROM main.artist)")
                    }
                    if ("song_album_map" in srcTables && "album" in srcTables) {
                        val (ac, av) = common("album")
                        exec("INSERT OR IGNORE INTO main.album ($ac) SELECT $av FROM src.album s WHERE s.id IN (SELECT albumId FROM src.song_album_map WHERE songId IN (SELECT id FROM temp.import_ids))")
                        val (mc, mv) = common("song_album_map")
                        exec("INSERT OR IGNORE INTO main.song_album_map ($mc) SELECT $mv FROM src.song_album_map s WHERE s.songId IN (SELECT id FROM temp.import_ids) AND s.albumId IN (SELECT id FROM main.album)")
                    }

                    // Playlists that are in the phone's library (same filter the Android app uses).
                    exec("DROP TABLE IF EXISTS temp.import_playlists")
                    exec(
                        "CREATE TEMP TABLE import_playlists AS SELECT id FROM src.playlist WHERE bookmarkedAt IS NOT NULL " +
                            "AND id NOT IN ('LP_DOWNLOADED', 'LP_LIKED')",
                    )
                    val (pc, pv) = common("playlist")
                    playlistsAdded = exec("INSERT OR IGNORE INTO main.playlist ($pc) SELECT $pv FROM src.playlist s WHERE s.id IN (SELECT id FROM temp.import_playlists)")
                    val (mc, mv) = common("playlist_song_map", exclude = setOf("id"))
                    plsAdded = exec(
                        "INSERT INTO main.playlist_song_map ($mc) SELECT $mv FROM src.playlist_song_map s " +
                            "WHERE s.playlistId IN (SELECT id FROM temp.import_playlists) AND s.songId IN (SELECT id FROM main.song) " +
                            "AND s.playlistId IN (SELECT id FROM main.playlist) " +
                            "AND NOT EXISTS (SELECT 1 FROM main.playlist_song_map m WHERE m.playlistId = s.playlistId AND m.songId = s.songId)",
                    )

                    // Play history.
                    val (ec, ev) = common("event", exclude = setOf("id"))
                    plays = exec(
                        "INSERT INTO main.event ($ec) SELECT $ev FROM src.event s WHERE s.songId IN (SELECT id FROM main.song) " +
                            "AND NOT EXISTS (SELECT 1 FROM main.event m WHERE m.songId = s.songId AND m.timestamp = s.timestamp)",
                    )
                    exec("DROP TABLE IF EXISTS temp.import_ids")
                    exec("DROP TABLE IF EXISTS temp.import_playlists")
                }
            } finally {
                runCatching { conn.usePrepared("DETACH DATABASE src") { it.step() } }
            }
        }
        // Make sure open lists (Liked, Playlists, History) pick up the new rows.
        db.invalidationTracker.refreshAsync()
        return ImportResult(songsAdded, liked, playlistsAdded, plsAdded, plays, skipped, version)
    }
}
