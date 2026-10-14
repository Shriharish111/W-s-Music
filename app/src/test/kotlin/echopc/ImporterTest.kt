package echopc

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import echopc.db.AndroidImporter
import echopc.db.ImportException
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Builds a database with the Android app's real v45 schema (from its exported schema JSON) and sample rows. */
internal fun buildAndroidDb(file: File) {
    val schema = Json.parseToJsonElement(object {}.javaClass.getResource("/android-schema-45.json")!!.readText()).jsonObject["database"]!!.jsonObject
    val conn = BundledSQLiteDriver().open(file.absolutePath)
    try {
        for (e in schema["entities"]!!.jsonArray) {
            val o = e.jsonObject
            val table = o["tableName"]!!.jsonPrimitive.content
            conn.execSQL(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
        }
        val t0 = 1_700_000_000_000L
        val sql = listOf(
            // id, title, duration, liked, likedDate, inLibrary, isLocal, isDownloaded, dateDownload
            song("s1", "Song One", 200, liked = 1, inLib = t0),
            song("s2", "Song Two", 210, liked = 1, inLib = t0 + 1),
            song("s3", "Song Three", 220, liked = 0, inLib = t0 + 2),
            song("s4", "Song Four (playlist only)", 230),
            song("s5", "Song Five (history only)", 240),
            song("s6", "Local File", 250, liked = 1, inLib = t0, local = 1),
            song("s7", "Unreferenced", 260),
            song("s8", "Downloaded", 270, liked = 1, inLib = t0, downloaded = 1),
            "INSERT INTO artist (id,name,lastUpdateTime) VALUES ('A1','Artist One',$t0),('A2','Artist Two',$t0),('A3','Artist Three',$t0)",
            "INSERT INTO song_artist_map (songId,artistId,position) VALUES ('s1','A2',1),('s1','A1',0),('s2','A3',0),('s3','A1',0)",
            "INSERT INTO album (id,title,songCount,duration,lastUpdateTime) VALUES ('AL1','Album One',3,600,$t0)",
            "INSERT INTO song_album_map (songId,albumId,`index`) VALUES ('s1','AL1',0),('s2','AL1',1)",
            "INSERT INTO playlist (id,name,bookmarkedAt,createdAt,isLocal) VALUES ('LPgym','Gym',$t0,$t0,1)",
            "INSERT INTO playlist (id,name,browseId,bookmarkedAt) VALUES ('LPsync','Synced from YouTube','VLPL123',$t0)",
            "INSERT INTO playlist (id,name) VALUES ('LPhidden','Not in library')",
            "INSERT INTO playlist (id,name,bookmarkedAt) VALUES ('LP_DOWNLOADED','Downloaded',$t0)",
            "INSERT INTO playlist_song_map (playlistId,songId,position) VALUES ('LPgym','s1',0),('LPgym','s4',1),('LPgym','s6',2),('LPsync','s2',0),('LPhidden','s1',0)",
            "INSERT INTO event (songId,timestamp,playTime) VALUES ('s1',${t0 + 10},60000),('s1',${t0 + 20},30000),('s5',${t0 + 30},45000)",
        )
        sql.forEach { conn.execSQL(it) }
    } finally {
        conn.close()
    }
}

private fun song(id: String, title: String, dur: Int, liked: Int = 0, inLib: Long? = null, local: Int = 0, downloaded: Int = 0) =
    "INSERT INTO song (id,title,duration,liked,likedDate,totalPlayTime,inLibrary,isLocal,isDownloaded,dateDownload,thumbnailUrl,albumId,albumName) VALUES " +
        "('$id','$title',$dur,$liked,${if (liked == 1) inLib else "NULL"},0,${inLib ?: "NULL"},$local,$downloaded,${if (downloaded == 1) inLib else "NULL"},'https://img/$id','AL1','Album One')"

class ImporterTest {
    private fun tempFile(suffix: String) = File.createTempFile("wsmusic-test", suffix).also { it.delete(); it.deleteOnExit() }

    @Test
    fun importsLikesPlaylistsAndHistory() = runBlocking {
        val android = tempFile(".db").also(::buildAndroidDb)
        val dbFile = tempFile(".db")
        val db = LibraryDatabase.open(dbFile)
        val r = LibraryRepository(db)
        val result = AndroidImporter(db).import(android)
        println("RESULT: " + result.summary() + "  (source schema version ${result.sourceVersion})")

        assertEquals(6, result.songsAdded, "s1-s5 + s8 (s6 is local-only, s7 is unreferenced)")
        assertEquals(3, result.likedSongs, "s1, s2, s8 are liked and playable")
        assertEquals(2, result.playlistsAdded, "Gym + Synced; hidden and system playlists skipped")
        assertEquals(3, result.playlistSongsAdded, "Gym: s1,s4  Synced: s2  (local file skipped)")
        assertEquals(3, result.playsAdded)
        assertEquals(1, result.skippedLocalSongs)

        val liked = r.likedSongs.first()
        assertEquals(setOf("s1", "s2", "s8"), liked.map { it.song.id }.toSet())
        assertEquals(listOf("Artist One", "Artist Two"), liked.first { it.song.id == "s1" }.artists.map { it.name }, "artist order preserved")
        assertEquals("Album One", liked.first { it.song.id == "s1" }.album?.title)
        assertEquals(false, liked.first { it.song.id == "s8" }.song.isDownloaded, "download flag reset on desktop")
        assertEquals(setOf("s1", "s2", "s3", "s8"), r.librarySongs.first().map { it.song.id }.toSet())

        val playlists = r.playlists.first()
        assertEquals(setOf("Gym", "Synced from YouTube"), playlists.map { it.playlist.name }.toSet())
        val gym = playlists.first { it.playlist.name == "Gym" }
        assertEquals(listOf("s1", "s4"), r.playlistSongs(gym.playlist.id).first().map { it.song.song.id })
        assertEquals("VLPL123", playlists.first { it.playlist.name != "Gym" }.playlist.browseId)

        val history = r.history().first()
        assertEquals(listOf("s5", "s1", "s1"), history.map { it.song.song.id }, "newest first")
        assertEquals(60_000L, history.last().event.playTime)

        // Importing the same file again changes nothing.
        val again = AndroidImporter(db).import(android)
        assertEquals(0, again.songsAdded); assertEquals(0, again.playlistsAdded)
        assertEquals(0, again.playlistSongsAdded); assertEquals(0, again.playsAdded)
        assertEquals(3, r.likedSongs.first().size)
        assertEquals(3, r.history().first().size)
        db.close()
    }

    @Test
    fun mergesLikesIntoExistingSongsAndKeepsLocalData() = runBlocking {
        val android = tempFile(".db").also(::buildAndroidDb)
        val db = LibraryDatabase.open(tempFile(".db"))
        val r = LibraryRepository(db)
        // Desktop already knows s2 (not liked) and has its own liked song + playlist.
        r.save(SongItem("s2", "Song Two", listOf(Artist("Artist Three", "A3")), null, 210, null, null, null, "https://img/s2"))
        val mine = SongItem("mine", "Desktop Only", listOf(Artist("Me", null)), null, 100, null, null, null, "")
        r.toggleLike(mine)
        r.createPlaylist("Desktop list")

        val result = AndroidImporter(db).import(android)
        assertEquals(5, result.songsAdded, "s2 already existed")
        assertTrue(r.likedSongs.first().any { it.song.id == "s2" }, "phone like merged into existing song")
        assertTrue(r.likedSongs.first().any { it.song.id == "mine" }, "desktop like kept")
        assertEquals(3, r.playlists.first().size, "desktop playlist kept next to the two imported")
        db.close()
    }

    @Test
    fun readsBackupZip() = runBlocking {
        val android = tempFile(".db").also(::buildAndroidDb)
        val zip = tempFile(".zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("settings.preferences_pb")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
            z.putNextEntry(ZipEntry("song.db")); z.write(android.readBytes()); z.closeEntry()
        }
        val db = LibraryDatabase.open(tempFile(".db"))
        val result = AndroidImporter(db).import(zip)
        assertEquals(3, result.likedSongs)
        db.close()
    }

    @Test
    fun rejectsOtherFiles() = runBlocking {
        val notIt = tempFile(".db")
        BundledSQLiteDriver().open(notIt.absolutePath).also { it.execSQL("CREATE TABLE unrelated (x INTEGER)"); it.close() }
        val db = LibraryDatabase.open(tempFile(".db"))
        val e = assertFailsWith<ImportException> { AndroidImporter(db).import(notIt) }
        println("REJECTED: " + e.message)
        val text = tempFile(".txt").also { it.writeText("hello") }
        assertFailsWith<Exception> { AndroidImporter(db).import(text) }
        db.close()
    }
}
