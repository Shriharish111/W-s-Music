package echopc

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** Headless check: search -> queue -> play -> prefetch -> instant next -> radio. */
fun main(args: Array<String>): Unit = runBlocking {
    val q = args.firstOrNull() ?: "daft punk get lucky"
    val songs = YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrElse { println("SEARCH FAILED: $it"); kotlin.system.exitProcess(1) }
        .items.filterIsInstance<SongItem>()
    println("SEARCH OK: ${songs.size} songs")

    var last = PlayerState()
    val player = DesktopPlayer({ last = it })
    suspend fun waitPlaying(label: String): Long {
        val t0 = System.currentTimeMillis()
        while (!last.playing && last.error == null && System.currentTimeMillis() - t0 < 90_000) delay(50)
        val ms = System.currentTimeMillis() - t0
        println("$label: time-to-play=${ms}ms playing=${last.playing} duration=${last.durationMs} error=${last.error}")
        return ms
    }

    var queue = QueueState().withItems(songs, 0)
    println("up next: ${queue.upNext?.title}")
    player.play(queue.current!!.id, queue.upNext?.id)
    waitPlaying("track 1 (cold)")
    delay(8000) // give the prefetch time to finish
    println("position after 8s: ${last.positionMs}ms")

    queue = queue.jump(queue.nextIndex(false)!!)
    player.play(queue.current!!.id, queue.upNext?.id)
    waitPlaying("track 2 (prefetched)")
    delay(2000)

    val radio = fetchRadio(queue.current!!)
    println("RADIO: ${radio.size} songs, first: ${radio.firstOrNull()?.title}")

    queue = queue.copy(repeat = RepeatMode.ALL).jump(queue.items.lastIndex)
    println("repeat-all next index from last: ${queue.nextIndex(true)} (expect 0)")
    player.stop()
    kotlin.system.exitProcess(if (last.error == null) 0 else 1)
}

