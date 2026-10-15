package echopc

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import echopc.db.EndReason
import echopc.db.GeneratedMix
import echopc.db.LibraryRepository
import echopc.db.StartReason
import echopc.db.TrackCodec
import echopc.reco.CandidateSource
import echopc.reco.Recommender
import echopc.reco.ShelfBandit
import echopc.reco.YouTubeCandidates
import java.io.File
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** A page of the main view. The controller keeps a back stack of these (top bar back/forward). */
sealed interface Route {
    data object Home : Route
    data object Search : Route
    data object Liked : Route
    data object LibrarySongs : Route
    data object History : Route
    data object Lyrics : Route
    data class LocalPlaylist(val id: String) : Route
    /** A generated mix (Daily Mix, Discover Weekly, ...). */
    data class Mix(val id: String) : Route
    /** An online playlist / album / mood page; its content lives in [Controller.details] under [key]. */
    data class Online(val key: String) : Route
}

/** The "where it was played from" string stored with each play. */
fun routeContext(r: Route): String = when (r) {
    Route.Home -> "home"
    Route.Search -> "search"
    Route.Liked -> "liked"
    Route.LibrarySongs -> "library"
    Route.History -> "history"
    Route.Lyrics -> "lyrics"
    is Route.LocalPlaylist -> "local:${r.id}"
    is Route.Online -> r.key
    is Route.Mix -> "mix:${r.id}"
}

/** What the right-hand panel shows. */
enum class PanelTab { NOW_PLAYING, QUEUE }

/** Library sidebar width limits in dp (it can also collapse to an icon strip). */
const val SIDEBAR_MIN = 240f
const val SIDEBAR_MAX = 440f

