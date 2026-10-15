package echopc

import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueueStateTest {
    private fun s(id: String) = SongItem(id = id, title = id, artists = listOf(Artist("A$id", null)), thumbnail = "")
    private val list = List(10) { s("s$it") }
    private val recs = List(5) { s("r$it") }

    @Test
    fun smartShufflePutsOneRecommendationAfterEveryThreeSongs() {
        val q = QueueState(list, index = 0).withSmartShuffle(recs, random = Random(1))
        assertEquals("s0", q.current?.id, "the current song stays")
        val upcoming = q.items.drop(1)
        // 9 upcoming songs -> a recommendation after the 3rd, 6th and 9th.
        assertEquals(listOf(3, 7, 11), upcoming.indices.filter { upcoming[it].id.startsWith("r") })
        assertEquals(setOf("r0", "r1", "r2"), q.recommended)
        assertEquals(list.toSet(), q.items.filter { !it.id.startsWith("r") }.toSet(), "nothing from the list is lost")
    }

    @Test
    fun removingRecommendationsKeepsTheCurrentSong() {
        val smart = QueueState(list, index = 0).withSmartShuffle(recs, random = Random(2))
        val playingAfterARec = smart.jump(5) // positions 0..5 include one recommendation (index 4)
        val cleaned = playingAfterARec.withoutRecommendations()
        assertEquals(playingAfterARec.current?.id, cleaned.current?.id)
        assertEquals(10, cleaned.items.size)
        assertTrue(cleaned.recommended.isEmpty())
        // Playing a recommendation right now: it stays (only the others go).
        val onRec = smart.jump(4)
        assertTrue(onRec.current!!.id.startsWith("r"))
        val kept = onRec.withoutRecommendations()
        assertEquals(onRec.current?.id, kept.current?.id)
        assertEquals(11, kept.items.size)
    }

    @Test
    fun linearRestoresTheListOrderAfterTheCurrentSong() {
        val shuffled = QueueState(list, index = 0).withSmartShuffle(recs, random = Random(3)).jump(2)
        val cur = shuffled.current!!
        val linear = shuffled.linear(list).add(s("manual")) // a song queued by hand stays queued
        assertEquals(cur.id, linear.current?.id)
        val after = linear.items.drop(linear.index + 1).map { it.id }
        val expected = list.dropWhile { it.id != cur.id }.drop(1).map { it.id }.filter { id -> linear.items.take(linear.index + 1).none { it.id == id } }
        assertEquals(expected + "manual", after)
        assertTrue(linear.items.none { it.id.startsWith("r") } || cur.id.startsWith("r"))
    }

    @Test
    fun withoutUpcomingLeavesHistoryAndCurrent() {
        val q = QueueState(list, index = 3).withoutUpcoming { it.id in setOf("s1", "s3", "s5") }
        assertEquals(listOf("s0", "s1", "s2", "s3", "s4", "s6", "s7", "s8", "s9"), q.items.map { it.id })
        assertEquals("s3", q.current?.id)
    }
}
