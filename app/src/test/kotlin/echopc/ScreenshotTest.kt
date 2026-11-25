package echopc

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.graphics.asSkiaBitmap
import com.music.innertube.YouTube
import com.music.innertube.models.Album
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import echopc.db.LibraryDatabase
import echopc.db.artistKey
import echopc.db.LibraryRepository
import echopc.lyrics.LyricsEntry
import echopc.lyrics.WordTimestamp
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/** Does nothing; lets the UI run without audio. */
class FakeEngine : PlayerEngine {
    val played = mutableListOf<String>()
    var pauseToggles = 0
    override fun play(videoId: String, nextVideoId: String?) { played += videoId }
    override fun prefetch(videoId: String) {}
    override fun togglePause() { pauseToggles++ }
    override fun seek(ms: Long) {}
    override fun setVolume(v: Double) {}
    override fun stop() {}
}

fun fakeSong(i: Int, title: String, artist: String, thumb: String = "") = SongItem(
    id = "fake$i", title = title, artists = listOf(Artist(artist, "UCfake$i")), album = Album("Album $i", "MPRE$i"),
    duration = 170 + i * 13, thumbnail = thumb,
)

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ScreenshotTest {
    private val out = File("build/shots").also { it.mkdirs() }

    private fun realSongs(): List<SongItem> = runBlocking {
        runCatching {
            YouTube.search("synthwave gaming", YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.filterIsInstance<SongItem>().take(9)
        }.getOrDefault(emptyList())
    }.ifEmpty {
        listOf("Neon Drive" to "Futurecop", "Night Call" to "Kavinsky", "Turbo Killer" to "Carpenter Brut", "Resonance" to "HOME", "Nightcall" to "Kavinsky",
            "Sunset" to "The Midnight", "Miami Disco" to "Perturbator", "Wave Rider" to "Timecop1983", "Tech Noir" to "Gunship").mapIndexed { i, (t, a) -> fakeSong(i, t, a) }
    }

    @Test
    fun renderScreens() = runDesktopComposeUiTest(width = 1340, height = 840, testTimeout = 6.minutes) {
        val songs = realSongs()
        val db = LibraryDatabase.open(File.createTempFile("ws-shot", ".db").also { it.delete(); it.deleteOnExit() })
        val repo = LibraryRepository(db)
        val scope = TestScope()
        val c = Controller(scope, repo, null) { _, _ -> FakeEngine() }

        val p1 = runBlocking {
            songs.take(5).forEach { repo.toggleLike(it) }
            val p1 = repo.createPlaylist("Late night drive"); val p2 = repo.createPlaylist("Gym grind"); repo.createPlaylist("Boss fight")
            songs.take(4).forEach { repo.addToPlaylist(p1.id, it) }; songs.drop(3).take(3).forEach { repo.addToPlaylist(p2.id, it) }
            repo.recordPlay(songs[0], 120_000); repo.recordPlay(songs[2], 90_000)
            p1
        }

        // State as if the user searched and is playing the 2nd song.
        c.results = songs
        c.query = "synthwave gaming"; c.lastSearched = "synthwave gaming"
        c.queue = QueueState(items = songs, index = 1)
        c.playback = PlayerState(playing = true, durationMs = 214_000)
        c.positionMs = 83_000
        c.volume = 0.7f
        val words = "Riding through the neon lights tonight".split(" ")
        c.lyrics = LyricsUiState.Loaded(
            "YouLyPlus",
            listOf(
                LyricsEntry(10_000, "Out on the highway, engine hums"), LyricsEntry(18_000, "Chrome and fire, here it comes"),
                LyricsEntry(26_000, "Every mile a memory"), LyricsEntry(34_000, "Burning bright like we were free"),
                LyricsEntry(44_000, "Hold on tight, the city glows"), LyricsEntry(58_000, "Nobody knows where the night goes"),
                LyricsEntry(70_000, "Taillights painting the road"), LyricsEntry(80_000, words.joinToString(" "),
                    words.mapIndexed { i, w -> WordTimestamp(w, 80.0 + i * 0.7, 80.0 + i * 0.7 + 0.6) }),
                LyricsEntry(92_000, "We're faster than the dawn"), LyricsEntry(104_000, "And the world keeps rolling on"),
                LyricsEntry(116_000, "Neon heartbeat, drive me home"),
            ),
            synced = true,
        )
        songs.getOrNull(1)?.thumbnail?.takeIf { it.isNotBlank() }?.let { url ->
            runBlocking { ThumbCache.load(url, 128) }?.let { c.accent = accentFrom(it.dominant) }
        }

        setContent { WsTheme(c.accent) { AppContent(c, onPickAndroidFile = {}) } }

        fun shot(name: String, dialog: Boolean = false) {
            Thread.sleep(1500) // let thumbnails finish loading
            waitForIdle()
            // An open dialog is a second root: capture it (the window behind it is the first root).
            val roots = onAllNodes(androidx.compose.ui.test.isRoot())
            val bmp = (if (dialog) roots[roots.fetchSemanticsNodes().size - 1] else roots[0]).captureToImage().asSkiaBitmap()
            val data = org.jetbrains.skia.Image.makeFromBitmap(bmp).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!
            File(out, "$name.png").writeBytes(data.bytes)
            println("SHOT $name ${bmp.width}x${bmp.height} -> ${File(out, "$name.png").length()} bytes")
        }
        c.navigate(Route.Search)
        c.panelOpen = true; c.panelTab = PanelTab.QUEUE; shot("1-search-queue")
        c.panelTab = PanelTab.NOW_PLAYING; shot("2-search-now-playing")
        c.navigate(Route.Lyrics); shot("3-lyrics")
        c.navigate(Route.Liked); shot("4-liked")
        c.navigate(Route.LocalPlaylist(p1.id)); shot("5-playlist")
        c.navigate(Route.History); c.sidebarCollapsed = true; shot("6-history-collapsed")
        c.sidebarCollapsed = false
        // Phase 4: Smart Shuffle in the queue and player bar, a hidden song dimmed, a toast with Undo.
        c.shuffleModes["home"] = ShuffleMode.SMART
        c.queue = QueueState(items = songs, index = 1, recommended = setOf(songs[3].id, songs[6].id))
        c.panelOpen = true; c.panelTab = PanelTab.QUEUE
        c.navigate(Route.Liked)
        c.blocks = Blocks(notInterested = setOf(songs[2].id), bannedArtists = setOf(songs[4].artistKey()))
        c.showToast("“${songs[2].title}” is hidden here", ToastAction("Undo") {})
        shot("8-smart-queue-feedback")
        c.shortcutsOpen = true; shot("9-shortcuts", dialog = true); c.shortcutsOpen = false
        c.shuffleModes.clear(); c.blocks = Blocks()
        c.navigate(Route.Search); c.results = emptyList(); c.query = ""; c.queue = QueueState(); c.playback = PlayerState(); c.positionMs = 0; c.lyrics = LyricsUiState.Idle
        shot("7-empty-state")
        scope.stop()
        repo.close()
    }
}
