package echopc.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import echo.music.iad1tya.db.entities.AlbumArtistMap
import echo.music.iad1tya.db.entities.AlbumEntity
import echo.music.iad1tya.db.entities.ArtistEntity
import echo.music.iad1tya.db.entities.Event
import echo.music.iad1tya.db.entities.FormatEntity
import echo.music.iad1tya.db.entities.LyricsEntity
import echo.music.iad1tya.db.entities.PlayCountEntity
import echo.music.iad1tya.db.entities.PlaylistEntity
import echo.music.iad1tya.db.entities.PlaylistSongMap
import echo.music.iad1tya.db.entities.PlaylistSongMapPreview
import echo.music.iad1tya.db.entities.SearchHistory
import echo.music.iad1tya.db.entities.SongAlbumMap
import echo.music.iad1tya.db.entities.SongArtistMap
import echo.music.iad1tya.db.entities.SongEntity
import echo.music.iad1tya.db.entities.SortedSongAlbumMap
import echo.music.iad1tya.db.entities.SortedSongArtistMap
import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * Desktop library database. Same table/column definitions as the Android app's `InternalDatabase`
 * for the tables ported so far, but a fresh schema (version 1) with no migrations: the Android
 * file (schema v48, 21 tables) can't be opened directly because Room checks the full schema.
 */
@Database(
    entities = [
        SongEntity::class,
        ArtistEntity::class,
        AlbumEntity::class,
        PlaylistEntity::class,
        SongArtistMap::class,
        SongAlbumMap::class,
        AlbumArtistMap::class,
        PlaylistSongMap::class,
        SearchHistory::class,
        FormatEntity::class,
        LyricsEntity::class,
        Event::class,
        PlayCountEntity::class,
    ],
    views = [
        SortedSongArtistMap::class,
        SortedSongAlbumMap::class,
        PlaylistSongMapPreview::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        /** %APPDATA%\WsMusic\library.db (or ~/.wsmusic/library.db off Windows). Moves over the early "EchoMusicPC" test database once. */
        fun defaultFile(): File {
            val appData = System.getenv("APPDATA")
            val dir = if (appData != null) File(appData, "WsMusic") else File(System.getProperty("user.home"), ".wsmusic")
            dir.mkdirs()
            val target = File(dir, "library.db")
            if (!target.exists() && appData != null) {
                val legacy = File(File(appData, "EchoMusicPC"), "library.db")
                if (legacy.isFile) {
                    runCatching {
                        listOf("", "-wal", "-shm").forEach { ext ->
                            File(legacy.path + ext).takeIf { it.isFile }?.copyTo(File(target.path + ext), overwrite = true)
                        }
                    }
                }
            }
            return target
        }

        fun open(file: File = defaultFile()): LibraryDatabase =
            Room.databaseBuilder<LibraryDatabase>(name = file.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
