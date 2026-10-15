package echopc.reco

import com.music.innertube.models.SongItem
import echopc.db.Feedback
import echopc.db.artistKey
import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MixEngineTest {
    // Two separate taste worlds: synthwave (Alpha, Beta) and jazz (Delta, Echo).
    private val alpha = List(4) { song("al$it", "Alpha") }
    private val beta = List(4) { song("be$it", "Beta") }
    private val delta = List(4) { song("de$it", "Delta") }
    private val echo = List(4) { song("ec$it", "Echo") }
    private val known = alpha + beta + delta + echo

    /** Radios stay inside their world: synthwave seeds point at synthwave songs, jazz at jazz. */
    private fun world(): FakeSource {
        val newSynth = List(30) { song("ns$it", "SynthNew${it % 10}") }
        val newJazz = List(30) { song("nj$it", "JazzNew${it % 10}") }
        val related = HashMap<String, List<SongItem>>()
        for (s in alpha) related[s.id] = beta.take(2) + newSynth.shuffled(Random(s.id.hashCode()))
        for (s in beta) related[s.id] = alpha.take(2) + newSynth.shuffled(Random(s.id.hashCode()))
        for (s in delta) related[s.id] = echo.take(2) + newJazz.shuffled(Random(s.id.hashCode()))
        for (s in echo) related[s.id] = delta.take(2) + newJazz.shuffled(Random(s.id.hashCode()))
        return FakeSource(related)
    }

    private fun profile(fb: List<Feedback> = emptyList()): TasteProfile {
        val plays = known.flatMapIndexed { i, s -> List(3 + i % 3) { full(s, daysAgo = 1.0 + it) } }
        return TasteProfile.build(plays, emptyList(), emptyList(), fb, NOW)
    }

    @Test
    fun dailyMixesClusterSeparateTastes() = runBlocking {
        val engine = MixEngine(world(), known.associateBy { it.id })
        val mixes = engine.dailyMixes(profile())
        assertEquals(2, mixes.size, "one mix per taste cluster: ${mixes.map { it.subtitle }}")
        for (mix in mixes) {
            val artists = mix.songs.map { it.artists[0].name }.toSet()
            val synth = artists.any { it.startsWith("Alpha") || it.startsWith("Beta") || it.startsWith("Synth") }
            val jazz = artists.any { it.startsWith("Delta") || it.startsWith("Echo") || it.startsWith("Jazz") }
            assertTrue(synth xor jazz, "a mix must not mix worlds: $artists")
            // Familiar and new songs blended.
            assertTrue(mix.songs.any { it in known } && mix.songs.any { it !in known })
            assertDiverse(mix.songs)
        }
        assertEquals(listOf("Daily Mix 1", "Daily Mix 2"), mixes.map { it.title })
    }

    @Test
    fun discoverWeeklyOnlyHasNewUnblockedSongs() = runBlocking {
        val fb = listOf(Feedback("UC_SynthNew3", Feedback.Kind.BAN_ARTIST, "", LocalDateTime.now()), Feedback("ns0", Feedback.Kind.NOT_INTERESTED, "", LocalDateTime.now()))
        val p = profile(fb)
        val mix = MixEngine(world(), known.associateBy { it.id }).discoverWeekly(p, alpha + delta)!!
        assertTrue(mix.songs.size in 10..30)
        assertTrue(mix.songs.none { p.known(it.id) }, "never-played songs only")
        assertTrue(mix.songs.none { it.artistKey() == "UC_SynthNew3" || it.id == "ns0" })
        val perArtist = mix.songs.groupingBy { it.artistKey() }.eachCount()
        assertTrue(perArtist.values.all { it <= 2 }, "at most two per artist: $perArtist")
    }

    @Test
    fun releaseRadarRoundRobinsArtistsAndSkipsKnown() = runBlocking {
        val releases = mapOf(
            "UC_Alpha" to listOf(alpha[0]) + List(10) { song("ra$it", "Alpha") },
            "UC_Delta" to List(3) { song("rd$it", "Delta") },
        )
        val mix = MixEngine(FakeSource(emptyMap(), releases), known.associateBy { it.id }).releaseRadar(profile(), listOf("UC_Alpha", "UC_Delta"))!!
        assertTrue(alpha[0] !in mix.songs, "already-played songs are not 'new'")
        assertEquals(listOf("ra0", "rd0", "ra1", "rd1"), mix.songs.take(4).map { it.id })
    }

    @Test
    fun onRepeatAndRewind() {
        val engine = MixEngine(world(), known.associateBy { it.id })
        val recent = alpha.flatMap { s -> List(3) { full(s, daysAgo = 2.0) } } + beta.take(2).flatMap { s -> List(2) { full(s, daysAgo = 3.0) } }
        val old = delta.flatMap { s -> List(4) { full(s, daysAgo = 100.0) } } + echo.take(1).flatMap { s -> List(4) { full(s, daysAgo = 90.0) } }
        val p = TasteProfile.build(recent + old, emptyList(), emptyList(), emptyList(), NOW)
        val onRepeat = engine.onRepeat(recent + old, p, NOW)!!
        assertEquals(6, onRepeat.songs.size)
        assertTrue(onRepeat.songs.take(4).all { it in alpha }, "most played first")
        val rewind = engine.repeatRewind(p, NOW)!!
        assertEquals((delta + echo.take(1)).toSet(), rewind.songs.toSet())
    }

    @Test
    fun diversifyLimitsArtistRuns() {
        // The top-ranked artist dominates the ranking, but enough others exist to spread it out.
        val ranked = List(6) { song("x$it", "Solo") } + List(20) { song("y$it", "Other$it") }
        val out = MixEngine.diversify(ranked, limit = 20)
        assertEquals(20, out.size)
        assertDiverse(out)
        assertEquals(listOf("x0", "x1"), out.take(2).map { it.id }, "best songs still lead")
        // With no alternatives left it relaxes rather than dropping songs.
        assertEquals(12, MixEngine.diversify(List(6) { song("x$it", "Solo") } + List(6) { song("y$it", "Other$it") }, limit = 12).size)
    }

    private fun assertDiverse(songs: List<SongItem>) {
        songs.windowed(10, partialWindows = false).forEach { w ->
            val worst = w.groupingBy { it.artistKey() }.eachCount().values.max()
            assertTrue(worst <= 2, "more than two songs by one artist within ten: ${w.map { it.artists[0].name }}")
        }
    }
}
