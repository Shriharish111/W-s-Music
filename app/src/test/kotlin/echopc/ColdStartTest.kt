package echopc

import com.music.innertube.models.SongItem
import echopc.db.AndroidImporter
import echopc.db.EndReason
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import echopc.db.StartReason
import echopc.reco.DAY
import echopc.reco.FakeSource
import echopc.reco.MixEngine
import echopc.reco.MixKind
import echopc.reco.NOW
import echopc.reco.Recommender
import echopc.reco.Saved
import echopc.reco.TasteProfile
import echopc.reco.song
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** New and small-library users: what they get from the mixes, Smart Shuffle and imported history. */
class ColdStartTest {
    private fun tmpDb() = File.createTempFile("ws-cold", ".db").also { it.delete(); it.deleteOnExit() }
    private suspend fun until(what: String, check: suspend () -> Boolean) = withTimeout(10_000) { while (!check()) delay(25) }.also { println("ok: $what") }

    /** Radio lists made of songs by many different one-off artists, so a mix has enough new material. */
    private fun fresh(prefix: String, n: Int = 15) = List(n) { song("$prefix$it", "$prefix-artist$it") }

    // ------------------------------------------------------------ 1. Daily Mix for small libraries

    private fun likedProfile(vararg liked: SongItem) =
        TasteProfile.build(emptyList(), liked.map { Saved(it.id, "UC_${it.artists[0].name}", it.artists[0].name, NOW - DAY) }, emptyList(), emptyList(), NOW)

    @Test
    fun twoLikedSongsOfUnrelatedArtistsMakeOneDailyMix() = runBlocking {
        val a = song("a1", "Alpha"); val b = song("b1", "Beta")
        val engine = MixEngine(FakeSource(mapOf("a1" to fresh("na"), "b1" to fresh("nb"))), listOf(a, b).associateBy { it.id })
        val mixes = engine.dailyMixes(likedProfile(a, b))
        assertEquals(1, mixes.size, "one blended mix, not one per artist")
        assertEquals("Daily Mix 1", mixes[0].title)
        assertTrue(a in mixes[0].songs && b in mixes[0].songs, "both liked songs are in it")
        assertTrue(mixes[0].songs.size >= 10 && mixes[0].songs.any { it.id.startsWith("na") } && mixes[0].songs.any { it.id.startsWith("nb") })
    }

    @Test
    fun threeUnrelatedLikesNowGetAMixAndOneLikeStillDoesNot() = runBlocking {
        val songs = listOf(song("a1", "Alpha"), song("b1", "Beta"), song("c1", "Gamma"))
        val related = songs.associate { it.id to fresh(it.id + "n") }
        val three = MixEngine(FakeSource(related), songs.associateBy { it.id }).dailyMixes(likedProfile(*songs.toTypedArray()))
        assertEquals(1, three.size)
        val one = MixEngine(FakeSource(related), songs.associateBy { it.id }).dailyMixes(likedProfile(songs[0]))
        assertEquals(0, one.size, "a single song is a radio mix, not a Daily Mix")
    }

    // ------------------------------------------------------------ 2. Smart Shuffle on short lists

    @Test
    fun smartShuffleAddsARecommendationEvenToShortLists() {
        val recs = fresh("r", 5)
        fun smart(n: Int) = QueueState(List(n) { song("s$it", "S$it") }, index = 0).withSmartShuffle(recs)
        assertEquals(1, smart(3).recommended.size, "3 songs: 2 upcoming, one recommendation")
        assertEquals(listOf(false, false, false, true), smart(3).items.mapIndexed { i, _ -> smart(3).isRecommended(i) }.let { it })
        assertEquals(1, smart(2).recommended.size)
        assertEquals(1, smart(1).recommended.size, "a one-song list gets one after it")
        assertEquals("s0", smart(1).items.first().id)
        assertEquals(2, smart(9).recommended.size, "8 upcoming: unchanged, one after every three")
    }

    @Test
    fun smartShuffleToastIsAccurateAndAFailedFetchIsNotCached() = runBlocking {
        val repo = LibraryRepository(LibraryDatabase.open(tmpDb()))
        val scope = TestScope()
        var related: List<SongItem> = emptyList()
        val source = object : echopc.reco.CandidateSource {
            override suspend fun related(seed: SongItem) = related
            override suspend fun recentReleases(artistId: String) = emptyList<SongItem>()
        }
        val c = Controller(scope, repo, null, recoSource = source) { _, _ -> FakeEngine() }
        c.autoplay = false
        val mine = List(3) { song("m$it", "Mine$it") }
        val ctx = "local:short"
        c.setShuffle(ctx, ShuffleMode.SMART)

        c.playFromList(mine, 0, context = ctx) // nothing to recommend (e.g. offline)
        until("failure toast") { c.toast?.startsWith("Couldn't find Smart Shuffle recommendations") == true }
        assertTrue(c.queue.recommended.isEmpty())

        related = fresh("r", 6) // connection is back: today's empty result must not be remembered
        c.playFromList(mine, 0, context = ctx)
        until("success toast") { c.toast == "Added 1 Smart Shuffle recommendation to the queue" }
        assertEquals(1, c.queue.recommended.size)
        assertEquals(4, c.queue.items.size)
        scope.stop(); repo.close()
    }

    // ------------------------------------------------------------ 3. mixes on day one

    private suspend fun play(repo: LibraryRepository, song: SongItem, times: Int = 1) =
        repeat(times) { repo.recordSignal(song, 200_000, 200_000, StartReason.CLICK_ROW, EndReason.TRACK_DONE, "search") }

