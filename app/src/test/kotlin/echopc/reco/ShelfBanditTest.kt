package echopc.reco

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShelfBanditTest {
    @Test
    fun betaSamplesHaveTheRightMean() {
        val b = ShelfBandit(Random(1))
        val mean = List(20_000) { b.sampleBeta(2.0, 6.0) }.average()
        assertEquals(0.25, mean, 0.01)
    }

    @Test
    fun clickedShelvesRiseButOthersStillGetShown() {
        val b = ShelfBandit(Random(7))
        val keys = listOf("feed-1", "feed-2", "feed-3", "mixes")
        // Users keep clicking "mixes" (last in the feed's order) and ignore the rest.
        val stats = mapOf("feed-1" to (200L to 4L), "feed-2" to (200L to 2L), "feed-3" to (200L to 2L), "mixes" to (200L to 90L))
        val firsts = List(500) { b.order(keys, stats).first() }.groupingBy { it }.eachCount()
        assertTrue((firsts["mixes"] ?: 0) > 450, "the shelf people click leads: $firsts")
        // A brand-new shelf (no stats) still reaches the top now and then: exploration.
        val withNew = keys + "brand-new"
        val newFirst = List(500) { b.order(withNew, stats).first() }.count { it == "brand-new" }
        assertTrue(newFirst in 1..250, "new shelf explored sometimes: $newFirst")
    }

    @Test
    fun withoutDataTheFeedOrderMostlyHolds() {
        val b = ShelfBandit(Random(3))
        val keys = List(6) { "s$it" }
        val firsts = List(1000) { b.order(keys, emptyMap()).first() }.groupingBy { it }.eachCount()
        assertTrue((firsts["s0"] ?: 0) > (firsts["s5"] ?: 0), "positional prior: $firsts")
    }
}
