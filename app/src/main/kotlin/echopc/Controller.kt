package echopc

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.pages.BrowseResult
import com.music.innertube.pages.HomePage
import com.music.innertube.pages.MoodAndGenres
import echopc.db.ImportResult
import echopc.db.LibraryRepository
import java.io.File
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

enum class Section { HOME, SEARCH, LIBRARY }
enum class PanelTab { QUEUE, LYRICS }

/** What the Home screen shows instead of the feed while a playlist / album / mood page is open. */
sealed interface DetailUi {
    val title: String
    data class Loading(override val title: String) : DetailUi
    data class Failed(override val title: String, val message: String) : DetailUi
    data class Songs(
        override val title: String,
        val kind: String, // "Playlist" / "Album"
        val subtitle: String,
        val cover: String?,
        val songs: List<SongItem>,
    ) : DetailUi
    data class Collection(override val title: String, val sections: List<BrowseResult.Item>) : DetailUi
}

sealed interface ImportUi {
    data object Idle : ImportUi
    data class Running(val fileName: String) : ImportUi
    data class Done(val result: ImportResult) : ImportUi
    data class Failed(val message: String) : ImportUi
}

/** Accent pair derived from album art: the dominant colour plus a hue-shifted partner for gradients. */
fun accentFrom(dominant: Color): Accent {
    val hsb = FloatArray(3)
    java.awt.Color.RGBtoHSB((dominant.red * 255).toInt(), (dominant.green * 255).toInt(), (dominant.blue * 255).toInt(), hsb)
    // Warm art (red/orange/yellow) pairs with a pink-red; cooler art pairs with the next hue along.
    val warm = hsb[0] < 0.17f || hsb[0] > 0.93f
    val shift = if (warm) -0.07f else 0.10f
    val partnerHue = ((hsb[0] + shift) % 1f + 1f) % 1f
    val partner = Color(java.awt.Color.HSBtoRGB(partnerHue, (hsb[1] * 0.9f).coerceIn(0.5f, 0.95f), 1f) or (0xFF shl 24))
    return Accent(dominant, partner)
}

/**
 * All app logic and state. Composables only read from it, so screens can be rendered with a fake
 * player in tests. Playback position has its own state so that only the widgets that show it redraw
 * as it advances (not the whole window).
 */
