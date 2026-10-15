package echopc

import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import echopc.db.Feedback
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import echopc.db.StartReason
import echopc.db.artistKey
import echopc.reco.CandidateSource
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Feedback actions and Smart Shuffle through the real Controller and database (no network, no audio). */
class FeedbackFlowTest {
    private fun s(id: String, artist: String) = SongItem(id = id, title = "Song $id", artists = listOf(Artist(artist, "UC_$artist")), duration = 200, thumbnail = "")

    private class Source(val related: List<SongItem>) : CandidateSource {
        var calls = 0
        override suspend fun related(seed: SongItem): List<SongItem> { calls++; return related }
        override suspend fun recentReleases(artistId: String) = emptyList<SongItem>()
    }

    private class Setup(val c: Controller, val repo: LibraryRepository, val engine: FakeEngine, val source: Source, val scope: TestScope) {
        fun close() { scope.stop(); repo.close() }
    }

    private fun setup(related: List<SongItem> = emptyList()): Setup {
        val repo = LibraryRepository(LibraryDatabase.open(File.createTempFile("ws-fb", ".db").also { it.delete(); it.deleteOnExit() }))
        val engine = FakeEngine()
        val source = Source(related)
        val scope = TestScope()
        val c = Controller(scope, repo, null, recoSource = source) { _, _ -> engine }
        c.autoplay = false
        return Setup(c, repo, engine, source, scope)
    }

    private suspend fun until(what: String, check: suspend () -> Boolean) = withTimeout(8_000) { while (!check()) delay(25) }.also { println("ok: $what") }

    @Test
    fun banningAnArtistSkipsAndUnqueuesThem() = runBlocking {
        val t = setup()
        val songs = listOf(s("a", "Alpha"), s("b", "Beta"), s("c", "Alpha"), s("d", "Gamma"))
        t.c.playFromList(songs, 0)
        t.c.banArtist(songs[0])
        assertEquals("b", t.c.queue.current?.id, "the banned artist's song stops")
        assertEquals(listOf("a", "b", "d"), t.c.queue.items.map { it.id }, "their later songs leave the queue")
        until("ban stored") { t.c.blocks.bannedArtists == setOf("UC_Alpha") }
        // Undo from the toast.
        t.c.runToastAction()
        until("ban undone") { t.c.blocks.bannedArtists.isEmpty() }
        t.close()
    }

    @Test
    fun hiddenSongsAreSkippedOnlyWhereHidden() = runBlocking {
        val t = setup()
        val songs = listOf(s("a", "Alpha"), s("b", "Beta"), s("c", "Gamma"))
        t.c.hideInContext(songs[1], "local:p1")
        until("hide stored") { "b" in t.c.blocks.hidden["local:p1"].orEmpty() }

        t.c.playFromList(songs, 1, context = "local:p1") // clicking the hidden song plays the next playable one
        assertEquals("c", t.c.queue.current?.id)
        t.c.playFromList(songs, 1, context = "liked") // elsewhere it plays normally
        assertEquals("b", t.c.queue.current?.id)
        assertEquals(listOf("c", "b"), t.engine.played)
        t.close()
    }

    @Test
    fun notInterestedAndExcludeAreStored() = runBlocking {
        val t = setup()
        val song = s("x", "Alpha")
        t.c.notInterested(song)
        t.c.toggleExcludeFromTaste(song)
        until("stored") { t.repo.feedbackNow().map { it.kind }.toSet() == setOf(Feedback.Kind.NOT_INTERESTED, Feedback.Kind.EXCLUDE_FROM_TASTE) }
        until("blocks updated") { song.id in t.c.blocks.notInterested && song.id in t.c.blocks.excluded }
        t.c.toggleExcludeFromTaste(song)
        until("exclude toggled off") { song.id !in t.c.blocks.excluded }
        t.close()
    }

