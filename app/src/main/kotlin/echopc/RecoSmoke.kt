package echopc

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import echopc.db.EndReason
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import echopc.db.StartReason
import echopc.db.TrackCodec
import echopc.reco.Recommender
import echopc.reco.YouTubeCandidates
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Builds real mixes from YouTube Music in a throwaway database: a fake listening history from a few
 * searches, then a full recommender run. `gradlew :app:smoke -Pmain=echopc.RecoSmokeKt`
 */
fun main(): Unit = runBlocking {
    YouTube.visitorData().onSuccess { YouTube.visitorData = it }
    val db = File.createTempFile("wsmusic-reco-smoke", ".db").also { it.delete(); it.deleteOnExit() }
    val repo = LibraryRepository(LibraryDatabase.open(db))
    val queries = listOf("the weeknd blinding lights", "dua lipa levitating", "daft punk get lucky", "arijit singh tum hi ho", "a r rahman jai ho")
    for (q in queries) {
        val songs = YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty().take(3)
        songs.forEachIndexed { i, s ->
            repeat(3 - i) { repo.recordSignal(s, 200_000, 200_000, StartReason.CLICK_ROW, EndReason.TRACK_DONE, "search") }
        }
        songs.firstOrNull()?.let { repo.toggleLike(it) }
        println("seeded '$q': ${songs.joinToString { "${it.title} / ${it.artists.firstOrNull()?.name} (${it.artists.firstOrNull()?.id})" }}")
    }
    val t0 = System.currentTimeMillis()
    val rebuilt = Recommender(repo, YouTubeCandidates(repo)).refresh(force = true)
    println("rebuilt $rebuilt in ${System.currentTimeMillis() - t0} ms")
    for (mix in repo.mixesNow()) {
        val songs = TrackCodec.decode(mix.songs)
        println("== ${mix.title} (${songs.size}) - ${mix.subtitle}")
        songs.take(6).forEach { println("   ${it.title} - ${it.artists.joinToString { a -> a.name }}") }
    }
    repo.close()
    kotlin.system.exitProcess(0)
}
