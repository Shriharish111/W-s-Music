package echopc

import com.music.innertube.YouTube
import com.music.innertube.models.*
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
    YouTube.visitorData().onSuccess { YouTube.visitorData = it }
    fun kind(i: YTItem) = i::class.simpleName
    val home = YouTube.home()
    home.onSuccess { h ->
        println("HOME ok: ${h.sections.size} sections, chips=${h.chips?.map { it.title }}, continuation=${h.continuation != null}")
        h.sections.take(12).forEach { s -> println("  [${s.title}] ${s.items.size} items: ${s.items.groupingBy { kind(it) }.eachCount()}  e.g. ${s.items.firstOrNull()?.title}") }
    }.onFailure { println("HOME failed: $it") }
    YouTube.explore().onSuccess { e -> println("EXPLORE ok: ${e.newReleaseAlbums.size} new albums, ${e.moodAndGenres.size} moods; e.g. ${e.newReleaseAlbums.firstOrNull()?.title}") }.onFailure { println("EXPLORE failed: $it") }
    YouTube.getChartsPage().onSuccess { c -> println("CHARTS ok: " + c.sections.joinToString { "${it.title}(${it.items.size})" }) }.onFailure { println("CHARTS failed: $it") }
    // Drill-down: first playlist and first album found on home
    val items = home.getOrNull()?.sections?.flatMap { it.items }.orEmpty()
    items.filterIsInstance<PlaylistItem>().firstOrNull()?.let { p ->
        YouTube.playlist(p.id).onSuccess { pp -> println("PLAYLIST '${p.title}' ok: ${pp.songs.size} songs, first=${pp.songs.firstOrNull()?.title}") }.onFailure { println("PLAYLIST failed: $it") }
    } ?: println("no playlist item on home")
    items.filterIsInstance<AlbumItem>().firstOrNull()?.let { al ->
        YouTube.album(al.browseId).onSuccess { ap -> println("ALBUM '${al.title}' ok: ${ap.songs.size} songs") }.onFailure { println("ALBUM failed: $it") }
    } ?: println("no album item on home")
    kotlin.system.exitProcess(0)
}