    @Test
    fun emptyBuildIsRetriedAfterFivePlaysTheSameDay() = runBlocking {
        val repo = LibraryRepository(LibraryDatabase.open(tmpDb()))
        val mine = listOf(song("m0", "Alpha"), song("m1", "Alpha"), song("m2", "Beta"), song("m3", "Beta"))
        val recommender = Recommender(repo, FakeSource(mine.associate { it.id to fresh(it.id + "n") }))

        assertTrue(recommender.refresh().isNotEmpty(), "first launch tries everything")
        assertEquals(emptyList(), repo.mixesNow().map { it.kind }, "...and a new user has nothing to build from")
        assertEquals(emptyList(), recommender.refresh(), "nothing changed: no pointless retry")

        mine.forEach { play(repo, it) } // 4 plays: not yet
        assertEquals(emptyList(), recommender.refresh())

        play(repo, mine[0]) // the 5th play
        val rebuilt = recommender.refresh()
        assertTrue(MixKind.DISCOVER in rebuilt && MixKind.DAILY in rebuilt, "rebuilt after 5 plays: $rebuilt")
        val kinds = repo.mixesNow().map { it.kind }.toSet()
        assertTrue(MixKind.DISCOVER in kinds && MixKind.DAILY in kinds, "mixes now exist: $kinds")
        repo.close()
    }

    @Test
    fun aFirstLikeAloneTriggersARetry() = runBlocking {
        val repo = LibraryRepository(LibraryDatabase.open(tmpDb()))
        val liked = song("l0", "Alpha")
        val recommender = Recommender(repo, FakeSource(mapOf("l0" to fresh("ln"))))
        recommender.refresh()
        assertEquals(emptyList(), recommender.refresh())
        repo.toggleLike(liked)
        assertTrue(MixKind.DISCOVER in recommender.refresh(), "liking a song is new evidence")
        assertTrue(repo.mixesNow().any { it.kind == MixKind.DISCOVER }, "Discover Weekly works from a single like")
        repo.close()
    }

    @Test
    fun controllerBuildsTheFirstMixesAfterFivePlays() = runBlocking {
        val repo = LibraryRepository(LibraryDatabase.open(tmpDb()))
        val scope = TestScope()
        lateinit var state: (PlayerState) -> Unit
        lateinit var ended: () -> Unit
        val songs = List(6) { song("p$it", if (it < 3) "Alpha" else "Beta") }
        val c = Controller(scope, repo, null, recoSource = FakeSource(songs.associate { it.id to fresh(it.id + "n") })) { st, en -> state = st; ended = en; FakeEngine() }
        c.autoplay = false
        c.playFromList(songs, 0)
        repeat(5) { // five full listens in a row
            state(PlayerState(playing = true, positionMs = 200_000, durationMs = 200_000)); ended()
            delay(30)
        }
        until("first mixes appear without a restart") { c.mixes.any { it.kind == MixKind.DISCOVER } }
        scope.stop(); repo.close()
    }

    // ------------------------------------------------------------ 4. imported history counts toward taste

    @Test
    fun importedHistoryBecomesTasteSignalsAndIsNotCountedTwice() = runBlocking {
        val db = LibraryDatabase.open(tmpDb())
        val repo = LibraryRepository(db)
        val android = File.createTempFile("ws-android", ".db").also { it.delete(); it.deleteOnExit(); buildAndroidDb(it) }
        AndroidImporter(db).import(android) // history: s1 twice (60 s, 30 s), s5 once (45 s)
        assertEquals(emptyList(), repo.signalsSince(LocalDateTime.now().minusYears(30)), "the import itself writes only the history table")

        repo.backfillSignalsFromHistory()
        val signals = repo.signalsSince(LocalDateTime.now().minusYears(30)) // all of them
        assertEquals(3, signals.size)
        val s1 = signals.filter { it.songId == "s1" }
        assertEquals(listOf(60_000L, 30_000L).sorted(), s1.map { it.playedMs }.sorted())
        assertTrue(s1.all { it.artistKey == "A1" && it.artistName == "Artist One" && it.durationMs == 200_000L && it.startReason == "import" })
        assertEquals("name:unknown", signals.single { it.songId == "s5" }.artistKey, "a song without artists still counts, under no artist")

        repo.backfillSignalsFromHistory() // safe to repeat
        assertEquals(3, repo.signalsSince(LocalDateTime.now().minusYears(30)).size)

        // A play made in this app is already a signal: its history entry is not turned into a second one.
        val s2 = repo.songsById(listOf("s2")).getValue("s2")
        repo.recordPlay(s2, 90_000)
        repo.recordSignal(s2, 90_000, 210_000, StartReason.CLICK_ROW, EndReason.TRACK_DONE, "liked")
        repo.backfillSignalsFromHistory()
        assertEquals(4, repo.signalsSince(LocalDateTime.now().minusYears(30)).size)

        // ...and the taste profile now knows the imported listening. (The fake history is from 2023 and the
        // profile only looks back a year, so evaluate it as of the day after those plays.)
        val asOf = s1.maxOf { it.timestamp }.plusDays(1)
        val inputs = Recommender(repo, FakeSource(emptyMap())).loadInputs(asOf)
        assertTrue(inputs.profile.artistAffinity("A1") > 0, "Artist One is a known taste")
        assertEquals(2, inputs.profile.tracks.getValue("s1").streams, "both imported plays of s1 were 30 s or more")
        repo.close()
    }
}
