package echopc

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import com.music.innertube.models.PlaylistItem
import com.music.innertube.pages.HomePage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class UiFlowTest {
    private fun tmp(suffix: String) = File.createTempFile("ws-ui", suffix).also { it.delete(); it.deleteOnExit() }

    @Test
    fun wholeLibraryFlow() = runDesktopComposeUiTest(width = 1340, height = 840, testTimeout = 4.minutes) {
        val repo = LibraryRepository(LibraryDatabase.open(tmp(".db")))
        val engine = FakeEngine()
        val scope = TestScope()
        val c = Controller(scope, repo, null) { _, _ -> engine }
        val songs = listOf(fakeSong(1, "Alpha Track", "Artist A"), fakeSong(2, "Beta Track", "Artist B"), fakeSong(3, "Gamma Track", "Artist C"))
        c.results = songs
        c.navigate(Route.Search) // the app opens on Home
        setContent { WsTheme(c.accent) { AppContent(c, onPickAndroidFile = {}) } }

        // 1. Heart a result (the heart is in every row, revealed on hover, always keyboard reachable).
        waitUntil(timeoutMillis = 8_000L) { onAllNodesWithContentDescription("Like").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithContentDescription("Like")[0].performClick()
        waitUntil(timeoutMillis = 8_000L) { runBlocking { repo.likedSongs.first().size } == 1 }
        waitUntil(timeoutMillis = 8_000L) { c.likedIds.size == 1 }
        val likedId = c.likedIds.single()
        val likedTitle = songs.first { it.id == likedId }.title

        // 2. Clicking a result starts playback of that song and queues the list.
        onAllNodesWithText("Beta Track")[0].performClick()
        waitUntil(timeoutMillis = 5_000L) { engine.played.isNotEmpty() }
        assertEquals("fake2", c.queue.current?.id)
        assertEquals(3, c.queue.items.size)
        assertEquals("fake2", engine.played.last())

        // 3. Liked Songs in the library sidebar opens the page with the liked song.
        onAllNodesWithText("Liked Songs")[0].performClick()
        waitUntil(timeoutMillis = 5_000L) { c.route == Route.Liked }
        waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText(likedTitle).fetchSemanticsNodes().isNotEmpty() }

        // 4. New playlist through the sidebar's + button and the dialog.
        onNodeWithContentDescription("Create playlist").performClick()
        waitUntil(timeoutMillis = 5_000L) { c.newPlaylistOpen && onAllNodesWithText("Name").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Name").performTextInput("Boss fight")
        onNodeWithText("Create").performClick()
        waitUntil(timeoutMillis = 8_000L) { runBlocking { repo.playlists.first().any { it.playlist.name == "Boss fight" } } }
        waitUntil(timeoutMillis = 5_000L) { !c.newPlaylistOpen }
        waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText("Boss fight").fetchSemanticsNodes().isNotEmpty() } // listed in the sidebar

        // 5. Import from an Android database through the real dialogs.
        val android = tmp(".db").also(::buildAndroidDb)
        c.importAndroid(android)
        waitUntil(timeoutMillis = 15_000L) { onAllNodesWithText("Import complete").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(c.importUi is ImportUi.Done)
        onNodeWithText("Done").performClick()
        waitUntil(timeoutMillis = 5_000L) { c.importUi == ImportUi.Idle }
        val liked = runBlocking { repo.likedSongs.first().map { it.song.id }.toSet() }
        assertTrue(liked.containsAll(setOf(likedId, "s1", "s2", "s8")), "liked=$liked")
        val names = runBlocking { repo.playlists.first().map { it.playlist.name }.toSet() }
        assertTrue(names.containsAll(setOf("Boss fight", "Gym", "Synced from YouTube")), "playlists=$names")
        // The sidebar refreshes by itself after the import.
        waitUntil(timeoutMillis = 8_000L) { onAllNodesWithText("Gym", substring = true).fetchSemanticsNodes().isNotEmpty() }

        // Back / forward through the pages visited.
        c.goBack(); assertEquals(Route.Search, c.route)
        c.goForward(); assertEquals(Route.Liked, c.route)

        // 6. Queue controls.
        c.skipNext()
        assertEquals("fake3", c.queue.current?.id)
        c.skipPrevious()
        assertEquals("fake2", c.queue.current?.id)
        c.cycleRepeat(); assertEquals(RepeatMode.ALL, c.queue.repeat)
        scope.stop()
        repo.close()
    }

    @Test
    fun homePage() = runDesktopComposeUiTest(width = 1340, height = 1700, testTimeout = 4.minutes) {
        val repo = LibraryRepository(LibraryDatabase.open(tmp(".db")))
        val engine = FakeEngine()
        val scope = TestScope()
        val c = Controller(scope, repo, null) { _, _ -> engine }
        val songs = listOf(fakeSong(1, "Alpha Track", "Artist A"), fakeSong(2, "Beta Track", "Artist B"))
        val playlist = PlaylistItem("PLx", "Chill Mix", com.music.innertube.models.Artist("Curator", null), "20 songs", null, null, null, null)
        c.homeSections = listOf(HomePage.Section("Fresh picks", "JUST FOR YOU", null, null, songs + playlist))
        c.homeChips = listOf(HomePage.Chip("Party", null, null), HomePage.Chip("Focus", null, null))
        runBlocking { repo.toggleLike(songs[0]); repo.recordPlay(songs[1], 40_000) }
        setContent { WsTheme(c.accent) { AppContent(c, onPickAndroidFile = {}) } }

        // Front page: starts on Home with shelf, chips, quick tiles and a mix from listening history.
        assertEquals(Route.Home, c.route)
        waitUntil(conditionDescription = "home wait #1", timeoutMillis = 8_000L) { onAllNodesWithText("Fresh picks").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Party").assertExists(); onNodeWithText("Focus").assertExists()
        waitUntil(conditionDescription = "home wait #2", timeoutMillis = 8_000L) { onAllNodesWithText("Liked Songs", substring = true).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(conditionDescription = "home wait #3", timeoutMillis = 8_000L) { onAllNodesWithText("Made for you").fetchSemanticsNodes().isNotEmpty() }
        waitUntil(conditionDescription = "home wait #4", timeoutMillis = 8_000L) { onAllNodesWithText("Recently played").fetchSemanticsNodes().isNotEmpty() }

        // Clicking a song card plays it.
        onAllNodesWithText("Alpha Track", substring = true)[0].performClick()
        waitUntil(conditionDescription = "home wait #5", timeoutMillis = 5_000L) { engine.played.isNotEmpty() }
        assertTrue(engine.played.last() in setOf("fake1", "fake2"), "played=${engine.played}")

        // Playlist page: shows, Save adds it to the library, then Back returns to the feed.
        c.showDetail("test", DetailUi.Songs("Chill Mix", "Playlist", "Curator • 2 songs", null, songs))
        waitUntil(conditionDescription = "home wait #6", timeoutMillis = 5_000L) { onAllNodesWithContentDescription("Save to Your Library").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Save to Your Library").performClick()
        waitUntil(conditionDescription = "home wait #7", timeoutMillis = 8_000L) { runBlocking { repo.playlists.first().any { it.playlist.name == "Chill Mix" && it.songCount == 2 } } }
        onNodeWithContentDescription("Go back").performClick()
        waitUntil(conditionDescription = "home wait #8", timeoutMillis = 5_000L) { c.route == Route.Home }
        waitUntil(conditionDescription = "home wait #9", timeoutMillis = 8_000L) { onAllNodesWithText("Fresh picks").fetchSemanticsNodes().isNotEmpty() }

        // "Liked Songs" (quick tile or sidebar) opens the Liked Songs page.
        onAllNodesWithText("Liked Songs", substring = true)[0].performClick()
        waitUntil(conditionDescription = "home wait #10", timeoutMillis = 5_000L) { c.route == Route.Liked }
        scope.stop()
        repo.close()
    }
}