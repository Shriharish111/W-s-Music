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
        c.searching && c.results.isEmpty() -> EmptyState("Searching for “${c.query}”", "")
        c.searchError != null -> EmptyState("Search didn't work", "${c.searchError}. Check your connection and try again.") {
            PrimaryButton("Try again", { c.search() }, icon = Icons.Rounded.Refresh)
        }
        c.results.isEmpty() -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
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
    val songs = c.results
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)) {
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
    }
}

@Composable
private fun TopResultCard(c: Controller, song: SongItem, songs: List<SongItem>) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (hovered) Ws.surfaceTop else Ws.surfaceHi)
            .hoverable(source).clickable(source, null) { c.playFromList(songs, 0) }.handCursor().padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Thumb(song.thumbnail, 92.dp, corner = 6.dp, seed = song.title)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(song.title, style = MaterialTheme.typography.headlineLarge, color = Ws.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Song • ${song.artists.joinToString { it.name }}", style = MaterialTheme.typography.bodyMedium, color = Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (hovered) PlayButton(false, false, true, { c.playFromList(songs, 0) }, Modifier.align(Alignment.BottomEnd), size = 48.dp, onAccent = true)
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
