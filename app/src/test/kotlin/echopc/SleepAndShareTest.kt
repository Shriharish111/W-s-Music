package echopc

import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class SleepAndShareTest {
    private fun s(id: String) = SongItem(id = id, title = "Song $id", artists = listOf(Artist("Artist", "UC_a")), duration = 200, thumbnail = "")

    private class Setup(val c: Controller, val engine: FakeEngine) {
        var report: (PlayerState) -> Unit = {}
        var ended: () -> Unit = {}
    }

    private fun withController(block: suspend (Setup) -> Unit): Unit = runBlocking {
        val repo = LibraryRepository(LibraryDatabase.open(File.createTempFile("ws-sleep", ".db").also { it.delete(); it.deleteOnExit() }))
        val scope = TestScope()
        val engine = FakeEngine()
        var report: (PlayerState) -> Unit = {}
        var ended: () -> Unit = {}
        val c = Controller(scope, repo, null) { st, en -> report = st; ended = en; engine }
        c.autoplay = false
        try { block(Setup(c, engine).also { it.report = report; it.ended = ended }) } finally { scope.stop(); repo.close() }
    }

    @Test
    fun endOfSongPausesWithTheNextSongLinedUp() = withController { t ->
        t.c.playFromList(listOf(s("a"), s("b")), 0)
        t.c.sleepAfterSong()
        t.ended()
        assertEquals("b", t.c.queue.current?.id)
        assertEquals(listOf("a"), t.engine.played, "the next song must not start")
        assertTrue(t.c.resumePending)
        assertFalse(t.c.sleepTimerOn)
        t.c.togglePause()
        assertEquals(listOf("a", "b"), t.engine.played)
    }

    @Test
    fun timedTimerPausesPlayback() = withController { t ->
        t.c.playFromList(listOf(s("a")), 0)
        t.report(PlayerState(playing = true, durationMs = 200_000))
        t.c.startSleepTimer(1, durationMs = 50)
        var waited = 0
        while (t.c.sleepTimerOn && waited < 10_000) { delay(50); waited += 50 }
        assertEquals(1, t.engine.pauseToggles)
        assertFalse(t.c.sleepTimerOn)
    }

    @Test
    fun cancelledTimerDoesNothing() = withController { t ->
        t.c.playFromList(listOf(s("a")), 0)
        t.report(PlayerState(playing = true, durationMs = 200_000))
        t.c.startSleepTimer(1, durationMs = 100)
        t.c.cancelSleepTimer()
        delay(300)
        assertEquals(0, t.engine.pauseToggles)
    }

    @Test
    fun copiesSongLinks() = withController { t ->
        val copied = mutableListOf<String>()
        t.c.copyText = { copied += it }
        t.c.copyLink(s("abc123"))
        t.c.copyLink(s("abc123"), allPlatforms = true)
        assertEquals(listOf("https://music.youtube.com/watch?v=abc123", "https://song.link/y/abc123"), copied)
    }

    @Test
    fun pressingPlayAfterAFailureTriesAgain() = withController { t ->
        t.c.playFromList(listOf(s("a")), 0)
        t.report(PlayerState(error = "No playable stream found for this track."))
        t.c.togglePause()
        assertEquals(listOf("a", "a"), t.engine.played)
        assertEquals(0, t.engine.pauseToggles)
    }
}
