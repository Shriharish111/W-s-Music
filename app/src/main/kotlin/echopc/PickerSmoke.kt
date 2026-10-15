package echopc

import com.music.innertube.YouTube
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import echopc.db.TastePick
import echopc.db.TrackCodec
import echopc.reco.Recommender
import echopc.reco.YouTubeCandidates
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking

/**
 * The new-user path against live YouTube Music, in a throwaway database: "Popular right now", the artist
 * picker's suggestions, search and similar artists, then picks turned into mixes (with no listening at all).
 * `gradlew :app:smoke -Pmain=echopc.PickerSmokeKt`
 */
fun main(): Unit = runBlocking {
    YouTube.visitorData().onSuccess { YouTube.visitorData = it }
    val db = File.createTempFile("wsmusic-picker-smoke", ".db").also { it.delete(); it.deleteOnExit() }
    val repo = LibraryRepository(LibraryDatabase.open(db))
    val discovery = YouTubeDiscovery(repo)

    var t = System.currentTimeMillis()
    val popular = discovery.popularNow()
    println("POPULAR (${System.currentTimeMillis() - t} ms): '${popular?.title}' ${popular?.songs?.size} songs, e.g. ${popular?.songs?.take(3)?.joinToString { it.title + " / " + it.artists.firstOrNull()?.name }}")
    t = System.currentTimeMillis()
    discovery.popularNow()
    println("POPULAR again from cache: ${System.currentTimeMillis() - t} ms")

    t = System.currentTimeMillis()
    val suggested = discovery.suggestedArtists()
    println("SUGGESTED ARTISTS (${System.currentTimeMillis() - t} ms): ${suggested.size}, with photo=${suggested.count { it.thumbnail != null }}: ${suggested.take(10).joinToString { it.title }}")
    t = System.currentTimeMillis()
    discovery.suggestedArtists()
    println("SUGGESTED again from cache: ${System.currentTimeMillis() - t} ms")

    val found = discovery.searchArtists("coldplay")
    println("SEARCH 'coldplay': ${found.take(3).joinToString { it.title + " (" + it.id + ")" }}")
    val similar = found.firstOrNull()?.let { discovery.similarArtists(it.id) }.orEmpty()
    println("SIMILAR to ${found.firstOrNull()?.title}: ${similar.joinToString { it.title }}")

    // Picks only, no listening: what does a new user get?
    val picks = (listOfNotNull(found.firstOrNull()) + suggested.take(2)).distinctBy { it.id }
    repo.replaceTastePicks(picks.map { TastePick(it.id, it.title, it.thumbnail.orEmpty(), LocalDateTime.now()) })
    println("PICKED: ${picks.joinToString { it.title }}")
    t = System.currentTimeMillis()
    val rebuilt = Recommender(repo, YouTubeCandidates(repo)).refresh(force = true)
    println("rebuilt $rebuilt in ${System.currentTimeMillis() - t} ms")
    for (mix in repo.mixesNow()) {
        val songs = TrackCodec.decode(mix.songs)
        println("== ${mix.title} (${songs.size}) - ${mix.subtitle}")
        songs.take(5).forEach { println("   ${it.title} - ${it.artists.joinToString { a -> a.name }}") }
    }
    repo.close()
    kotlin.system.exitProcess(0)
}
