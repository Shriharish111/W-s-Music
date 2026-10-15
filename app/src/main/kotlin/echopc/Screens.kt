package echopc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.AlbumItem
import com.music.innertube.pages.MoodAndGenres
import echopc.db.toSongItem
import echopc.lyrics.LyricsEntry
import echopc.lyrics.LyricsUtils
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Brand accent for pages that have no cover of their own (Liked Songs, history). */
private val BrandAccent = Accent(Ws.defaultAccent, Color(0xFFB8A3F5))

/** Plain message block for empty and error states: what happened and what to do. */
@Composable
internal fun EmptyState(title: String, subtitle: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Ws.text)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
        if (action != null) Box(Modifier.padding(top = 8.dp)) { action() }
    }
}

// =====================================================================================
//  Search
// =====================================================================================

@Composable
fun SearchPage(c: Controller) {
    val recent by c.library.searchHistory().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    when {
        c.typing && c.query.isNotBlank() && c.query.trim() != c.lastSearched -> SuggestionsView(c) // still typing: show matches
        c.searching && c.results.isEmpty() -> EmptyState("Searching for “${c.query}”", "")
        c.searchError != null -> EmptyState("Search didn't work", "${c.searchError}. Check your connection and try again.") {
            PrimaryButton("Try again", { c.search() }, icon = Icons.Rounded.Refresh)
        }
        c.searchDone && c.results.isEmpty() && c.searchSummaries.isEmpty() -> EmptyState("No results for “${c.query}”", "Check the spelling, or try the artist or album name.")
        c.results.isEmpty() && c.searchSummaries.isEmpty() -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (recent.isNotEmpty()) item("recent") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent searches", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = Ws.text)
                        Text("Clear", Modifier.clickable { scope.launch { c.library.clearSearchHistory() } }.handCursor(), style = MaterialTheme.typography.labelLarge, color = Ws.muted)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { itemsIndexed(recent, key = { _, h -> h.id }) { _, h -> WsChip(h.query, { c.search(h.query) }) } }
                }
            }
            item("browse-title") { Text("Browse all", style = MaterialTheme.typography.headlineMedium, color = Ws.text) }
            if (c.moods.isEmpty()) item("browse-empty") {
                Text("Type a song or artist in the search bar above.", style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
            } else item("browse") { BrowseGrid(c, c.moods.take(40)) }
        }
        else -> SearchResults(c)
    }
}

@Composable
private fun SearchResults(c: Controller) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        val columns = ((maxWidth - 24.dp) / 184.dp).toInt().coerceAtLeast(1)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)) {
            item("tabs") {
                LazyRow(Modifier.padding(bottom = 8.dp), contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(SearchTab.entries) { _, tab -> WsChip(tab.label, { c.selectSearchTab(tab) }, selected = c.searchTab == tab) }
                }
            }
            when (c.searchTab) {
                SearchTab.All -> if (c.searchSummaries.isEmpty()) songsOnlyView(c, wide) else allView(c, wide)
                SearchTab.Songs, SearchTab.Videos -> {
                    val songs = c.tabItems.filterIsInstance<SongItem>()
                    if (songs.isNotEmpty()) {
                        item("columns") { TrackColumns(wide) }
                        trackRows(c, songs, wide)
                    }
                    tabFooter(c)
                }
                else -> {
                    itemsIndexed(c.tabItems.chunked(columns), key = { i, _ -> "grid-$i" }) { _, row ->
                        Row(Modifier.padding(horizontal = 12.dp)) { row.forEach { ItemCard(c, it, c.tabItems) } }
                    }
                    tabFooter(c)
                }
            }
        }
    }
}

/** Under a filtered list: loading text, "no results", or the button for the next page. */
private fun LazyListScope.tabFooter(c: Controller) {
    item("footer") {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            when {
                c.tabLoading -> Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
                c.tabItems.isEmpty() -> Text("No ${c.searchTab.label.lowercase()} found for “${c.query}”.", style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
                c.tabMore -> OutlineButton("Show more", { c.loadMoreSearch() })
            }
        }
    }
}

