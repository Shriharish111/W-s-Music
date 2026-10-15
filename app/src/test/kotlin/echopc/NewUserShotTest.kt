package echopc

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.music.innertube.YouTube
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking

/** Screenshots of what a brand-new user sees, with real YouTube Music data (build/shots). */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class NewUserShotTest {
    private val out = File("build/shots").also { it.mkdirs() }

    @Test
    fun renderNewUserScreens() = runDesktopComposeUiTest(width = 1340, height = 1000, testTimeout = 6.minutes) {
        runBlocking { YouTube.visitorData().onSuccess { YouTube.visitorData = it } }
        val repo = LibraryRepository(LibraryDatabase.open(File.createTempFile("ws-newuser", ".db").also { it.delete(); it.deleteOnExit() }))
        val scope = TestScope()
        val c = Controller(scope, repo, null) { _, _ -> FakeEngine() } // real YouTubeDiscovery and candidates
        c.panelOpen = false
        setContent { WsTheme(c.accent) { AppContent(c, onPickAndroidFile = {}) } }

        fun shot(name: String) {
            Thread.sleep(3500); waitForIdle()
            val bmp = onRoot().captureToImage().asSkiaBitmap()
            File(out, "$name.png").writeBytes(org.jetbrains.skia.Image.makeFromBitmap(bmp).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            println("SHOT $name ${bmp.width}x${bmp.height}")
        }
        // waitUntil keeps Compose running (frames, effects) while it polls, so Home reacts to the new state.
        fun waitFor(what: String, ms: Long = 60_000, cond: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            val ok = runCatching { waitUntil(timeoutMillis = ms) { cond() } }.isSuccess
            println("$what: ${if (ok) "ready" else "TIMED OUT"} after ${System.currentTimeMillis() - t0} ms")
        }

        c.loadHome() // what the app does at launch
        waitFor("popular songs", 25_000) { c.popularNow != null }
        waitFor("starter artists") { c.suggestedArtists.isNotEmpty() }
        waitFor("home feed", 15_000) { !c.homeLoading && c.homeSections.isNotEmpty() }
        shot("n1-new-user-home")

        c.openPicker()
        c.togglePick(c.suggestedArtists[0]); c.togglePick(c.suggestedArtists[1])
        waitFor("similar artists", 20_000) { c.pickerSimilar.isNotEmpty() }
        shot("n2-picker")
        scope.stop(); repo.close()
    }
}
