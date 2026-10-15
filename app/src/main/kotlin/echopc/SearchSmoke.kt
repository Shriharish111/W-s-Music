@file:JvmName("SearchSmokeKt")

package echopc

import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import kotlinx.coroutines.runBlocking

private fun show(i: YTItem) = when (i) {
    is SongItem -> "song '${i.title}' - ${i.artists.joinToString { it.name }} [${i.id}]${if (i.isVideoSong) " (video)" else ""}"
    is AlbumItem -> "album '${i.title}'"
    is ArtistItem -> "artist '${i.title}'"
    is PlaylistItem -> "playlist '${i.title}'"
    else -> i.toString()
}

/** What the search shows first for exact titles: `gradlew :app:smoke -Pmain=echopc.SearchSmokeKt`. */
fun main(): Unit = runBlocking {
    for (q in listOf("ennavale adi ennavale", "drop dead olivia rodrigo", "blinding lights", "kadhal sadugudu", "vaathi coming")) {
        println("== $q")
        val sum = YouTube.searchSummary(q).getOrThrow()
        val top = sum.summaries.firstOrNull()
        println("   top section '${top?.title}': " + top?.items?.joinToString(" | ") { show(it) })
        println("   first of Songs: " + sum.summaries.firstOrNull { it.title == "Songs" }?.items?.take(3)?.joinToString(" | ") { show(it) })
        val ranked = SearchRank.apply(q, sum.summaries)
        println("   NOW SHOWN FIRST: " + ranked.summaries.first().items.first().let { show(it) } + "   | queue starts: " + ranked.songs.take(2).joinToString(" | ") { show(it) })
        println("   song filter first 3: " + YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrNull()?.items?.take(3)?.joinToString(" | ") { show(it) })
    }
    kotlin.system.exitProcess(0)
}
