package echopc

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** Mouse buttons 4 and 5 go back and forward through pages. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class MouseNavigationTest {
    @Test
    fun sideButtonsGoBackAndForward() = runDesktopComposeUiTest(width = 1340, height = 840, testTimeout = 2.minutes) {
        val db = File.createTempFile("ws-mouse", ".db").also { it.delete(); it.deleteOnExit() }
        val repo = LibraryRepository(LibraryDatabase.open(db))
        val scope = TestScope()
        val c = Controller(scope, repo, null) { _, _ -> FakeEngine() }
        setContent { WsTheme(c.accent) { AppContent(c, onPickAndroidFile = {}) } }
        c.navigate(Route.Liked)
        c.navigate(Route.History)
        waitForIdle()
        assertEquals(Route.History, c.route)

        onRoot().performMouseInput { press(MouseButton(3)); release(MouseButton(3)) }
        waitForIdle()
        assertEquals(Route.Liked, c.route)
        onRoot().performMouseInput { press(MouseButton(3)); release(MouseButton(3)) }
        waitForIdle()
        assertEquals(Route.Home, c.route)

        onRoot().performMouseInput { press(MouseButton(4)); release(MouseButton(4)) }
        waitForIdle()
        assertEquals(Route.Liked, c.route)
        scope.stop()
        repo.close()
    }
}