/** Plain song list (used when YouTube's mixed results are not available and only songs could be fetched). */
private fun LazyListScope.songsOnlyView(c: Controller, wide: Boolean) {
    val songs = c.results
    if (songs.isEmpty()) return
    item("top") {
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(if (wide) Modifier.weight(0.42f) else Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Top result", style = MaterialTheme.typography.headlineMedium, color = Ws.text)
                TopResultCard(c, songs.first(), songs)
            }
            if (wide) Column(Modifier.weight(0.58f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Songs", style = MaterialTheme.typography.headlineMedium, color = Ws.text)
                songs.drop(1).take(4).forEachIndexed { i, s -> TrackRow(c, s, i + 1, songs, wide = false) }
            }
        }
    }
    item("all-title") {
        Text("All songs", Modifier.padding(start = 24.dp, top = 32.dp, bottom = 8.dp), style = MaterialTheme.typography.headlineMedium, color = Ws.text)
    }
    item("columns") { TrackColumns(wide) }
    trackRows(c, songs, wide)
}

/** YouTube Music's mixed results: top result, then songs, videos, albums, artists and playlists, each with a "Show all" link. */
private fun LazyListScope.allView(c: Controller, wide: Boolean) {
    val summaries = c.searchSummaries
    val top = summaries.firstOrNull()?.takeIf { it.title.equals("Top result", ignoreCase = true) }
    val topItem = top?.items?.firstOrNull()
    if (topItem != null) item("top") {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Top result", style = MaterialTheme.typography.headlineMedium, color = Ws.text)
            Box(Modifier.widthIn(max = 520.dp)) { TopResultCard(c, topItem, c.results) }
        }
    }
    summaries.filter { it !== top }.forEachIndexed { i, section ->
        val tab = SearchTab.entries.firstOrNull { it != SearchTab.All && it.label.equals(section.title, ignoreCase = true) }
        item("hdr-$i") {
            Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = Ws.text)
                if (tab != null) Text("Show all", Modifier.clickable { c.selectSearchTab(tab) }.handCursor(), style = MaterialTheme.typography.labelLarge, color = Ws.muted)
            }
        }
        val songs = section.items.filterIsInstance<SongItem>()
        if (songs.size == section.items.size) {
            itemsIndexed(songs, key = { _, song -> "s$i-${song.id}" }) { idx, song -> TrackRow(c, song, idx, songs, wide = false) } // index = position in `songs`
        } else item("shelf-$i") {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) { itemsIndexed(section.items) { _, it -> ItemCard(c, it, section.items) } }
        }
    }
}

@Composable
private fun TopResultCard(c: Controller, item: YTItem, songs: List<SongItem>) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val kind = when (item) { is SongItem -> if (item.isVideoSong) "Video" else "Song"; is AlbumItem -> "Album"; is ArtistItem -> "Artist"; is PlaylistItem -> "Playlist"; else -> "" }
    val detail = when (item) {
        is SongItem -> item.artists.joinToString { it.name }
        is AlbumItem -> item.artists?.joinToString { it.name }.orEmpty()
        is PlaylistItem -> item.author?.name.orEmpty()
        else -> ""
    }
    val open = {
        if (item is SongItem) c.playFromList(songs.ifEmpty { listOf(item) }, songs.indexOfFirst { it.id == item.id }.coerceAtLeast(0)) else c.openItem(item, emptyList())
    }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (hovered) Ws.surfaceTop else Ws.surfaceHi)
            .hoverable(source).clickable(source, null) { open() }.handCursor().padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Thumb(item.thumbnail, 92.dp, corner = if (item is ArtistItem) 46.dp else 6.dp, seed = item.title)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = MaterialTheme.typography.headlineLarge, color = Ws.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(kind, detail).filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodyMedium, color = Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (hovered && item !is ArtistItem) PlayButton(false, false, true, { if (item is SongItem) open() else c.playItem(item, emptyList()) }, Modifier.align(Alignment.BottomEnd), size = 48.dp, onAccent = true)
    }
}

