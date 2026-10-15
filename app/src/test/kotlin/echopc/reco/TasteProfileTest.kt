package echopc.reco

import echopc.db.EndReason
import echopc.db.Feedback
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TasteProfileTest {
    private val a1 = song("a1", "Alpha")
    private val b1 = song("b1", "Beta")
    private val c1 = song("c1", "Gamma")

    @Test
    fun playWeightsFollowListeningBehaviour() {
        assertEquals(Weights.FULL_LISTEN, playWeight(full(a1)))
        assertEquals(Weights.EARLY_SKIP, playWeight(skip(a1)))
        val stream = Play("x", "k", "n", NOW, 45_000, 200_000, EndReason.FWD_BUTTON) // skipped, but after 30 s
        assertEquals(Weights.STREAM, playWeight(stream))
        val leftEarly = Play("x", "k", "n", NOW, 5_000, 200_000, EndReason.NEW_SELECTION) // picked something else
        assertEquals(0.0, playWeight(leftEarly))
    }

    @Test
    fun decayHalvesPerHalfLife() {
        assertEquals(1.0, decay(0, 7.0), 1e-9)
        assertEquals(0.5, decay(7 * DAY, 7.0), 1e-9)
        assertEquals(0.25, decay(14 * DAY, 7.0), 1e-9)
    }

    @Test
    fun recentListeningOutranksOldAndSkipsCountAgainst() {
        val plays = List(5) { full(a1, daysAgo = 1.0) } + // Alpha: lots, recently
            List(5) { full(b1, daysAgo = 200.0) } + // Beta: lots, long ago
            List(4) { skip(c1, daysAgo = 1.0) } // Gamma: keeps being skipped
        val p = TasteProfile.build(plays, emptyList(), emptyList(), emptyList(), NOW)
        assertTrue(p.artistAffinity("UC_Alpha") > p.artistAffinity("UC_Beta"))
        assertTrue(p.artistAffinity("UC_Gamma") < 0)
        assertEquals(listOf("UC_Alpha", "UC_Beta"), p.topArtists())
        assertEquals(4, p.tracks.getValue("c1").skips)
        assertEquals(5, p.tracks.getValue("a1").streams)
    }

    @Test
    fun feedbackBlocksAndExcludes() {
        val now = LocalDateTime.now()
        val fb = listOf(
            Feedback("a1", Feedback.Kind.NOT_INTERESTED, "", now),
            Feedback("UC_Beta", Feedback.Kind.BAN_ARTIST, "", now),
            Feedback("c1", Feedback.Kind.EXCLUDE_FROM_TASTE, "", now),
        )
        val plays = listOf(full(a1), full(b1), full(c1))
        val p = TasteProfile.build(plays, emptyList(), emptyList(), fb, NOW)
        assertTrue(p.blocked("a1", "UC_Alpha"))
        assertTrue(p.blocked("other", "UC_Beta"))
        assertTrue("UC_Beta" !in p.topArtists())
        // Excluded listening still counts, but only a tenth.
        assertEquals(Weights.FULL_LISTEN * Weights.EXCLUDED_FACTOR, p.tracks.getValue("c1").longScore, 0.01)
    }

    @Test
    fun likesCountMoreThanAPlay() {
        val p = TasteProfile.build(listOf(full(a1)), listOf(Saved("b1", "UC_Beta", "Beta", NOW - DAY)), emptyList(), emptyList(), NOW)
        assertTrue(p.artistAffinity("UC_Beta") > p.artistAffinity("UC_Alpha"))
        assertTrue(p.known("b1") && p.known("a1") && !p.known("zz"))
    }
}