@Stable
class Controller(
    private val scope: CoroutineScope,
    val library: LibraryRepository,
    private val settingsFile: File? = null,
    engineFactory: (onState: (PlayerState) -> Unit, onEnded: () -> Unit) -> PlayerEngine,
) {
    // ---- navigation ----
    var section by mutableStateOf(Section.HOME)
    var libraryTab by mutableStateOf(0)
    var openPlaylistId by mutableStateOf<String?>(null)
    var panelOpen by mutableStateOf(true)
    var panelTab by mutableStateOf(PanelTab.QUEUE)

    // ---- home feed ----
    var homeSections by mutableStateOf<List<HomePage.Section>>(emptyList())
    var homeChips by mutableStateOf<List<HomePage.Chip>>(emptyList())
    var homeChip by mutableStateOf<HomePage.Chip?>(null)
    var homeLoading by mutableStateOf(false)
    var homeError by mutableStateOf<String?>(null)
    var homeMore by mutableStateOf<String?>(null) // continuation token for more shelves
    var homeLoadingMore by mutableStateOf(false)
    var newReleases by mutableStateOf<List<AlbumItem>>(emptyList())
    var moods by mutableStateOf<List<MoodAndGenres.Item>>(emptyList())
    var detail by mutableStateOf<DetailUi?>(null)

    // ---- search ----
    var query by mutableStateOf("")
    var results by mutableStateOf<List<SongItem>>(emptyList())
    var searching by mutableStateOf(false)
    var searchError by mutableStateOf<String?>(null)

    // ---- playback ----
    var queue by mutableStateOf(QueueState())
    var playback by mutableStateOf(PlayerState()) // positionMs intentionally unused here
    var positionMs by mutableLongStateOf(0L)
    var volume by mutableFloatStateOf(0.8f)
    var autoplay by mutableStateOf(true)

    // ---- extras ----
    var lyrics by mutableStateOf<LyricsUiState>(LyricsUiState.Idle)
    var accent by mutableStateOf(Accent(Ws.defaultAccent, Ws.defaultAccent2))
    var toast by mutableStateOf<String?>(null)
    var importUi by mutableStateOf<ImportUi>(ImportUi.Idle)
    var likedIds by mutableStateOf<Set<String>>(emptySet())
    var newPlaylistFor by mutableStateOf<SongItem?>(null) // song to add once created
    var newPlaylistOpen by mutableStateOf(false)
    var typing by mutableStateOf(false) // a text field has focus: keyboard shortcuts stand down

    private val engine: PlayerEngine = engineFactory(::onEngineState, ::onEnded)
    private var playingSong: SongItem? = null
    private var playedMax = 0L
    private var sideJob: Job? = null
    private var lyricsJob: Job? = null
    private var toastJob: Job? = null

    init {
        loadSettings()
        engine.setVolume(volume.toDouble())
        scope.launch { library.likedIds.collect { likedIds = it.toSet() } }
    }

    // ---------------- engine callbacks ----------------

    private fun onEngineState(s: PlayerState) {
        if (s.positionMs != positionMs) positionMs = s.positionMs
        if (s.positionMs > playedMax) playedMax = s.positionMs
        val flags = s.copy(positionMs = 0)
        if (flags != playback) playback = flags
    }

    private fun onEnded() = skipNext(auto = true)

    // ---------------- playback control ----------------

    /** Logs the song that just finished/was left, if it was listened to for at least 10 s. */
    private fun flushPlay() {
        val song = playingSong
        val ms = playedMax
        playedMax = 0
        if (song != null && ms >= 10_000) scope.launch { runCatching { library.recordPlay(song, ms) } }
    }

    fun startCurrent() {
        val song = queue.current ?: return
        flushPlay()
        playingSong = song
        positionMs = 0
        engine.play(song.id, queue.upNext?.id)
        if (queue.upNext == null && autoplay) extendWithRadio(song, thenPlayFirstNew = false)
        loadSideEffects(song)
    }

    private fun loadSideEffects(song: SongItem) {
        lyricsJob?.cancel(); sideJob?.cancel()
        lyrics = LyricsUiState.Loading
        lyricsJob = scope.launch { lyrics = LyricsRepository.load(song) }
        sideJob = scope.launch {
            val dominant = song.thumbnail.takeIf { it.isNotBlank() }?.let { ThumbCache.load(it, 128)?.dominant }
            accent = if (dominant != null) accentFrom(dominant) else Accent(Ws.defaultAccent, Ws.defaultAccent2)
        }
    }

    private fun extendWithRadio(after: SongItem, thenPlayFirstNew: Boolean) {
        scope.launch {
            val radio = fetchRadio(after).filter { r -> queue.items.none { it.id == r.id } }
            if (radio.isEmpty()) return@launch
            val firstNew = queue.items.size
            queue = queue.copy(items = queue.items + radio)
            if (thenPlayFirstNew) { queue = queue.jump(firstNew); startCurrent() } else engine.prefetch(radio.first().id)
        }
    }

    fun skipNext(auto: Boolean = false) {
        val ni = queue.nextIndex(auto)
        when {
            ni != null -> { queue = queue.jump(ni); startCurrent() }
            autoplay && queue.current != null -> extendWithRadio(queue.current!!, thenPlayFirstNew = true)
            else -> { flushPlay(); playingSong = null; engine.stop() }
        }
    }

    fun skipPrevious() {
        if (positionMs > 3000) { seek(0); return }
        queue.previousIndex()?.let { queue = queue.jump(it); startCurrent() }
    }

    fun togglePause() = engine.togglePause()
    fun seek(ms: Long) { positionMs = ms; engine.seek(ms) }
    fun changeVolume(v: Float) { volume = v; engine.setVolume(v.toDouble()) }

    fun playFromList(list: List<SongItem>, index: Int) { queue = queue.withItems(list, index); startCurrent() }
    fun playShuffled(list: List<SongItem>) { if (list.isNotEmpty()) playFromList(list.shuffled(), 0) }
    fun jumpTo(index: Int) { queue = queue.jump(index); startCurrent() }

    fun addToQueue(song: SongItem) {
        val was = queue.items.isEmpty(); queue = queue.add(song)
        if (was) { queue = queue.jump(0); startCurrent() } else showToast("Added to queue")
    }

    fun playNext(song: SongItem) {
        queue = queue.playNext(song)
        if (queue.items.size == 1) { queue = queue.jump(0); startCurrent() } else { queue.upNext?.let { engine.prefetch(it.id) }; showToast("Playing next") }
    }

    fun removeFromQueue(position: Int) { queue = queue.remove(position) }
    fun shuffleQueue() { queue = queue.shuffledUpcoming(); queue.upNext?.let { engine.prefetch(it.id) } }
    fun clearQueue() {
        flushPlay(); playingSong = null; engine.stop(); queue = QueueState(); lyrics = LyricsUiState.Idle
        accent = Accent(Ws.defaultAccent, Ws.defaultAccent2)
    }
    fun cycleRepeat() { queue = queue.copy(repeat = RepeatMode.entries[(queue.repeat.ordinal + 1) % RepeatMode.entries.size]) }

    // ---------------- home feed ----------------

    /** Loads the YouTube Music home feed (optionally filtered by a mood chip) plus new releases and moods. */
    fun loadHome(chip: HomePage.Chip? = null) {
        homeChip = chip
        homeLoading = true; homeError = null
        scope.launch {
            YouTube.home(browseId = chip?.endpoint?.browseId ?: "FEmusic_home", params = chip?.endpoint?.params)
                .onSuccess { page ->
                    homeSections = page.sections
                    homeMore = page.continuation
                    if (!page.chips.isNullOrEmpty()) homeChips = page.chips!!
                }
                .onFailure { homeError = it.message ?: "Couldn't load the home feed" }
            homeLoading = false
        }
        if (newReleases.isEmpty() && moods.isEmpty()) scope.launch {
            YouTube.explore().onSuccess { e -> newReleases = e.newReleaseAlbums; moods = e.moodAndGenres }
        }
    }

    fun selectChip(chip: HomePage.Chip) = loadHome(if (homeChip?.title == chip.title) null else chip)

    fun loadMoreHome() {
        val token = homeMore ?: return
        if (homeLoadingMore) return
        homeLoadingMore = true
        scope.launch {
            YouTube.home(continuation = token)
                .onSuccess { page ->
                    val known = homeSections.map { it.title }.toSet()
                    homeSections = homeSections + page.sections.filter { it.title !in known }
                    homeMore = page.continuation
                }
                .onFailure { homeMore = null }
            homeLoadingMore = false
        }
    }

    fun closeDetail() { detail = null }

    fun openPlaylist(p: PlaylistItem) {
        detail = DetailUi.Loading(p.title)
        scope.launch {
            YouTube.playlist(p.id)
                .onSuccess { page ->
                    val songs = page.songs.toMutableList()
                    var next = page.songsContinuation ?: page.continuation
                    var pages = 0
                    while (next != null && pages < 4) { // long playlists: pull a few more pages
                        val more = YouTube.playlistContinuation(next).getOrNull() ?: break
                        songs += more.songs; next = more.continuation; pages++
                    }
                    detail = DetailUi.Songs(
                        page.playlist.title.ifBlank { p.title }, "Playlist",
                        listOfNotNull(page.playlist.author?.name, "${songs.size} songs").joinToString(" · "),
                        page.playlist.thumbnail ?: p.thumbnail, songs.distinctBy { it.id },
                    )
                }
                .onFailure { detail = DetailUi.Failed(p.title, it.message ?: "Couldn't open this playlist") }
        }
    }

    fun openAlbum(a: AlbumItem) {
        detail = DetailUi.Loading(a.title)
        scope.launch {
            YouTube.album(a.browseId)
                .onSuccess { page ->
                    detail = DetailUi.Songs(
                        page.album.title.ifBlank { a.title }, "Album",
                        listOfNotNull(page.album.artists?.joinToString { it.name }, page.album.year?.toString(), "${page.songs.size} songs").joinToString(" · "),
                        page.album.thumbnail.ifBlank { a.thumbnail }, page.songs,
                    )
                }
                .onFailure { detail = DetailUi.Failed(a.title, it.message ?: "Couldn't open this album") }
        }
    }

    fun openMood(item: MoodAndGenres.Item) {
        detail = DetailUi.Loading(item.title)
        scope.launch {
            YouTube.browse(item.endpoint.browseId, item.endpoint.params)
                .onSuccess { r -> detail = DetailUi.Collection(r.title ?: item.title, r.items.filter { it.items.isNotEmpty() }) }
                .onFailure { detail = DetailUi.Failed(item.title, it.message ?: "Couldn't open this category") }
        }
    }

    /** Click on a card: open playlists/albums, play songs from their shelf, search for artists. */
    fun openItem(item: YTItem, siblings: List<YTItem>) {
        when (item) {
            is PlaylistItem -> openPlaylist(item)
            is AlbumItem -> openAlbum(item)
            is SongItem -> { val songs = siblings.filterIsInstance<SongItem>(); playFromList(songs, songs.indexOfFirst { it.id == item.id }.coerceAtLeast(0)) }
            is ArtistItem -> search(item.title)
            else -> Unit
        }
    }

    /** The round play button on a card: start playing right away without opening the page. */
    fun playItem(item: YTItem, siblings: List<YTItem>) {
        when (item) {
            is PlaylistItem -> scope.launch {
                showToast("Loading “${item.title}”…")
                YouTube.playlist(item.id).onSuccess { if (it.songs.isNotEmpty()) playFromList(it.songs, 0) }.onFailure { showToast("Couldn't load that playlist") }
            }
            is AlbumItem -> scope.launch {
                showToast("Loading “${item.title}”…")
                YouTube.album(item.browseId).onSuccess { if (it.songs.isNotEmpty()) playFromList(it.songs, 0) }.onFailure { showToast("Couldn't load that album") }
            }
            else -> openItem(item, siblings)
        }
    }

    /** "Mix": the song you picked followed by YouTube Music's radio of similar songs. */
    fun playMix(seed: SongItem) {
        scope.launch {
            showToast("Building your mix…")
            val radio = fetchRadio(seed)
            if (radio.isEmpty()) { showToast("Couldn't build a mix right now"); return@launch }
            playFromList(listOf(seed) + radio.take(49), 0)
        }
    }

    fun saveAsPlaylist(name: String, songs: List<SongItem>) {
        scope.launch {
            val p = library.createPlaylist(name)
            songs.forEach { library.addToPlaylist(p.id, it) }
            showToast("Saved “${p.name}” to your playlists")
        }
    }

    // ---------------- search ----------------

    fun search(q: String = query) {
        query = q
        if (q.isBlank()) return
        section = Section.SEARCH
        searching = true; searchError = null
        scope.launch {
            runCatching { library.rememberSearch(q) }
            YouTube.search(q, YouTube.SearchFilter.FILTER_SONG)
                .onSuccess { r -> results = r.items.filterIsInstance<SongItem>() }
                .onFailure { searchError = it.message ?: "Search failed" }
            searching = false
        }
    }

    // ---------------- library ----------------

    fun toggleLike(song: SongItem) { scope.launch { library.toggleLike(song) } }

    fun addToPlaylist(playlistId: String, song: SongItem) {
        scope.launch { showToast(if (library.addToPlaylist(playlistId, song)) "Added to playlist" else "Already in that playlist") }
    }

    fun createPlaylist(name: String) {
        val song = newPlaylistFor
        newPlaylistOpen = false; newPlaylistFor = null
        scope.launch {
            val p = library.createPlaylist(name)
            if (song != null) library.addToPlaylist(p.id, song)
            showToast("Created “${p.name}”")
        }
    }

    fun importAndroid(file: File) {
        importUi = ImportUi.Running(file.name)
        scope.launch {
            importUi = runCatching { library.importAndroid(file) }
                .fold({ ImportUi.Done(it) }, { ImportUi.Failed(it.message ?: it.toString()) })
        }
    }

    fun showToast(text: String) {
        toast = text
        toastJob?.cancel()
        toastJob = scope.launch { delay(2400); toast = null }
    }

    // ---------------- lifecycle ----------------

    /** Warms up the stream resolver so the first song starts sooner. */
    fun warmUp() {
        scope.launch(Dispatchers.IO) {
            runCatching { if (YouTube.visitorData == null) YouTube.visitorData().onSuccess { YouTube.visitorData = it } }
            loadHome() // the feed no longer waits for the stream resolver warm-up
            delay(4_000) // keep the first seconds light; this only shortens the first song's start
            InnerTubeXStream.prewarm()
        }
    }

    /** Saves the last listen and settings; called when the window closes. */
    fun shutdown() {
        val song = playingSong; val ms = playedMax
        if (song != null && ms >= 10_000) runBlocking { runCatching { library.recordPlay(song, ms) } }
        saveSettings()
    }

    private fun loadSettings() {
        val f = settingsFile?.takeIf { it.isFile } ?: return
        runCatching {
            val p = Properties().apply { f.inputStream().use(::load) }
            p.getProperty("volume")?.toFloatOrNull()?.let { volume = it.coerceIn(0f, 1f) }
            p.getProperty("autoplay")?.let { autoplay = it.toBoolean() }
            p.getProperty("panelOpen")?.let { panelOpen = it.toBoolean() }
            p.getProperty("repeat")?.let { r -> RepeatMode.entries.firstOrNull { it.name == r }?.let { queue = queue.copy(repeat = it) } }
        }
    }

    private fun saveSettings() {
        val f = settingsFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            Properties().apply {
                setProperty("volume", volume.toString()); setProperty("autoplay", autoplay.toString())
                setProperty("panelOpen", panelOpen.toString()); setProperty("repeat", queue.repeat.name)
            }.let { p -> f.outputStream().use { p.store(it, "W's MUSIC settings") } }
        }
    }
}
