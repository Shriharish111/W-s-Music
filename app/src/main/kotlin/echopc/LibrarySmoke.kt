package echopc

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import echopc.db.toSongItem
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
    val file = File.createTempFile("echo-library-test", ".db").also { it.delete(); it.deleteOnExit() }
    fun check(label: String, ok: Boolean) { println((if (ok) "PASS  " else "FAIL  ") + label); if (!ok) failures++ }

    val songs = YouTube.search("daft punk", YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.filterIsInstance<SongItem>().take(3)
    println("songs: " + songs.joinToString { it.title.take(25) })

    var repo = LibraryRepository(LibraryDatabase.open(file))
    check("starts empty", repo.likedSongs.first().isEmpty() && repo.playlists.first().isEmpty())

    repo.toggleLike(songs[0])
    var liked = repo.likedSongs.first()
    check("like saves song + marks liked", liked.size == 1 && liked[0].song.id == songs[0].id && liked[0].song.liked)
    println("  expected artists: " + songs[0].artists.joinToString { it.name } + " | album: " + songs[0].album?.name)
    println("  stored artists:   " + liked[0].artists.joinToString { it.name } + " | album: " + liked[0].album?.title)
    check("artists/album stored", liked[0].artists.isNotEmpty() && liked[0].artists.first().name == songs[0].artists.first().name)
    check("also in library", repo.librarySongs.first().size == 1)
    check("round trip to SongItem", liked[0].toSongItem().let { it.id == songs[0].id && it.title == songs[0].title && it.artists.size == songs[0].artists.size })

    repo.toggleLike(songs[0])
    check("unlike", repo.likedSongs.first().isEmpty())
    repo.toggleLike(songs[0])

    val pl = repo.createPlaylist("Road trip")
    check("playlist created", repo.playlists.first().let { it.size == 1 && it[0].playlist.name == "Road trip" })
    check("add song 1", repo.addToPlaylist(pl.id, songs[1]))
    check("add song 2", repo.addToPlaylist(pl.id, songs[2]))
    check("duplicate rejected", !repo.addToPlaylist(pl.id, songs[1]))
    val inPl = repo.playlistSongs(pl.id).first()
    check("playlist order + contents", inPl.map { it.song.song.id } == listOf(songs[1].id, songs[2].id))
    check("song count in list", repo.playlists.first()[0].songCount == 2)
    repo.removeFromPlaylist(inPl[0].map)
    check("remove from playlist", repo.playlistSongs(pl.id).first().map { it.song.song.id } == listOf(songs[2].id))
    repo.renamePlaylist(pl.id, "Road trip 2")
    check("rename", repo.playlists.first()[0].playlist.name == "Road trip 2")

    repo.recordPlay(songs[1], 45_000)
    repo.recordPlay(songs[1], 30_000)
    val hist = repo.history().first()
    check("history newest first, 2 events", hist.size == 2 && hist[0].song.song.id == songs[1].id)
    check("total play time summed", repo.history().first()[0].song.song.totalPlayTime == 75_000L)

    repo.rememberSearch("daft punk"); repo.rememberSearch("adele"); repo.rememberSearch("daft punk")
    check("search history dedup + order", repo.searchHistory().first().map { it.query } == listOf("daft punk", "adele"))

    repo.cacheLyrics(songs[0].id, "[00:01.00]hello", "LrcLib")
    check("lyrics cache", repo.cachedLyrics(songs[0].id)?.provider == "LrcLib")

    repo.close()
    repo = LibraryRepository(LibraryDatabase.open(file))
    check("persists after reopen: liked", repo.likedSongs.first().map { it.song.id } == listOf(songs[0].id))
    check("persists after reopen: playlist + songs", repo.playlists.first().let { it.size == 1 && it[0].songCount == 1 })
    check("persists after reopen: history", repo.history().first().size == 2)

    repo.deletePlaylist(pl.id)
    check("delete playlist cascades", repo.playlists.first().isEmpty() && repo.librarySongs.first().size == 1)
    repo.removeFromLibrary(songs[0].id)
    check("remove from library", repo.librarySongs.first().isEmpty() && repo.likedSongs.first().isEmpty())
    repo.close()

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

private var failures = 0