    @Test
    fun smartShuffleMixesInRecommendationsAndRemovesThemWhenTurnedOff() = runBlocking {
        val mine = List(9) { s("m$it", "Mine$it") }
        val related = List(12) { s("r$it", "Rec$it") } + mine.take(2) // songs already in the list are never recommended
        val t = setup(related)
        t.c.navigate(Route.LocalPlaylist("p1"))
        val ctx = routeContext(t.c.route)
        assertEquals(ShuffleMode.OFF, t.c.shuffleMode(ctx))
        t.c.cycleShuffle(ctx); t.c.cycleShuffle(ctx) // off -> shuffle -> Smart Shuffle
        assertEquals(ShuffleMode.SMART, t.c.shuffleMode(ctx))

        t.c.playCollection(mine)
        until("recommendations added") { t.c.queue.recommended.isNotEmpty() }
        val q = t.c.queue
        // 8 upcoming songs: a recommendation after the 3rd and the 6th.
        assertEquals(2, q.recommended.size, "about one per three songs: ${q.items.map { it.id }}")
        assertTrue(q.recommended.all { it.startsWith("r") })
        assertEquals(9 + 2, q.items.size)
        assertEquals(mine.map { it.id }.toSet(), q.items.map { it.id }.filter { !it.startsWith("r") }.toSet(), "every song of the playlist is in the shuffle, wherever it started")

        // Remove one: it leaves the queue and won't be suggested for this playlist again.
        val recPos = q.items.indexOfFirst { it.id in q.recommended && q.items.indexOf(it) > q.index }
        val removed = q.items[recPos].id
        t.c.removeRecommendation(recPos)
        assertTrue(t.c.queue.items.none { it.id == removed })
        until("removal remembered") { removed in t.c.blocks.hidden[ctx].orEmpty() }

        // Shuffle modes are per playlist: another list is unaffected.
        t.c.playFromList(mine, 0, context = "local:other")
        assertEquals(ShuffleMode.OFF, t.c.shuffleMode())
        assertTrue(t.c.queue.recommended.isEmpty())

        // Turning Smart Shuffle off on the first playlist restores its own order.
        t.c.playFromList(mine, 0, context = ctx)
        until("recommendations again") { t.c.queue.recommended.isNotEmpty() }
        assertTrue(removed !in t.c.queue.items.map { it.id }, "a removed recommendation stays away")
        t.c.setShuffle(ctx, ShuffleMode.OFF)
        assertTrue(t.c.queue.recommended.isEmpty())
        assertEquals(mine.map { it.id }.toSet(), t.c.queue.items.map { it.id }.toSet(), "back to the playlist's own songs")
        t.close()
    }

    @Test
    fun keepingARecommendationAddsItToThePlaylist() = runBlocking {
        val mine = List(10) { s("m$it", "Mine$it") }
        val t = setup(List(8) { s("r$it", "Rec$it") })
        val playlist = t.repo.createPlaylist("Road trip")
        mine.forEach { t.repo.addToPlaylist(playlist.id, it) }
        val ctx = "local:${playlist.id}"
        t.c.setShuffle(ctx, ShuffleMode.SMART)
        t.c.playFromList(mine, 0, context = ctx)
        until("recommendations added") { t.c.queue.recommended.isNotEmpty() }
        assertTrue(t.c.canKeepRecommendations)
        val pos = t.c.queue.items.indexOfFirst { it.id in t.c.queue.recommended }
        val kept = t.c.queue.items[pos]
        t.c.addRecommendationToPlaylist(pos)
        assertTrue(kept.id !in t.c.queue.recommended)
        until("in the playlist") { t.repo.playlistSongs(playlist.id).first().any { it.song.song.id == kept.id } }

        // Playing a recommendation is logged as a Smart Shuffle start.
        val rec = t.c.queue.items.indexOfFirst { it.id in t.c.queue.recommended }
        t.c.jumpTo(rec)
        t.c.skipNext()
        until("signal") { t.repo.signalsSince(LocalDateTime.now().minusDays(1)).any { it.startReason == StartReason.SMART_SHUFFLE } }
        t.close()
    }

    @Test
    fun smartShuffleRecommendationsAreCachedPerDay() = runBlocking {
        val t = setup(List(10) { s("r$it", "Rec$it") } + s("bad", "Banned"))
        t.repo.addFeedback(s("bad", "Banned").artistKey(), Feedback.Kind.BAN_ARTIST)
        val list = List(4) { s("m$it", "Mine") }
        val first = t.c.recommender.smartShuffle("liked", list, 3)
        val calls = t.source.calls
        val second = t.c.recommender.smartShuffle("liked", list, 3)
        assertEquals(first, second)
        assertEquals(calls, t.source.calls, "second call served from today's cache")
        assertTrue(first.none { it.id == "bad" })
        t.close()
    }
}
