package echopc

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.click
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Keyboard shortcuts, hover tooltips and the right-click song menu. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class InteractionTest {
    private fun tmp() = File.createTempFile("ws-ix", ".db").also { it.delete(); it.deleteOnExit() }
    private fun s(id: String, title: String, artist: String) = SongItem(id = id, title = title, artists = listOf(Artist(artist, "UC_$artist")), duration = 200, thumbnail = "")

    private class Setup(val c: Controller, val repo: LibraryRepository, val engine: FakeEngine, val scope: TestScope) {
        fun close() { scope.stop(); repo.close() }
    }

    private fun setup(): Setup {
        val repo = LibraryRepository(LibraryDatabase.open(tmp()))
        val engine = FakeEngine()
        val scope = TestScope()
        val c = Controller(scope, repo, null) { _, _ -> engine }
        c.autoplay = false
        return Setup(c, repo, engine, scope)
    }

    // ------------------------------------------------------------ key mapping

    private fun sc(key: Key, mods: String = "") = shortcutFor(key, ctrl = 'c' in mods, shift = 's' in mods, alt = 'a' in mods)

    @Test
    fun everyShortcutIsRecognisedAndModifiersMustMatchExactly() {
        val table = listOf(
            Triple(Key.Spacebar, "", Shortcut.PLAY_PAUSE),
            Triple(Key.DirectionRight, "c", Shortcut.NEXT), Triple(Key.DirectionLeft, "c", Shortcut.PREVIOUS),
            Triple(Key.DirectionRight, "s", Shortcut.SEEK_FORWARD), Triple(Key.DirectionLeft, "s", Shortcut.SEEK_BACK),
            Triple(Key.DirectionUp, "c", Shortcut.VOLUME_UP), Triple(Key.DirectionDown, "c", Shortcut.VOLUME_DOWN),
            Triple(Key.M, "", Shortcut.MUTE), Triple(Key.S, "c", Shortcut.SHUFFLE), Triple(Key.R, "c", Shortcut.REPEAT),
            Triple(Key.B, "as", Shortcut.LIKE), Triple(Key.L, "c", Shortcut.SEARCH),
            Triple(Key.DirectionLeft, "a", Shortcut.BACK), Triple(Key.DirectionRight, "a", Shortcut.FORWARD),
            Triple(Key.Slash, "c", Shortcut.HELP),
        )
        for ((key, mods, expected) in table) assertEquals(expected, sc(key, mods), "$expected")
        assertEquals(Shortcut.entries.toSet(), table.map { it.third }.toSet(), "the table covers every shortcut")
        // Near misses are not shortcuts, so normal typing and other combinations are left alone.
        assertNull(sc(Key.S, "cs"), "Ctrl+Shift+S")
        assertNull(sc(Key.S), "plain S")
        assertNull(sc(Key.M, "c"), "Ctrl+M")
        assertNull(sc(Key.DirectionRight), "plain arrow")
        assertNull(shortcutFor(Key.Spacebar, ctrl = false, shift = false, alt = false, meta = true), "Win+Space")
        // Every displayed shortcut is unique, so the help dialog and tooltips cannot contradict each other.
        assertEquals(Shortcut.entries.size, Shortcut.entries.map { it.keys }.toSet().size)
    }

    private fun ShortcutHandler.press(key: Key, down: Boolean = true, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        onKey(key, down, ctrl, shift, alt, meta = false)

    @Test
    fun handlerStandsDownWhileTypingAndIgnoresKeyRepeatExceptWhereItMakesSense() {
        val t = setup()
        val h = ShortcutHandler(t.c)
        // Typing in a text box: Space and Ctrl+arrows belong to the text box.
        t.c.typing = true
        assertEquals(false, h.press(Key.Spacebar))
        assertEquals(false, h.press(Key.DirectionRight, ctrl = true))
        assertEquals(0, t.engine.pauseToggles)
        assertEquals(true, h.press(Key.Slash, ctrl = true), "Help still works")
        assertTrue(t.c.shortcutsOpen)
        t.c.shortcutsOpen = false
        t.c.typing = false

        // Holding Space must not flicker play/pause: one toggle per press.
        h.press(Key.Spacebar); h.press(Key.Spacebar); h.press(Key.Spacebar)
        assertEquals(1, t.engine.pauseToggles)
        h.press(Key.Spacebar, down = false); h.press(Key.Spacebar)
        assertEquals(2, t.engine.pauseToggles)
        h.press(Key.Spacebar, down = false)

        // Volume keeps changing while held.
        t.c.changeVolume(0.5f)
        h.press(Key.DirectionUp, ctrl = true); h.press(Key.DirectionUp, ctrl = true)
        assertEquals(0.7f, t.c.volume, 0.001f)
        t.close()
    }

    /** Real Compose key events (what the window delivers): modifiers and the event adapter work end to end. */
    @Test
    fun realKeyEventsReachTheHandler() = runDesktopComposeUiTest(width = 400, height = 300) {
        val t = setup()
        val handler = ShortcutHandler(t.c)
        val focus = androidx.compose.ui.focus.FocusRequester()
        setContent {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.focusRequester(focus).focusable().onPreviewKeyEvent(handler::onKeyEvent).fillMaxSize(),
            )
            androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
        }
        waitForIdle()
        t.c.playFromList(songs, 0)
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(1, t.engine.pauseToggles, "Space")
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionRight) } }
        assertEquals("b", t.c.queue.current?.id, "Ctrl+Right is Next")
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionLeft) } }
        assertEquals("a", t.c.queue.current?.id, "Ctrl+Left is Previous")
        t.c.playback = PlayerState(playing = true, durationMs = 60_000); t.c.positionMs = 10_000
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
        assertEquals(15_000, t.c.positionMs, "Shift+Right skips 5 s")
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
        assertEquals(ShuffleMode.ON, t.c.shuffleMode(), "Ctrl+S")
        t.close()
    }

    // ------------------------------------------------------------ what the shortcuts do

    @Test
    fun shortcutsDriveTheSameActionsAsTheButtons() = runBlocking {
        val t = setup(); val c = t.c
        val songs = listOf(s("a", "Alpha", "A"), s("b", "Beta", "B"), s("c", "Gamma", "C"))
        c.playFromList(songs, 0)

        c.perform(Shortcut.NEXT); assertEquals("b", c.queue.current?.id)
        c.perform(Shortcut.PREVIOUS); assertEquals("a", c.queue.current?.id)

        // Seeking clamps at both ends.
        c.playback = PlayerState(playing = true, durationMs = 20_000); c.positionMs = 10_000
        c.perform(Shortcut.SEEK_FORWARD); assertEquals(15_000, c.positionMs)
        c.perform(Shortcut.SEEK_FORWARD); c.perform(Shortcut.SEEK_FORWARD); assertEquals(20_000, c.positionMs)
        c.positionMs = 3_000; c.perform(Shortcut.SEEK_BACK); assertEquals(0, c.positionMs)

        // Volume: steps of 10 %, clamped, with a toast; mute remembers the level.
        c.changeVolume(0.95f); c.perform(Shortcut.VOLUME_UP); assertEquals(1f, c.volume); assertEquals("Volume 100%", c.toast)
        c.changeVolume(0.05f); c.perform(Shortcut.VOLUME_DOWN); assertEquals(0f, c.volume)
        c.changeVolume(0.6f); c.perform(Shortcut.MUTE); assertEquals(0f, c.volume); c.perform(Shortcut.MUTE); assertEquals(0.6f, c.volume)

        // Shuffle and repeat cycle like their buttons and say what they are now.
        c.perform(Shortcut.SHUFFLE); assertEquals(ShuffleMode.ON, c.shuffleMode()); assertEquals("Shuffle on", c.toast)
        c.perform(Shortcut.REPEAT); assertEquals(RepeatMode.ALL, c.queue.repeat); assertEquals("Repeat all", c.toast)
        c.perform(Shortcut.REPEAT); assertEquals("Repeat this song", c.toast)
        c.perform(Shortcut.REPEAT); assertEquals("Repeat off", c.toast)

        // Like the song that is playing.
        c.perform(Shortcut.LIKE)
        withTimeout(8_000) { while ("a" !in c.likedIds) delay(25) }
        c.perform(Shortcut.LIKE)
        withTimeout(8_000) { while ("a" in c.likedIds) delay(25) }

        // Navigation.
        c.navigate(Route.Liked); c.navigate(Route.History)
        c.perform(Shortcut.BACK); assertEquals(Route.Liked, c.route)
        c.perform(Shortcut.FORWARD); assertEquals(Route.History, c.route)
        val tick = c.searchFocusTick
        c.perform(Shortcut.SEARCH); assertEquals(Route.Search, c.route); assertEquals(tick + 1, c.searchFocusTick)
        c.perform(Shortcut.HELP); assertTrue(c.shortcutsOpen); c.perform(Shortcut.HELP); assertEquals(false, c.shortcutsOpen)
        t.close()
    }

    // ------------------------------------------------------------ right-click menu

    private fun ui(songs: List<SongItem>, setup: Setup, block: androidx.compose.ui.test.DesktopComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 1340, height = 1700, testTimeout = 4.minutes) {
            setup.c.results = songs
            setup.c.navigate(Route.Search)
            setContent { WsTheme(setup.c.accent) { AppContent(setup.c, onPickAndroidFile = {}) } }
            block()
            setup.close()
        }

    private val songs = listOf(s("a", "Alpha Track", "Artist A"), s("b", "Beta Track", "Artist B"), s("c", "Gamma Track", "Artist C"))

    private fun SemanticsNodeInteraction.bounds() = fetchSemanticsNode().boundsInRoot

    @Test
    fun rightClickingASongRowOpensItsOptionsAtThePointer() {
        val t = setup()
        ui(songs, t) {
            waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText("Beta Track").fetchSemanticsNodes().isNotEmpty() }
            // No button pressed: the options are not showing.
            assertEquals(0, onAllNodesWithText("Add to queue").fetchSemanticsNodes().size)

            val title = onAllNodesWithText("Beta Track")[0]
            val spot = title.bounds().center
            title.performMouseInput { rightClick() }
            waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText("Add to queue").fetchSemanticsNodes().isNotEmpty() }
            for (label in listOf("Play next", "Add to playlist", "Not interested", "Don't play Artist B", "Exclude from your taste profile")) {
                assertEquals(1, onAllNodesWithText(label).fetchSemanticsNodes().size, label)
            }
            // The menu opens where the pointer is, not at the row's far edge.
            val first = onNodeWithText("Play next").bounds()
            assertTrue(kotlin.math.abs(first.left - spot.x) < 40f && first.top > spot.y - 40f && first.top < spot.y + 60f, "menu at $first, pointer at $spot")

            // Choosing an option works: Add to queue.
            onNodeWithText("Add to queue").performMouseInput { click() }
            waitUntil(timeoutMillis = 8_000L) { t.c.queue.items.any { it.id == "b" } }
            waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText("Add to queue").fetchSemanticsNodes().isEmpty() } // menu closed
        }
    }

    @Test
    fun rightClickingAQueueRowOffersRemoveFromQueue() {
        val t = setup()
        t.c.playFromList(songs, 0)
        t.c.panelOpen = true; t.c.panelTab = PanelTab.QUEUE
        ui(songs, t) {
            waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText("Next up").fetchSemanticsNodes().isNotEmpty() }
            // "Gamma Track" is in the results list and in the queue panel: the panel is the right-hand one.
            val inQueue = onAllNodesWithText("Gamma Track").fetchSemanticsNodes().indices.map { onAllNodesWithText("Gamma Track")[it] }.maxByOrNull { it.bounds().left }!!
            inQueue.performMouseInput { rightClick() }
            waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText("Remove from queue").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Remove from queue").performMouseInput { click() }
            waitUntil(timeoutMillis = 8_000L) { t.c.queue.items.none { it.id == "c" } }
        }
    }

    // ------------------------------------------------------------ tooltips

    @Test
    fun hoveringControlsShowsTheirNameAndShortcutAboveAtTheBottomBelowElsewhere() {
        val t = setup()
        t.c.queue = QueueState(items = songs, index = 0)
        t.c.playback = PlayerState(playing = true, durationMs = 200_000)
        ui(songs, t) {
            // Player bar: the tooltip opens above the button and carries the shortcut.
            waitUntil(timeoutMillis = 8_000L) { onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty() }
            val pause = onNodeWithContentDescription("Pause")
            pause.performMouseInput { moveTo(center) }
            waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText("Pause").fetchSemanticsNodes().isNotEmpty() && onAllNodesWithText("Space").fetchSemanticsNodes().isNotEmpty() }
            val tip = onNodeWithText("Pause").bounds(); val button = pause.bounds()
            assertTrue(tip.bottom <= button.top + 1f, "tooltip $tip is above the button $button")

            // Top bar: below the button.
            onNodeWithContentDescription("Keyboard shortcuts").performMouseInput { moveTo(Offset.Zero + Offset(5f, 5f)) }
            val help = onNodeWithContentDescription("Keyboard shortcuts")
            help.performMouseInput { moveTo(center) }
            waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText("Ctrl+/").fetchSemanticsNodes().isNotEmpty() }
            val hint = onNodeWithText("Ctrl+/").bounds()
            assertTrue(hint.top >= help.bounds().bottom - 1f, "tooltip $hint is below the button ${help.bounds()}")
        }
    }

    @Test
    fun theControlsThatWereMissingLabelsHaveThem() {
        val t = setup()
        t.c.queue = QueueState(items = songs, index = 0)
        ui(songs, t) {
            // Play, repeat and shuffle now say what a click does (and every other bar button too).
            waitUntil(timeoutMillis = 8_000L) { onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty() }
            for (label in listOf("Play", "Turn on repeat", "Turn on shuffle", "Previous", "Next", "Mute", "Queue", "Lyrics", "Now playing view")) {
                assertTrue(onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty(), "missing label: $label")
            }
        }
    }
}
