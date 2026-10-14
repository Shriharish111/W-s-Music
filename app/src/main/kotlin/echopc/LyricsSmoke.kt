package echopc

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import echopc.lyrics.LyricsUtils
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>): Unit = runBlocking {
    val queries = if (args.isNotEmpty()) args.toList() else listOf("daft punk get lucky", "YOASOBI 夜に駆ける", "adele hello")
    for (q in queries) {
        println("\n=== $q")
        val song = YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrNull()?.items?.filterIsInstance<SongItem>()?.firstOrNull()
        if (song == null) { println("no search result"); continue }
        println("song: ${song.title} / ${song.artists.joinToString { it.name }} (${song.duration}s)")
        val t0 = System.currentTimeMillis()
        val state = LyricsRepository.load(song) { println("  [provider] $it") }
        println("took ${System.currentTimeMillis() - t0} ms -> ${state::class.simpleName}")
        if (state is LyricsUiState.Loaded) {
            println("provider=${state.provider} synced=${state.synced} lines=${state.lines.size} wordSync=${state.lines.any { it.words != null }}")
            state.lines.take(4).forEach { println("   ${it.time}ms: ${it.text}") }
            if (state.synced && state.lines.size > 5) {
                val pos = state.lines[5].time + 100
                println("   line index at ${pos}ms = ${LyricsUtils.findCurrentLineIndex(state.lines, pos)} (expect 5)")
            }
            val first = state.lines.firstOrNull { LyricsUtils.isJapanese(it.text) || LyricsUtils.isKorean(it.text) || LyricsUtils.isChinese(it.text) }
            if (first != null) println("   romanize: ${first.text}  ->  ${LyricsRepository.romanize(first.text)}")
        }
    }
    kotlin.system.exitProcess(0)
}
