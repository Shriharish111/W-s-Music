package echopc.db

import com.music.innertube.models.Album
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
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
import echo.music.iad1tya.db.entities.SongEntity
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Converts a library row back into the model the player/queue uses. */
fun Song.toSongItem(): SongItem =
    SongItem(
        id = song.id,
        title = song.title,
        artists = artists.map { Artist(name = it.name, id = it.id.takeUnless { id -> id.startsWith("LA") }) },
        album = album?.let { Album(name = it.title, id = it.id) } ?: song.albumId?.let { Album(name = song.albumName.orEmpty(), id = it) },
        duration = song.duration.takeIf { it >= 0 },
        thumbnail = song.thumbnailUrl.orEmpty(),
        explicit = song.explicit,
    )

/**
 * Local-only library operations. Unlike the Android entities' `toggleLike()`, nothing here talks
 * to YouTube (no account is signed in on desktop yet), so liking is purely local.
 */
class LibraryRepository(private val db: LibraryDatabase) {
    private val dao = db.dao()

    // Room returns a song's artists in arbitrary order; restore the stored `position` order.
    private suspend fun List<Song>.withArtistOrder(): List<Song> {
        if (isEmpty()) return this
        val position = HashMap<Pair<String, String>, Int>()
        map { it.song.id }.distinct().chunked(500).forEach { ids ->
            dao.artistMaps(ids).forEach { position[it.songId to it.artistId] = it.position }
        }
        return map { s -> s.copy(artists = s.artists.sortedBy { position[s.song.id to it.id] ?: Int.MAX_VALUE }) }
    }

    val likedSongs: Flow<List<Song>> = dao.likedSongs().map { it.withArtistOrder() }
    val likedIds: Flow<List<String>> = dao.likedIds()
    val librarySongs: Flow<List<Song>> = dao.librarySongs().map { it.withArtistOrder() }
    val playlists: Flow<List<Playlist>> = dao.playlists()
    fun history(limit: Int = 200): Flow<List<EventWithSong>> =
        dao.history(limit).map { events ->
            val ordered = events.map { it.song }.withArtistOrder()
            events.mapIndexed { i, e -> e.copy(song = ordered[i]) }
        }
    fun playlistSongs(playlistId: String): Flow<List<PlaylistSong>> =
        dao.playlistSongs(playlistId).map { rows ->
            val ordered = rows.map { it.song }.withArtistOrder()
            rows.mapIndexed { i, row -> row.copy(song = ordered[i]) }
        }
    fun playlist(playlistId: String): Flow<Playlist?> = dao.playlist(playlistId)
    fun searchHistory(limit: Int = 12): Flow<List<SearchHistory>> = dao.searchHistory(limit)

    /** Makes sure [item] exists in the database (with artists and album). */
    suspend fun save(item: SongItem): SongEntity {
        dao.getSong(item.id)?.let { return it }
        val now = LocalDateTime.now()
        val entity = SongEntity(
            id = item.id,
            title = item.title,
            duration = item.duration ?: -1,
            thumbnailUrl = item.thumbnail,
            albumId = item.album?.id,
            albumName = item.album?.name,
            explicit = item.explicit,
            date = now,
            dateModified = now,
        )
        val artists = item.artists.map { ArtistEntity(id = it.id ?: ArtistEntity.generateArtistId(), name = it.name) }
        val album = item.album?.let { AlbumEntity(id = it.id, title = it.name, songCount = 1, duration = item.duration ?: 0) }
        dao.upsertSongGraph(entity, artists, album)
        return dao.getSong(item.id) ?: entity
    }

    suspend fun toggleLike(item: SongItem) {
        val song = save(item)
        val now = LocalDateTime.now()
        dao.update(
            song.copy(
                liked = !song.liked,
                likedDate = if (!song.liked) now else null,
                inLibrary = if (!song.liked) song.inLibrary ?: now else song.inLibrary,
            ),
        )
    }

    suspend fun addToLibrary(item: SongItem) {
        val song = save(item)
        if (song.inLibrary == null) dao.update(song.copy(inLibrary = LocalDateTime.now()))
    }

    suspend fun removeFromLibrary(songId: String) = dao.removeFromLibrary(songId)

    // ---- playlists ----

    suspend fun createPlaylist(name: String): PlaylistEntity {
        val playlist = PlaylistEntity(name = name.trim().ifBlank { "New playlist" }, bookmarkedAt = LocalDateTime.now(), isLocal = true)
        dao.insert(playlist)
        return playlist
    }

    suspend fun renamePlaylist(id: String, name: String) = dao.renamePlaylist(id, name.trim(), LocalDateTime.now())
    suspend fun deletePlaylist(id: String) = dao.deletePlaylist(id)

    /** @return false if the song was already in the playlist. */
    suspend fun addToPlaylist(playlistId: String, item: SongItem): Boolean {
        save(item)
        if (dao.countInPlaylist(playlistId, item.id) > 0) return false
        dao.insert(PlaylistSongMap(playlistId = playlistId, songId = item.id, position = dao.nextPosition(playlistId)))
        return true
    }

    suspend fun removeFromPlaylist(map: PlaylistSongMap) = dao.removeFromPlaylist(map.id)

    // ---- history ----

    suspend fun recordPlay(item: SongItem, listenedMs: Long) {
        save(item)
        dao.insert(Event(songId = item.id, timestamp = LocalDateTime.now(), playTime = listenedMs))
        dao.addPlayTime(item.id, listenedMs)
    }

    suspend fun clearHistory() = dao.clearHistory()

    // ---- search history ----

    suspend fun rememberSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        dao.deleteSearch(q) // move an existing entry to the top
        dao.insert(SearchHistory(query = q))
    }

    suspend fun clearSearchHistory() = dao.clearSearchHistory()

    // ---- lyrics cache ----

    suspend fun cachedLyrics(id: String): LyricsEntity? = dao.lyrics(id)
    suspend fun cacheLyrics(id: String, lyrics: String, provider: String) = dao.insert(LyricsEntity(id = id, lyrics = lyrics, provider = provider))

    /** Copies likes, library songs, playlists and history from an Android app backup / song.db. */
    suspend fun importAndroid(file: java.io.File): ImportResult = AndroidImporter(db).import(file)

    fun close() = db.close()
}