/** "Browse all": big colour tiles, one per mood / genre, in that category's own colour. */
@Composable
fun BrowseGrid(c: Controller, items: List<MoodAndGenres.Item>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = when { maxWidth >= 1000.dp -> 5; maxWidth >= 760.dp -> 4; maxWidth >= 520.dp -> 3; else -> 2 }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { BrowseTile(it, { c.openMood(it) }, Modifier.weight(1f)) }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun BrowseTile(item: MoodAndGenres.Item, onClick: () -> Unit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val base = remember(item) { Color(item.stripeColor.toInt() or (0xFF shl 24)) }
    // Darken a little so white text always reads; a touch lighter on hover.
    val bg = androidx.compose.ui.graphics.lerp(base, Color.Black, if (hovered) 0.12f else 0.25f)
    Box(
        modifier.height(100.dp).clip(RoundedCornerShape(8.dp)).background(bg)
            .hoverable(source).clickable(source, null, onClick = onClick).handCursor().padding(16.dp),
    ) {
        Text(item.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// =====================================================================================
//  Library collections
// =====================================================================================

@Composable
fun LikedPage(c: Controller) {
    val songs by c.library.likedSongs.collectAsState(emptyList())
    val items = remember(songs) { songs.map { it.toSongItem() } }
    CollectionPage(
        c, "Playlist", "Liked Songs", "${items.size} songs", CoverArt.Icon(Icons.Rounded.Favorite), items,
        emptyText = "Songs you like show up here. Click the heart next to any song, or import your likes from the Android app.",
        accent = BrandAccent,
    )
}

@Composable
fun LibrarySongsPage(c: Controller) {
    val songs by c.library.librarySongs.collectAsState(emptyList())
    val items = remember(songs) { songs.map { it.toSongItem() } }
    val scope = rememberCoroutineScope()
    CollectionPage(
        c, "Collection", "Your songs", "${items.size} songs", CoverArt.Icon(Icons.Rounded.MusicNote), items,
        emptyText = "Liked and imported songs land here automatically.",
        accent = Accent(Color(0xFF3A3A3A), Color(0xFF5A5A5A)),
        rowTrailing = { i -> WsIconButton(Icons.Rounded.Close, "Remove from Your songs", { scope.launch { c.library.removeFromLibrary(items[i].id) } }, size = 32.dp, iconSize = 16.dp) },
    )
}

@Composable
fun HistoryPage(c: Controller) {
    val events by c.library.history().collectAsState(emptyList())
    val items = remember(events) { events.map { it.song.toSongItem() } }
    val fmt = remember { DateTimeFormatter.ofPattern("MMM d, HH:mm") }
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    CollectionPage(
        c, "History", "Recently played", "${events.size} plays", CoverArt.Icon(Icons.Rounded.History), items,
        emptyText = "Songs you listen to for at least 10 seconds show up here.",
        accent = Accent(Color(0xFF2F5D62), Color(0xFF5E8C61)),
        extraColumn = "Played" to { i -> events[i].event.timestamp.format(fmt) },
        actions = { if (events.isNotEmpty()) OutlineButton("Clear history", { confirmClear = true }, danger = true) },
    )
    if (confirmClear) ConfirmDialog(
        "Clear your listening history?", "Your plays are removed from this PC. Liked songs and playlists stay.", "Clear history",
        { scope.launch { c.library.clearHistory() }; confirmClear = false }, { confirmClear = false },
    )
}

@Composable
fun LocalPlaylistPage(c: Controller, id: String) {
    val playlist by c.library.playlist(id).collectAsState(null)
    val rows by c.library.playlistSongs(id).collectAsState(emptyList())
    val items = remember(rows) { rows.map { it.song.toSongItem() } }
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val name = playlist?.playlist?.name.orEmpty()
    CollectionPage(
        c, "Playlist", name, "${items.size} songs", CoverArt.Image(playlist?.thumbnails?.firstOrNull() ?: items.firstOrNull()?.thumbnail, name), items,
        emptyText = "This playlist is empty. Use the ⋯ menu on any song and choose Add to playlist.",
        rowTrailing = { i -> WsIconButton(Icons.Rounded.Close, "Remove from this playlist", { scope.launch { c.library.removeFromPlaylist(rows[i].map) } }, size = 32.dp, iconSize = 16.dp) },
        actions = {
            WsIconButton(Icons.Rounded.Edit, "Rename playlist", { renaming = true }, size = 40.dp, iconSize = 22.dp)
            WsIconButton(Icons.Rounded.Delete, "Delete playlist", { confirmDelete = true }, size = 40.dp, iconSize = 22.dp, hoverTint = Ws.danger)
        },
    )
    if (renaming) NameDialog("Rename playlist", name, "Save", { n -> scope.launch { c.library.renamePlaylist(id, n) }; renaming = false }, { renaming = false })
    if (confirmDelete) ConfirmDialog(
        "Delete “$name”?", "The playlist is removed. Its songs stay in Your songs.", "Delete",
        { scope.launch { c.library.deletePlaylist(id) }; confirmDelete = false; c.goBack() }, { confirmDelete = false },
    )
}

/** A generated mix. It reads from the in-memory list, so it opens instantly. */
@Composable
fun MixPage(c: Controller, id: String) {
    val mix = c.mixes.firstOrNull { it.id == id }
    if (mix == null) {
        EmptyState("This mix isn't ready", "Mixes are rebuilt from your listening. Play some more music and it will show up on Home.")
        return
    }
    val songs = remember(mix.songs) { c.mixSongs(mix) }
    val updated = remember(mix.generatedAt) { mix.generatedAt.format(DateTimeFormatter.ofPattern("MMM d")) }
    CollectionPage(
        c, "Made for you", mix.title, "${mix.subtitle} • ${songs.size} songs • updated $updated",
        CoverArt.Image(songs.firstOrNull()?.thumbnail, mix.title), songs,
        emptyText = "Nothing in this mix yet.",
    )
}

// =====================================================================================
//  Lyrics (full page)
// =====================================================================================

@Composable
fun LyricsPage(c: Controller) {
    val accent = LocalAccent.current
    // Brighter than the usual flood so the dark upcoming lines still read against it.
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.lerp(accent.primary, Color.Black, 0.25f))) {
        when (val s = c.lyrics) {
            LyricsUiState.Idle -> EmptyState("Nothing is playing", "Play a song to see its lyrics here.")
            LyricsUiState.Loading -> EmptyState("Finding lyrics", "")
            LyricsUiState.NotFound -> EmptyState("No lyrics for this song", "None of the lyrics sources have it yet.")
            is LyricsUiState.Loaded -> LoadedLyrics(c, s)
        }
    }
}

@Composable
private fun LoadedLyrics(c: Controller, state: LyricsUiState.Loaded) {
    var romanize by remember(state) { mutableStateOf(false) }
    val romanized = remember(state) { androidx.compose.runtime.mutableStateMapOf<Int, String>() }
    val canRomanize = remember(state) { state.lines.any { l -> l.text.any { it.code > 0x2FF } } }
    LaunchedEffect(romanize, state) {
        if (!romanize) return@LaunchedEffect
        state.lines.forEachIndexed { i, line ->
            if (i !in romanized) withContext(Dispatchers.Default) { LyricsRepository.romanize(line.text) }?.let { romanized[i] = it }
        }
    }
    // Only recomposes when the highlighted line changes, not on every position tick.
    val currentIndex by remember(state) { derivedStateOf { if (state.synced) LyricsUtils.findCurrentLineIndex(state.lines, c.positionMs) else -1 } }
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) { if (currentIndex >= 0) listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0)) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 24.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetaText("Lyrics from ${state.provider}" + if (state.synced) "" else ", not time-synced", Modifier.weight(1f))
            if (canRomanize) WsChip("Romanize", { romanize = !romanize }, selected = romanize)
        }
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 32.dp, bottom = 160.dp)) {
            itemsIndexed(state.lines) { i, line ->
                LyricLine(c, line, romanized[i].takeIf { romanize }, active = i == currentIndex, past = state.synced && currentIndex >= 0 && i < currentIndex, seekable = state.synced)
            }
        }
    }
}

