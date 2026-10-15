@file:JvmName("SearchSmokeKt")

package echopc

import com.music.innertube.YouTube
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import kotlinx.coroutines.runBlocking

/** Live check of the calls behind the search tabs and the artist page: `gradlew :app:smoke -Pmain=echopc.SearchSmokeKt`. */
fun main(): Unit = runBlocking {
    val sum = YouTube.searchSummary("daft punk").getOrThrow()
    println("summary sections: " + sum.summaries.joinToString { "${it.title}(${it.items.size})" })
    val artist = sum.summaries.flatMap { it.items }.filterIsInstance<ArtistItem>().first()
    val page = YouTube.artist(artist.id).getOrThrow()
    println("artist page '${page.artist.title}': " + page.sections.joinToString { "${it.title}(${it.items.size})" })
    val playlist = sum.summaries.flatMap { it.items }.filterIsInstance<PlaylistItem>().first()
    val songs = YouTube.playlist(playlist.id).getOrThrow().songs
    println("playlist '${playlist.title}' opens with ${songs.size} songs")
    val vids = YouTube.search("daft punk", YouTube.SearchFilter.FILTER_VIDEO).getOrThrow()
    val more = YouTube.searchContinuation(vids.continuation!!).getOrThrow()
    println("videos page 1: ${vids.items.size}, page 2: ${more.items.size}, overlap: ${vids.items.map { it.id }.intersect(more.items.map { it.id }.toSet()).size}")
    kotlin.system.exitProcess(0)
}
