package echopc

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.music.innertube.models.Artist
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.SearchSuggestions
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** While the search box is being typed in, the page shows matching artists and songs and ways to finish the search. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SuggestionsTest {
    private fun tmp(suffix: String) = File.createTempFile("ws-suggest", suffix).also { it.delete(); it.deleteOnExit() }

    @Test
    fun typingShowsTheArtistTheirSongsAndCompletions() = runDesktopComposeUiTest(width = 1340, height = 900, testTimeout = 2.minutes) {
        val repo = LibraryRepository(LibraryDatabase.open(tmp(".db")))
        val scope = TestScope()
        val engine = FakeEngine()
        val c = Controller(scope, repo, null) { _, _ -> engine }
        val artist = ArtistItem("UC1", "Tame Impala", null, shuffleEndpoint = null, radioEndpoint = null)
        val song = fakeSong(7, "Loser", "Tame Impala")
        c.navigate(Route.Search)
        setContent { WsTheme(c.accent) { AppContent(c, onPickAndroidFile = {}) } }
        waitForIdle() // the search box reports "not focused" once at start; set the typing state after that
        c.searchFocusTick++ // focus the real search box, like Ctrl+L, so pressing a row really takes focus from it
        waitUntil(timeoutMillis = 8_000L) { c.typing }
        c.query = "tame"
        c.suggestions = SearchSuggestions(queries = listOf("tame impala dracula"), recommendedItems = listOf(artist, song))
        c.suggestionsFor = "tame"

        for (text in listOf("Search for “tame”", "Tame Impala", "Loser", "tame impala dracula", "Top matches")) {
            try { waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
            catch (e: Throwable) { throw AssertionError("missing on screen: $text", e) }
        }
        // Picking a suggested song plays it straight away.
        onAllNodesWithText("Loser")[0].performClick()
        waitUntil(timeoutMillis = 8_000L) { c.queue.current != null }
        assertEquals("fake7", c.queue.current?.id)
        assertTrue(!c.typing, "the typing view closes once something is picked")
        scope.stop()
        repo.close()
    }
}