/** Content of an online playlist / album / mood page. */
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
    recoSource: CandidateSource? = null,
    engineFactory: (onState: (PlayerState) -> Unit, onEnded: () -> Unit) -> PlayerEngine,
) {
    // ---- navigation ----
    var backStack by mutableStateOf<List<Route>>(listOf(Route.Home))
        private set
    private var forwardStack by mutableStateOf<List<Route>>(emptyList())
    val route: Route get() = backStack.last()
    val canGoBack: Boolean get() = backStack.size > 1
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()
    val details = mutableStateMapOf<String, DetailUi>()
    var panelOpen by mutableStateOf(true)
    var panelTab by mutableStateOf(PanelTab.NOW_PLAYING)
    var sidebarWidth by mutableFloatStateOf(300f) // dp
    var sidebarCollapsed by mutableStateOf(false)

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

    // ---- recommendations ----
    val recommender = Recommender(library, recoSource ?: YouTubeCandidates(library))
    var mixes by mutableStateOf<List<GeneratedMix>>(emptyList())
    var mixesRefreshing by mutableStateOf(false)
    private var signalsSinceRefresh = 0
    private val bandit = ShelfBandit()
    private var shelfStats: Map<String, Pair<Long, Long>> = emptyMap()
    private var shelfOrder: List<String> = emptyList()
    private val shelfImpressions = HashSet<String>()

    private val engine: PlayerEngine = engineFactory(::onEngineState, ::onEnded)
    private var playingSong: SongItem? = null
    private var playedMax = 0L
    private var playStartReason = StartReason.CLICK_ROW
    private var playContext = "home"
    /** Where the current queue came from ("liked", "local:<id>", "mix:<id>", ...); radio additions are "radio". */
    private var queueContext = "home"
    private val radioIds = HashSet<String>()
    /** Play logging: one worker runs the writes strictly in the order plays end. */
    private val playLog = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var sideJob: Job? = null
    private var lyricsJob: Job? = null
    private var toastJob: Job? = null

    init {
        loadSettings()
        engine.setVolume(volume.toDouble())
        scope.launch { library.likedIds.collect { likedIds = it.toSet() } }
        scope.launch { library.mixes.collect { mixes = it } }
        scope.launch { loadShelfStats() }
        scope.launch(Dispatchers.IO) { for (write in playLog) runCatching { write() } }
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

    /**
     * Closes the play that is ending: a taste signal for any play (a quick skip is a strong signal),
     * and a history entry if it was listened to for at least 10 s.
     */
    private fun flushPlay(endReason: String) {
        val song = playingSong ?: return
        val ms = playedMax
        val duration = playback.durationMs.takeIf { it > 0 } ?: ((song.duration ?: 0) * 1000L)
        val reason = if (playback.error != null) EndReason.ERROR else endReason
        val start = playStartReason; val context = playContext
        val at = java.time.LocalDateTime.now()
        playedMax = 0
        playingSong = null
        playLog.trySend {
            if (ms >= 10_000) library.recordPlay(song, ms)
            if (ms > 0 || reason == EndReason.FWD_BUTTON) {
                library.recordSignal(song, ms, duration, start, reason, context, at)
                onSignalRecorded()
            }
        }
    }

    /** Starts the queue's current song; [start] says why it starts, [ended] why the previous one stopped. */
    fun startCurrent(start: String = StartReason.CLICK_ROW, ended: String = EndReason.NEW_SELECTION) {
        val song = queue.current ?: return
        flushPlay(ended)
        playingSong = song
        playStartReason = start
        playContext = if (song.id in radioIds) "radio" else queueContext
        positionMs = 0
        engine.play(song.id, queue.upNext?.id)
        if (queue.upNext == null && autoplay) extendWithRadio(song, thenPlayFirstNew = false, ended = ended)
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

    private fun extendWithRadio(after: SongItem, thenPlayFirstNew: Boolean, ended: String) {
        scope.launch {
            val radio = fetchRadio(after).filter { r -> queue.items.none { it.id == r.id } }
            if (radio.isEmpty()) return@launch
            radioIds += radio.map { it.id }
            val firstNew = queue.items.size
            queue = queue.copy(items = queue.items + radio)
            if (thenPlayFirstNew) { queue = queue.jump(firstNew); startCurrent(StartReason.AUTOPLAY, ended) } else engine.prefetch(radio.first().id)
        }
    }

    fun skipNext(auto: Boolean = false) {
        val ni = queue.nextIndex(auto)
        val reason = if (auto) EndReason.TRACK_DONE else EndReason.FWD_BUTTON
        when {
            ni != null -> { queue = queue.jump(ni); startCurrent(if (auto) StartReason.TRACK_DONE else StartReason.FWD_BUTTON, reason) }
            autoplay && queue.current != null -> extendWithRadio(queue.current!!, thenPlayFirstNew = true, ended = reason)
            else -> { flushPlay(reason); engine.stop() }
        }
    }

    fun skipPrevious() {
        if (positionMs > 3000) { seek(0); return }
        queue.previousIndex()?.let { queue = queue.jump(it); startCurrent(StartReason.BACK_BUTTON, EndReason.BACK_BUTTON) }
    }

    fun togglePause() = engine.togglePause()
    fun seek(ms: Long) { positionMs = ms; engine.seek(ms) }
    fun changeVolume(v: Float) { volume = v; engine.setVolume(v.toDouble()) }

    /** Plays [list] from [index]; the page it was started from becomes the play context. */
    fun playFromList(list: List<SongItem>, index: Int, context: String = routeContext(route)) {
        queueContext = context
        radioIds.clear()
        queue = queue.withItems(list, index)
        startCurrent()
    }
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
        flushPlay(EndReason.CLOSED); engine.stop(); queue = QueueState(); lyrics = LyricsUiState.Idle
        accent = Accent(Ws.defaultAccent, Ws.defaultAccent2)
    }
    fun cycleRepeat() { queue = queue.copy(repeat = RepeatMode.entries[(queue.repeat.ordinal + 1) % RepeatMode.entries.size]) }

    // ---------------- home feed ----------------

    /** Loads the YouTube Music home feed (optionally filtered by a mood chip) plus new releases and moods. */
    fun loadHome(chip: HomePage.Chip? = null) {
        homeChip = chip
        shelfOrder = emptyList(); shelfImpressions.clear()
        scope.launch { loadShelfStats() }
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

    // ---------------- navigation ----------------

    /** Opens [to] in the main view. Re-opening the current page does nothing. */
    fun navigate(to: Route) {
        if (route == to) return
        backStack = backStack + to
        forwardStack = emptyList()
    }

    fun goBack() {
        if (!canGoBack) return
        forwardStack = listOf(route) + forwardStack
        backStack = backStack.dropLast(1)
    }

    fun goForward() {
        val next = forwardStack.firstOrNull() ?: return
        forwardStack = forwardStack.drop(1)
        backStack = backStack + next
    }

    /** Home button: Home without wiping history (like a browser's home button). */
    fun goHome() = navigate(Route.Home)

    /** Lyrics live in the main view; the player bar button toggles them. */
    fun toggleLyrics() = if (route == Route.Lyrics) goBack() else navigate(Route.Lyrics)

    /** Player bar buttons for the right panel: open on [tab], or close it if it already shows [tab]. */
    fun togglePanel(tab: PanelTab) {
        if (panelOpen && panelTab == tab) panelOpen = false else { panelTab = tab; panelOpen = true }
    }

    /** Shows [ui] as an online page (also used by tests to show a page without the network). */
    fun showDetail(key: String, ui: DetailUi) { details[key] = ui; navigate(Route.Online(key)) }

    private fun loadDetail(key: String, title: String, load: suspend () -> DetailUi) {
        details[key] = DetailUi.Loading(title)
        navigate(Route.Online(key))
        scope.launch { details[key] = runCatching { load() }.getOrElse { DetailUi.Failed(title, it.message ?: "Couldn't open “$title”") } }
    }

    fun openPlaylist(p: PlaylistItem) = loadDetail("playlist:${p.id}", p.title) {
        val page = YouTube.playlist(p.id).getOrThrow()
        val songs = page.songs.toMutableList()
        var next = page.songsContinuation ?: page.continuation
        var pages = 0
        while (next != null && pages < 4) { // long playlists: pull a few more pages
            val more = YouTube.playlistContinuation(next).getOrNull() ?: break
            songs += more.songs; next = more.continuation; pages++
        }
        DetailUi.Songs(
            page.playlist.title.ifBlank { p.title }, "Playlist",
            listOfNotNull(page.playlist.author?.name, "${songs.size} songs").joinToString(" • "),
            page.playlist.thumbnail ?: p.thumbnail, songs.distinctBy { it.id },
        )
    }

    fun openAlbum(a: AlbumItem) = loadDetail("album:${a.browseId}", a.title) {
        val page = YouTube.album(a.browseId).getOrThrow()
        DetailUi.Songs(
            page.album.title.ifBlank { a.title }, "Album",
            listOfNotNull(page.album.artists?.joinToString { it.name }, page.album.year?.toString(), "${page.songs.size} songs").joinToString(" • "),
            page.album.thumbnail.ifBlank { a.thumbnail }, page.songs,
        )
    }

    fun openMood(item: MoodAndGenres.Item) = loadDetail("mood:${item.endpoint.browseId}:${item.endpoint.params}", item.title) {
        val r = YouTube.browse(item.endpoint.browseId, item.endpoint.params).getOrThrow()
        DetailUi.Collection(r.title ?: item.title, r.items.filter { it.items.isNotEmpty() })
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
            playFromList(listOf(seed) + radio.take(49), 0, context = "radio")
        }
    }

    fun saveAsPlaylist(name: String, songs: List<SongItem>) {
        scope.launch {
            val p = library.createPlaylist(name)
            songs.forEach { library.addToPlaylist(p.id, it) }
            showToast("Saved “${p.name}” to your playlists")
        }
    }

    // ---------------- recommendations ----------------

    /** Rebuilds whatever mixes are due (in the background); [force] rebuilds all of them. */
    fun refreshMixes(force: Boolean = false) {
        if (mixesRefreshing) return
        mixesRefreshing = true
        signalsSinceRefresh = 0
        scope.launch(Dispatchers.IO) {
            runCatching { recommender.refresh(force) }.onFailure { println("[reco] refresh failed: ${it.message}") }
            mixesRefreshing = false
        }
    }

    private fun onSignalRecorded() {
        // Plenty of new listening: let the daily mixes catch up without waiting for tomorrow.
        if (++signalsSinceRefresh >= 20) refreshMixes()
    }

    fun mixSongs(mix: GeneratedMix): List<SongItem> = TrackCodec.decode(mix.songs)

    fun playGeneratedMix(mix: GeneratedMix) {
        val songs = mixSongs(mix)
        if (songs.isNotEmpty()) playFromList(songs, 0, context = "mix:${mix.id}")
    }

    private suspend fun loadShelfStats() {
        runCatching { shelfStats = library.shelfStats().mapValues { it.value.impressions to it.value.clicks } }
    }

    /**
     * Home shelf order for this visit, from the bandit. Fixed once drawn (so the page doesn't shuffle
     * while you look at it); shelves that appear later (infinite scroll) are ranked among themselves
     * and added at the end.
     */
    fun orderShelves(keys: List<String>): List<String> {
        val fresh = keys.filter { it !in shelfOrder }
        if (fresh.isNotEmpty()) shelfOrder = shelfOrder + bandit.order(fresh, shelfStats)
        return shelfOrder.filter { it in keys }
    }

    /** Counts each shelf once per Home visit. */
    fun noteShelfImpressions(keys: List<String>) {
        val new = keys.filter { shelfImpressions.add(it) }
        if (new.isNotEmpty()) scope.launch { runCatching { library.recordShelfImpressions(new) } }
    }

    fun noteShelfClick(key: String) { scope.launch { runCatching { library.recordShelfClick(key) } } }

    // ---------------- search ----------------

    fun search(q: String = query) {
        query = q
        if (q.isBlank()) return
        navigate(Route.Search)
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
            refreshMixes()
        }
    }

    /** Saves the last listen and settings; called when the window closes. */
    fun shutdown() {
        val song = playingSong; val ms = playedMax
        val duration = playback.durationMs.takeIf { it > 0 } ?: ((song?.duration ?: 0) * 1000L)
        if (song != null) runBlocking {
            runCatching {
                if (ms >= 10_000) library.recordPlay(song, ms)
                if (ms > 0) library.recordSignal(song, ms, duration, playStartReason, EndReason.CLOSED, playContext)
            }
        }
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
            p.getProperty("panelTab")?.let { t -> PanelTab.entries.firstOrNull { it.name == t }?.let { panelTab = it } }
            p.getProperty("sidebarWidth")?.toFloatOrNull()?.let { sidebarWidth = it.coerceIn(SIDEBAR_MIN, SIDEBAR_MAX) }
            p.getProperty("sidebarCollapsed")?.let { sidebarCollapsed = it.toBoolean() }
        }
    }

    private fun saveSettings() {
        val f = settingsFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            Properties().apply {
                setProperty("volume", volume.toString()); setProperty("autoplay", autoplay.toString())
                setProperty("panelOpen", panelOpen.toString()); setProperty("repeat", queue.repeat.name)
                setProperty("panelTab", panelTab.name)
                setProperty("sidebarWidth", sidebarWidth.toString()); setProperty("sidebarCollapsed", sidebarCollapsed.toString())
            }.let { p -> f.outputStream().use { p.store(it, "W's MUSIC settings") } }
        }
    }
}
