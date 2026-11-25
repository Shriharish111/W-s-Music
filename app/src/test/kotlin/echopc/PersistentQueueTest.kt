package echopc

import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Closing and reopening the app brings the queue back, paused where it was left (Echo Music's "persistent queue"). */
class PersistentQueueTest {
    private fun s(id: String) = SongItem(id = id, title = "Song $id", artists = listOf(Artist("Artist", "UC_a")), duration = 200, thumbnail = "")

    private class Engine : PlayerEngine {
        val played = mutableListOf<String>()
        val seeks = mutableListOf<Long>()
        var toggles = 0
        override fun play(videoId: String, nextVideoId: String?) { played += videoId }
        override fun prefetch(videoId: String) {}
        override fun togglePause() { toggles++ }
        override fun seek(ms: Long) { seeks += ms }
        override fun setVolume(v: Double) {}
        override fun stop() {}
    }

    private class Run(val c: Controller, val engine: Engine, val onState: (PlayerState) -> Unit, val repo: LibraryRepository, val scope: TestScope) {
        fun close() { c.shutdown(); scope.stop(); repo.close() }
    }

    private fun start(dir: File, db: File): Run {
        val repo = LibraryRepository(LibraryDatabase.open(db))
        val scope = TestScope()
        val engine = Engine()
        var report: (PlayerState) -> Unit = {}
        val c = Controller(scope, repo, File(dir, "settings.properties")) { st, _ -> report = st; engine }
        c.autoplay = false
        return Run(c, engine, report, repo, scope)
    }

    @Test
    fun queueComesBackPausedAtTheSameSpot() {
        val dir = Files.createTempDirectory("ws-pq").toFile().also { it.deleteOnExit() }
        val db = File(dir, "lib.db")
        val songs = listOf(s("a"), s("b"), s("c"))

        val first = start(dir, db)
        first.c.playFromList(songs, 1, context = "local:p1")
        first.onState(PlayerState(playing = true, durationMs = 200_000, positionMs = 73_000))
        first.close()
        assertTrue(File(dir, "queue.txt").isFile, "the queue is saved when the app closes")

        val second = start(dir, db)
        val c = second.c
        assertEquals(songs.map { it.id }, c.queue.items.map { it.id })
        assertEquals("b", c.queue.current?.id)
        assertEquals("local:p1", c.queueContext)
        assertEquals(73_000, c.positionMs)
        assertEquals(200_000, c.playback.durationMs)
        assertTrue(c.resumePending)
        assertFalse(c.playback.playing, "nothing plays by itself after a restart")
        assertTrue(second.engine.played.isEmpty())

        // Play: the song loads and jumps to where it was left.
        c.togglePause()
        assertEquals(listOf("b"), second.engine.played)
        assertEquals(0, second.engine.toggles)
        assertFalse(c.resumePending)
        second.onState(PlayerState(loading = true))
        assertEquals(73_000, c.positionMs, "the bar keeps the saved spot while loading")
        second.onState(PlayerState(playing = true, durationMs = 200_000, positionMs = 0))
        assertEquals(listOf(73_000L), second.engine.seeks)
        assertEquals(73_000, c.positionMs)

        // Clearing the queue clears what comes back next time.
        c.clearQueue()
        second.close()
        val third = start(dir, db)
        assertNull(third.c.queue.current)
        assertFalse(third.c.resumePending)
        third.close()
    }

    @Test
    fun seekingBeforePressingPlayStartsFromThere() {
        val dir = Files.createTempDirectory("ws-pq").toFile().also { it.deleteOnExit() }
        val db = File(dir, "lib.db")
        val first = start(dir, db)
        first.c.playFromList(listOf(s("x"), s("y")), 0)
        first.onState(PlayerState(playing = true, durationMs = 200_000, positionMs = 50_000))
        first.close()

        val second = start(dir, db)
        second.c.seek(120_000)
        assertTrue(second.engine.seeks.isEmpty(), "nothing is loaded yet")
        second.c.togglePause()
        second.onState(PlayerState(playing = true, durationMs = 200_000))
        assertEquals(listOf(120_000L), second.engine.seeks)
        second.close()
    }

    @Test
    fun brokenFileIsIgnored() {
        val dir = Files.createTempDirectory("ws-pq").toFile().also { it.deleteOnExit() }
        File(dir, "queue.txt").writeText("not a queue")
        val run = start(dir, File(dir, "lib.db"))
        assertNull(run.c.queue.current)
        run.close()
    }
}
