package echopc.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import echo.music.iad1tya.db.entities.AlbumArtistMap
import echo.music.iad1tya.db.entities.AlbumEntity
import echo.music.iad1tya.db.entities.ArtistEntity
import echo.music.iad1tya.db.entities.Event
import echo.music.iad1tya.db.entities.EventWithSong
import echo.music.iad1tya.db.entities.LyricsEntity
import echo.music.iad1tya.db.entities.Playlist
import echo.music.iad1tya.db.entities.PlaylistEntity
import echo.music.iad1tya.db.entities.PlaylistSong
import echo.music.iad1tya.db.entities.PlaylistSongMap
import echo.music.iad1tya.db.entities.SearchHistory
import echo.music.iad1tya.db.entities.Song
import echo.music.iad1tya.db.entities.SongAlbumMap
import echo.music.iad1tya.db.entities.SongArtistMap
import echo.music.iad1tya.db.entities.SongEntity
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow

/**
 * Desktop subset of the Android app's `DatabaseDao`. Queries are the app's own SQL (same table
 * and column names); only the blocking functions became `suspend`, as Room needs outside Android.
 */
@Dao
abstract class LibraryDao {
    // ---- songs ----

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY inLibrary DESC")
    abstract fun librarySongs(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY likedDate DESC")
    abstract fun likedSongs(): Flow<List<Song>>

    @Query("SELECT id FROM song WHERE liked")
    abstract fun likedIds(): Flow<List<String>>

    @Query("SELECT * FROM song WHERE id = :id")
    abstract suspend fun getSong(id: String): SongEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(song: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(artist: ArtistEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(album: AlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(map: SongArtistMap)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(map: SongAlbumMap)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(map: AlbumArtistMap)

    @Update
    abstract suspend fun update(song: SongEntity)

    /** Inserts a song with its artists/album if missing (existing rows are left untouched). */
    @Transaction
    open suspend fun upsertSongGraph(song: SongEntity, artists: List<ArtistEntity>, album: AlbumEntity?) {
        insertIgnore(song)
        artists.forEachIndexed { i, artist ->
            insertIgnore(artist)
            insert(SongArtistMap(songId = song.id, artistId = artist.id, position = i))
        }
        if (album != null) {
            insertIgnore(album)
            insert(SongAlbumMap(songId = song.id, albumId = album.id, index = 0))
        }
    }

    @Query("SELECT * FROM song_artist_map WHERE songId IN (:songIds)")
    abstract suspend fun artistMaps(songIds: List<String>): List<SongArtistMap>

    @Query("UPDATE song SET inLibrary = NULL, liked = 0, likedDate = NULL WHERE id = :id")
    abstract suspend fun removeFromLibrary(id: String)

    @Query("UPDATE song SET totalPlayTime = totalPlayTime + :ms WHERE id = :id")
    abstract suspend fun addPlayTime(id: String, ms: Long)

    // ---- play history ----

    @Insert
    abstract suspend fun insert(event: Event): Long

    @Transaction
    @Query("SELECT * FROM event ORDER BY timestamp DESC LIMIT :limit")
    abstract fun history(limit: Int): Flow<List<EventWithSong>>

    @Query("DELETE FROM event")
    abstract suspend fun clearHistory()

    // ---- playlists ----

    @Transaction
    @Query(
        "SELECT *, (SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = playlist.id) AS songCount FROM playlist WHERE bookmarkedAt IS NOT NULL ORDER BY isPinned DESC, createdAt DESC",
    )
    abstract fun playlists(): Flow<List<Playlist>>

    @Transaction
    @Query(
        "SELECT *, (SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = playlist.id) AS songCount FROM playlist WHERE id = :playlistId",
    )
    abstract fun playlist(playlistId: String): Flow<Playlist?>

    @Insert
    abstract suspend fun insert(playlist: PlaylistEntity)

    @Update
    abstract suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlist WHERE id = :id")
    abstract suspend fun deletePlaylist(id: String)

    @Query("UPDATE playlist SET name = :name, lastUpdateTime = :time WHERE id = :id")
    abstract suspend fun renamePlaylist(id: String, name: String, time: LocalDateTime)

    @Transaction
    @Query("SELECT * FROM playlist_song_map WHERE playlistId = :playlistId ORDER BY position")
    abstract fun playlistSongs(playlistId: String): Flow<List<PlaylistSong>>

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_song_map WHERE playlistId = :playlistId")
    abstract suspend fun nextPosition(playlistId: String): Int

    @Query("SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = :playlistId AND songId = :songId")
    abstract suspend fun countInPlaylist(playlistId: String, songId: String): Int

    @Insert
    abstract suspend fun insert(map: PlaylistSongMap)

    @Query("DELETE FROM playlist_song_map WHERE id = :mapId")
    abstract suspend fun removeFromPlaylist(mapId: Int)

    // ---- search history ----

    @Query("SELECT * FROM search_history ORDER BY id DESC LIMIT :limit")
    abstract fun searchHistory(limit: Int): Flow<List<SearchHistory>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(history: SearchHistory): Long

    @Query("DELETE FROM search_history WHERE query = :query")
    abstract suspend fun deleteSearch(query: String)

    @Query("DELETE FROM search_history")
    abstract suspend fun clearSearchHistory()

    // ---- lyrics cache ----

    @Query("SELECT * FROM lyrics WHERE id = :id")
    abstract suspend fun lyrics(id: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(lyrics: LyricsEntity)
}
