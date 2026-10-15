package echopc

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.Artist
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.pages.SearchSummary
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** Search shows YouTube Music's whole result page (songs, videos, albums, artists, playlists), not only songs. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SearchTabsTest {
    private fun tmp(suffix: String) = File.createTempFile("ws-search", suffix).also { it.delete(); it.deleteOnExit() }

    @Test
    fun mixedResultsShowEveryKind() = runDesktopComposeUiTest(width = 1340, height = 2400, testTimeout = 2.minutes) {
        val repo = LibraryRepository(LibraryDatabase.open(tmp(".db")))
        val scope = TestScope()
        val c = Controller(scope, repo, null) { _, _ -> FakeEngine() }
        val songs = listOf(fakeSong(1, "Alpha Track", "Artist A"), fakeSong(2, "Beta Track", "Artist B"))
        val album = AlbumItem("MPREb_1", "OLAK_1", title = "Great Album", artists = listOf(Artist("Artist A", null)), year = 2020, thumbnail = "")
        val artist = ArtistItem("UC1", "Artist A", null, shuffleEndpoint = null, radioEndpoint = null)
        val playlist = PlaylistItem("PL1", "Chill Mix", Artist("Curator", null), "20 songs", null, null, null, null)
        c.searchSummaries = listOf(
            SearchSummary("Top result", listOf(songs[0])),
            SearchSummary("Songs", songs),
            SearchSummary("Albums", listOf(album)),
            SearchSummary("Artists", listOf(artist)),
            SearchSummary("Playlists", listOf(playlist)),
        )
        c.results = songs
        c.query = "alpha"
        c.lastSearched = "alpha" // as after a real search; otherwise the page treats the text as still being typed
        c.navigate(Route.Search)
        setContent { WsTheme(c.accent) { AppContent(c, onPickAndroidFile = {}) } }

        for (text in listOf("Top result", "Alpha Track", "Beta Track", "Great Album", "Artist A", "Chill Mix", "Videos")) {
            try { waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
            catch (e: Throwable) { throw AssertionError("missing on screen: $text", e) }
        }
        // Every kind has its tab; the headers carry a "Show all" link.
        assertEquals(SearchTab.entries.size, 6)
        waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText("Show all").fetchSemanticsNodes().size >= 4 }
        // Clicking a song plays that song (not its neighbour) and queues the section's list.
        onAllNodesWithText("Beta Track")[0].performClick()
        waitUntil(timeoutMillis = 8_000L) { c.queue.current != null }
        assertEquals("fake2", c.queue.current?.id)
        scope.stop()
        repo.close()
    }
}