@Composable
private fun LyricLine(c: Controller, line: LyricsEntry, romanized: String?, active: Boolean, past: Boolean, seekable: Boolean) {
    // Past lines stay readable, upcoming lines recede; the current line is pure white.
    val idle = if (past) Ws.text.copy(alpha = 0.72f) else Color.Black.copy(alpha = 0.55f)
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(
        Modifier.hoverable(source).then(if (seekable) Modifier.clickable { c.seek(line.time) }.handCursor() else Modifier),
    ) {
        val words = line.words
        val text = if (active && !words.isNullOrEmpty()) {
            // Word-by-word highlight; reads the position directly so only this line redraws.
            val now = c.positionMs / 1000.0
            buildAnnotatedString {
                words.forEachIndexed { idx, w ->
                    withStyle(SpanStyle(color = if (now >= w.startTime) Ws.text else Ws.text.copy(alpha = 0.5f))) { append(w.text) }
                    if (idx < words.lastIndex) append(" ")
                }
            }
        } else buildAnnotatedString {
            withStyle(SpanStyle(color = if (active || (hovered && seekable)) Ws.text else idle)) { append(line.text) }
        }
        Text(text, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, lineHeight = 38.sp))
        if (romanized != null) Text(romanized, style = MaterialTheme.typography.titleMedium, color = if (active) Ws.text else idle)
    }
}
