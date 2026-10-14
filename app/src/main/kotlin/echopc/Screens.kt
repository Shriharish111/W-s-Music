package echopc

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.innertube.models.SongItem
import echo.music.iad1tya.db.entities.Playlist
import echopc.db.toSongItem
import echopc.lyrics.LyricsEntry
import echopc.lyrics.LyricsUtils
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun fmtTime(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

// =====================================================================================
//  Root layout
// =====================================================================================

@Composable
fun AppContent(c: Controller, onPickAndroidFile: () -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.fillMaxSize().background(Ws.bg0).drawBackdrop(accent),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
                NavRail(c)
                Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp)) {
                    run { val s = c.section
                        when (s) {
                            Section.HOME -> HomeScreen(c)
                            Section.SEARCH -> SearchScreen(c)
                            Section.LIBRARY -> LibraryScreen(c, onPickAndroidFile)
                        }
                    }
                }
                if (c.panelOpen) SidePanel(c)
            }
            PlayerBar(c)
        }
        // Toast
        Box(Modifier.align(Alignment.TopCenter).padding(top = 18.dp)) {
            c.toast?.let { message ->
                Text(
                    message,
                    Modifier.panel(14.dp, Ws.surfaceTop).padding(horizontal = 18.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge, color = Ws.text,
                )
            }
        }
        // Dialogs
        if (c.newPlaylistOpen) NameDialog("New playlist", "", "Create", onConfirm = c::createPlaylist, onDismiss = { c.newPlaylistOpen = false; c.newPlaylistFor = null })
        ImportDialog(c)
    }
}

/** Two big blurred colour pools in the background that follow the album-art accent. */
private fun Modifier.drawBackdrop(accent: Accent): Modifier = this.drawWithCache {
    // Brushes are built once per size/accent change, not on every frame.
    val w = size.width; val h = size.height
    val a = Brush.radialGradient(listOf(accent.primary.copy(alpha = 0.20f), Color.Transparent), androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.05f), w * 0.55f)
    val b = Brush.radialGradient(listOf(accent.secondary.copy(alpha = 0.13f), Color.Transparent), androidx.compose.ui.geometry.Offset(w * 0.95f, h * 0.95f), w * 0.5f)
    onDrawBehind { drawRect(a); drawRect(b) }
}

// =====================================================================================
//  Navigation rail
// =====================================================================================

@Composable
private fun NavRail(c: Controller) {
    val accent = LocalAccent.current
    Column(
        Modifier.width(76.dp).fillMaxHeight().panel(22.dp).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Logo mark
        Box(
            Modifier.size(44.dp).glow(accent.primary, 44.dp, 0.5f).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(accent.primary, accent.secondary))),
            contentAlignment = Alignment.Center,
        ) { Text("W", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(12.dp))
        NavItem(Icons.Rounded.Home, "Home", c.section == Section.HOME) { if (c.section == Section.HOME) c.closeDetail(); c.section = Section.HOME }
        NavItem(Icons.Rounded.Search, "Search", c.section == Section.SEARCH) { c.section = Section.SEARCH }
        NavItem(Icons.Rounded.LibraryMusic, "Library", c.section == Section.LIBRARY) { c.section = Section.LIBRARY }
        Spacer(Modifier.weight(1f))
        NavItem(Icons.Rounded.QueueMusic, "Panel", c.panelOpen) { c.panelOpen = !c.panelOpen }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    HoverRow(onClick, Modifier.padding(horizontal = 8.dp), selected = selected) { _ ->
        Column(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(icon, label, Modifier.size(24.dp), tint = if (selected) accent.highlight else Ws.muted)
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (selected) Ws.text else Ws.faint, maxLines = 1)
        }
    }
}

// =====================================================================================
//  Song rows & menu
// =====================================================================================

@Composable
fun SongRow(
    c: Controller,
    song: SongItem,
    index: Int,
    songs: List<SongItem>,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
) {
    val current = c.queue.current?.id == song.id
    val liked = song.id in c.likedIds
    HoverRow({ c.playFromList(songs, index) }, modifier, selected = current) { hovered ->
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                Thumb(song.thumbnail, 46.dp, corner = 10.dp, seed = song.title)
                if (hovered && !current) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(26.dp), tint = Color.White)
                } else if (current) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    EqBars(active = c.playback.playing)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(song.title, style = MaterialTheme.typography.titleSmall, color = if (current) LocalAccent.current.highlight else Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artists.joinToString { it.name }.ifBlank { "Unknown artist" }, style = MaterialTheme.typography.bodySmall, color = Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            extra()
            song.duration?.takeIf { it > 0 }?.let { Text(fmtTime(it * 1000L), style = MaterialTheme.typography.bodySmall, color = Ws.faint) }
            WsIconButton(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Unlike" else "Like", { c.toggleLike(song) },
                size = 34.dp, iconSize = 20.dp, tint = if (liked) LocalAccent.current.highlight else if (hovered) Ws.muted else Ws.faint, hoverTint = LocalAccent.current.highlight,
            )
            SongMenu(c, song)
        }
    }
}

@Composable
fun SongMenu(c: Controller, song: SongItem) {
    var open by remember { mutableStateOf(false) }
    var inPlaylists by remember { mutableStateOf(false) }
    val playlists by c.library.playlists.collectAsState(emptyList())
    Box {
        WsIconButton(Icons.Rounded.MoreHoriz, "More", { open = true; inPlaylists = false }, size = 34.dp, iconSize = 20.dp, tint = Ws.faint)
        DropdownMenu(open, { open = false }, containerColor = Ws.surfaceTop, shape = RoundedCornerShape(14.dp)) {
            if (!inPlaylists) {
                MenuItem("Play next", Icons.Rounded.PlayArrow) { c.playNext(song); open = false }
                MenuItem("Add to queue", Icons.Rounded.QueueMusic) { c.addToQueue(song); open = false }
                MenuItem("Add to playlist…", Icons.Rounded.PlaylistAdd) { inPlaylists = true }
            } else {
                MenuItem("New playlist…", Icons.Rounded.Add) { c.newPlaylistFor = song; c.newPlaylistOpen = true; open = false }
                playlists.forEach { p -> MenuItem("${p.playlist.name}  ·  ${p.songCount}", Icons.Rounded.MusicNote) { c.addToPlaylist(p.playlist.id, song); open = false } }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = Ws.text) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp), tint = Ws.muted) },
        onClick = onClick,
    )
}

@Composable
fun SongList(c: Controller, songs: List<SongItem>, modifier: Modifier = Modifier, extra: @Composable (Int) -> Unit = {}) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp)) {
        itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { i, song -> SongRow(c, song, i, songs, extra = { extra(i) }) }
    }
}

// =====================================================================================
//  Search
// =====================================================================================

@Composable
private fun SearchScreen(c: Controller) {
    val recent by c.library.searchHistory().collectAsState(emptyList())
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SearchField(c.query, { c.query = it }, { c.search() }, Modifier.focusRequester(focus), onFocusChange = { c.typing = it })
        when {
            c.searching && c.results.isEmpty() -> EmptyState(Icons.Rounded.GraphicEq, "Searching…", "")
            c.searchError != null -> EmptyState(Icons.Rounded.Search, "Search failed", c.searchError.orEmpty())
            c.results.isEmpty() -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (recent.isNotEmpty()) {
                        Eyebrow("Recent")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { itemsIndexed(recent, key = { _, h -> h.id }) { _, h -> WsChip(h.query, { c.search(h.query) }) } }
                    }
                    Eyebrow("Jump in")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(listOf("Gaming music", "Phonk", "Synthwave", "Lofi beats", "EDM", "Hip hop", "Rock classics", "Bollywood")) { _, s -> WsChip(s, { c.search(s) }) }
                    }
                }
                Wordmark()
                EmptyState(Icons.Rounded.MusicNote, "WHAT'S THE VIBE?", "Search any song or artist, or pick a vibe above.")
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("${c.results.size} results", Modifier.weight(1f))
                    GhostButton("Play all", { c.playFromList(c.results, 0) }, icon = Icons.Rounded.PlayArrow)
                }
                SongList(c, c.results, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier, onFocusChange: (Boolean) -> Unit = {}) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(18.dp)
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(58.dp).clip(shape).background(Ws.surface)
            .border(1.5.dp, if (focused) Brush.horizontalGradient(listOf(accent.primary, accent.secondary)) else SolidColor(Ws.border), shape)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(24.dp), tint = if (focused) accent.highlight else Ws.muted)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search songs, artists…", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), color = Ws.faint)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, color = Ws.text),
                cursorBrush = SolidColor(accent.highlight),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) }
                    .onKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { onSearch(); true } else false },
            )
        }
        if (value.isNotEmpty()) WsIconButton(Icons.Rounded.Close, "Clear", { onChange("") }, size = 32.dp, iconSize = 18.dp)
        GradientButton("Search", onSearch, icon = null)
    }
}

/** Big gradient "W'S MUSIC" brand text shown on the home screen. */
@Composable
private fun Wordmark() {
    val accent = LocalAccent.current
    Text(
        "W'S MUSIC",
        Modifier.fillMaxWidth().padding(top = 26.dp),
        style = MaterialTheme.typography.displayLarge.copy(brush = Brush.horizontalGradient(listOf(accent.primary, accent.secondary)), letterSpacing = 6.sp, fontSize = 52.sp),
        textAlign = TextAlign.Center,
    )
}

@Composable
internal fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(96.dp).glow(accent.primary, 90.dp, 0.35f), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(56.dp), tint = accent.highlight)
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Ws.text, textAlign = TextAlign.Center)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ws.muted, textAlign = TextAlign.Center)
    }
}

// =====================================================================================
//  Library
// =====================================================================================

@Composable
private fun LibraryScreen(c: Controller, onPickAndroidFile: () -> Unit) {
    val liked by c.library.likedSongs.collectAsState(emptyList())
    val playlists by c.library.playlists.collectAsState(emptyList())
    Column(Modifier.fillMaxSize().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("YOUR LIBRARY", style = MaterialTheme.typography.headlineLarge, color = Ws.text)
                Text("${liked.size} liked  ·  ${playlists.size} playlists", style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
            }
            GhostButton("Import from Android", onPickAndroidFile, icon = Icons.Rounded.FileUpload)
        }
        SegmentedTabs(listOf("Liked", "Songs", "Playlists", "History"), c.libraryTab, { c.libraryTab = it; c.openPlaylistId = null }, Modifier.width(460.dp))
        Box(Modifier.weight(1f)) { val tab = c.libraryTab
            when (tab) {
                0 -> LikedTab(c)
                1 -> SongsTab(c)
                2 -> PlaylistsTab(c)
                else -> HistoryTab(c)
            }
        }
    }
}

@Composable
private fun PlayHeader(c: Controller, songs: List<SongItem>, empty: String, extra: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GradientButton("Play all", { c.playFromList(songs, 0) }, icon = Icons.Rounded.PlayArrow, enabled = songs.isNotEmpty())
        GhostButton("Shuffle", { c.playShuffled(songs) }, icon = Icons.Rounded.Shuffle, enabled = songs.isNotEmpty())
        Eyebrow("${songs.size} songs", Modifier.weight(1f).padding(start = 6.dp))
        extra()
    }
    if (songs.isEmpty()) EmptyState(Icons.Rounded.FavoriteBorder, "NOTHING HERE YET", empty)
}

@Composable
private fun LikedTab(c: Controller) {
    val songs by c.library.likedSongs.collectAsState(emptyList())
    val items = remember(songs) { songs.map { it.toSongItem() } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayHeader(c, items, "Tap the heart on any song, or import your likes from the Android app.")
        SongList(c, items, Modifier.weight(1f))
    }
}

@Composable
private fun SongsTab(c: Controller) {
    val songs by c.library.librarySongs.collectAsState(emptyList())
    val items = remember(songs) { songs.map { it.toSongItem() } }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayHeader(c, items, "Liked songs land here automatically.")
        SongList(c, items, Modifier.weight(1f)) { i ->
            WsIconButton(Icons.Rounded.Close, "Remove from library", { scope.launch { c.library.removeFromLibrary(items[i].id) } }, size = 30.dp, iconSize = 16.dp, tint = Ws.faint)
        }
    }
}

@Composable
private fun HistoryTab(c: Controller) {
    val events by c.library.history().collectAsState(emptyList())
    val items = remember(events) { events.map { it.song.toSongItem() } }
    val fmt = remember { DateTimeFormatter.ofPattern("MMM d · HH:mm") }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("${events.size} plays", Modifier.weight(1f))
            GhostButton("Clear history", { scope.launch { c.library.clearHistory() } }, icon = Icons.Rounded.Delete, enabled = events.isNotEmpty(), danger = true)
        }
        if (events.isEmpty()) EmptyState(Icons.Rounded.History, "NO HISTORY", "Songs you listen to for 10+ seconds show up here.")
        SongList(c, items, Modifier.weight(1f)) { i -> Text(events[i].event.timestamp.format(fmt), style = MaterialTheme.typography.labelSmall, color = Ws.faint) }
    }
}

@Composable
private fun PlaylistsTab(c: Controller) {
    val playlists by c.library.playlists.collectAsState(emptyList())
    val open = c.openPlaylistId
    if (open == null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("${playlists.size} playlists", Modifier.weight(1f))
                GradientButton("New playlist", { c.newPlaylistFor = null; c.newPlaylistOpen = true }, icon = Icons.Rounded.Add)
            }
            if (playlists.isEmpty()) EmptyState(Icons.Rounded.PlaylistAdd, "NO PLAYLISTS", "Create one, or import yours from the Android app.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(playlists, key = { _, p -> p.playlist.id }) { _, p -> PlaylistCard(p) { c.openPlaylistId = p.playlist.id } }
            }
        }
    } else PlaylistDetail(c, open)
}

@Composable
private fun PlaylistCard(p: Playlist, onClick: () -> Unit) {
    HoverRow(onClick) { _ ->
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val thumb = p.thumbnails.firstOrNull()
            Thumb(thumb, 56.dp, corner = 12.dp, seed = p.playlist.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(p.playlist.name, style = MaterialTheme.typography.titleMedium, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.songCount} songs" + if (p.playlist.browseId != null) "  ·  from YouTube" else "", style = MaterialTheme.typography.bodySmall, color = Ws.muted)
            }
            Icon(Icons.Rounded.PlayArrow, null, tint = Ws.faint)
        }
    }
}

@Composable
private fun PlaylistDetail(c: Controller, id: String) {
    val playlist by c.library.playlist(id).collectAsState(null)
    val rows by c.library.playlistSongs(id).collectAsState(emptyList())
    val items = remember(rows) { rows.map { it.song.toSongItem() } }
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WsIconButton(Icons.Rounded.ArrowBack, "Back", { c.openPlaylistId = null })
            Text(playlist?.playlist?.name.orEmpty(), style = MaterialTheme.typography.headlineMedium, color = Ws.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            WsIconButton(Icons.Rounded.Edit, "Rename", { renaming = true })
            WsIconButton(Icons.Rounded.Delete, "Delete", { confirmDelete = true }, tint = Ws.danger.copy(alpha = 0.8f), hoverTint = Ws.danger)
        }
        PlayHeader(c, items, "Use ⋯ › Add to playlist on any song.")
        SongList(c, items, Modifier.weight(1f)) { i ->
            WsIconButton(Icons.Rounded.Close, "Remove", { scope.launch { c.library.removeFromPlaylist(rows[i].map) } }, size = 30.dp, iconSize = 16.dp, tint = Ws.faint)
        }
    }
    if (renaming) NameDialog("Rename playlist", playlist?.playlist?.name.orEmpty(), "Rename", { n -> scope.launch { c.library.renamePlaylist(id, n) }; renaming = false }, { renaming = false })
    if (confirmDelete) ConfirmDialog(
        "Delete “${playlist?.playlist?.name}”?", "The songs stay in your library; only the playlist is removed.", "Delete",
        { scope.launch { c.library.deletePlaylist(id) }; confirmDelete = false; c.openPlaylistId = null }, { confirmDelete = false },
    )
}

// =====================================================================================
//  Side panel: queue + lyrics
// =====================================================================================

@Composable
private fun SidePanel(c: Controller) {
    Column(Modifier.padding(start = 0.dp).width(390.dp).fillMaxHeight().panel(22.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SegmentedTabs(listOf("Queue", "Lyrics"), c.panelTab.ordinal, { c.panelTab = PanelTab.entries[it] })
        Box(Modifier.weight(1f)) { val tab = c.panelTab
            if (tab == PanelTab.QUEUE) QueuePanel(c) else LyricsPanel(c)
        }
    }
}

@Composable
private fun QueuePanel(c: Controller) {
    val q = c.queue
    val listState = rememberLazyListState()
    LaunchedEffect(q.index) { if (q.index >= 0 && q.items.isNotEmpty()) listState.scrollToItem((q.index - 1).coerceAtLeast(0)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("${q.items.size} in queue", Modifier.weight(1f))
            WsIconButton(Icons.Rounded.Shuffle, "Shuffle upcoming", { c.shuffleQueue() }, size = 34.dp, iconSize = 19.dp, enabled = q.items.size > 1)
            WsIconButton(Icons.Rounded.Delete, "Clear queue", { c.clearQueue() }, size = 34.dp, iconSize = 19.dp, enabled = q.items.isNotEmpty())
        }
        if (q.items.isEmpty()) {
            EmptyState(Icons.Rounded.QueueMusic, "QUEUE IS EMPTY", "Play something and the rest of the list lands here.")
        } else {
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(q.items, key = { i, s -> "$i-${s.id}" }) { i, song ->
                    HoverRow({ c.jumpTo(i) }, selected = i == q.index) { hovered ->
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                Thumb(song.thumbnail, 40.dp, corner = 9.dp, seed = song.title)
                                if (i == q.index) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) { EqBars(c.playback.playing, Modifier.size(16.dp)) }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(song.title, style = MaterialTheme.typography.titleSmall, color = if (i == q.index) LocalAccent.current.highlight else Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artists.joinToString { it.name }, style = MaterialTheme.typography.bodySmall, color = Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (hovered) WsIconButton(Icons.Rounded.Close, "Remove", { c.removeFromQueue(i) }, size = 28.dp, iconSize = 16.dp, tint = Ws.faint)
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================================
//  Lyrics
// =====================================================================================

@Composable
private fun LyricsPanel(c: Controller) {
    when (val s = c.lyrics) {
        LyricsUiState.Idle -> EmptyState(Icons.Rounded.Lyrics, "NO SONG YET", "Play a song to see its lyrics.")
        LyricsUiState.Loading -> EmptyState(Icons.Rounded.GraphicEq, "FINDING LYRICS…", "")
        LyricsUiState.NotFound -> EmptyState(Icons.Rounded.Lyrics, "NO LYRICS", "No provider has lyrics for this song.")
        is LyricsUiState.Loaded -> LoadedLyrics(c, s)
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
    LaunchedEffect(currentIndex) { if (currentIndex >= 0) listState.scrollToItem((currentIndex - 2).coerceAtLeast(0)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("via ${state.provider}" + if (state.synced) "" else " · unsynced", Modifier.weight(1f))
            if (canRomanize) WsChip(if (romanize) "Romanized ✓" else "Romanize", { romanize = !romanize })
        }
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 40.dp)) {
            itemsIndexed(state.lines) { i, line ->
                LyricLine(c, line, romanized[i].takeIf { romanize }, active = i == currentIndex, past = state.synced && currentIndex >= 0 && i < currentIndex, seekable = state.synced)
            }
        }
    }
}

@Composable
private fun LyricLine(c: Controller, line: LyricsEntry, romanized: String?, active: Boolean, past: Boolean, seekable: Boolean) {
    val accent = LocalAccent.current
    val target = when { active -> 1f; past -> 0.42f; else -> 0.62f }
    val alpha = target
    val size = if (active) 21f else 16f
    HoverRow({ if (seekable) c.seek(line.time) }, Modifier.padding(horizontal = 2.dp)) { _ ->
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            val words = line.words
            val text = if (active && !words.isNullOrEmpty()) {
                // Word-by-word highlight; reads the position directly so only this line redraws.
                val now = c.positionMs / 1000.0
                buildAnnotatedString {
                    words.forEachIndexed { idx, w ->
                        withStyle(SpanStyle(color = if (now >= w.startTime) accent.highlight else Ws.text.copy(alpha = 0.55f))) { append(w.text) }
                        if (idx < words.lastIndex) append(" ")
                    }
                }
            } else buildAnnotatedString { withStyle(SpanStyle(color = if (active) accent.highlight else Ws.text.copy(alpha = alpha))) { append(line.text) } }
            Text(text, fontSize = size.sp, lineHeight = (size * 1.3f).sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
            if (romanized != null) Text(romanized, style = MaterialTheme.typography.bodySmall, color = if (active) accent.primary else Ws.faint)
        }
    }
}

// =====================================================================================
//  Player bar
// =====================================================================================

@Composable
private fun PlayerBar(c: Controller) {
    val accent = LocalAccent.current
    val song = c.queue.current
    val has = song != null
    Row(
        Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 12.dp, vertical = 10.dp).panel(24.dp, Ws.surface.copy(alpha = 0.96f)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- now playing ----
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(66.dp), contentAlignment = Alignment.Center) {
                if (has) Box(Modifier.size(66.dp).glow(accent.primary, 72.dp, if (c.playback.playing) 0.55f else 0.25f))
                Thumb(song?.thumbnail, 62.dp, corner = 14.dp, seed = song?.title ?: "W")
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(song?.title ?: "Nothing playing", style = MaterialTheme.typography.titleMedium, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val err = c.playback.error
                Text(
                    err?.let { "⚠ $it" } ?: song?.artists?.joinToString { it.name } ?: "Pick a song to start",
                    style = MaterialTheme.typography.bodySmall, color = if (err != null) Ws.danger else Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (song != null) {
                val liked = song.id in c.likedIds
                WsIconButton(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Like", { c.toggleLike(song) }, tint = if (liked) accent.highlight else Ws.muted, hoverTint = accent.highlight)
            }
        }

        // ---- transport ----
        Column(Modifier.weight(1.6f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WsIconButton(Icons.Rounded.Shuffle, "Shuffle queue", { c.shuffleQueue() }, enabled = c.queue.items.size > 1)
                WsIconButton(Icons.Rounded.SkipPrevious, "Previous", { c.skipPrevious() }, enabled = has, iconSize = 28.dp, size = 44.dp, tint = Ws.text)
                PlayButton(c.playback.playing, c.playback.loading, has && c.playback.durationMs > 0 || c.playback.loading, { c.togglePause() })
                WsIconButton(Icons.Rounded.SkipNext, "Next", { c.skipNext() }, enabled = has, iconSize = 28.dp, size = 44.dp, tint = Ws.text)
                WsIconButton(
                    if (c.queue.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", { c.cycleRepeat() },
                    tint = if (c.queue.repeat == RepeatMode.OFF) Ws.muted else accent.highlight,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Only these two small widgets read the fast-changing position.
                ElapsedLabel(c)
                NeonSlider(
                    value = { if (c.playback.durationMs > 0) c.positionMs.toFloat() / c.playback.durationMs else 0f },
                    onChange = {}, onChangeFinished = { c.seek((it * c.playback.durationMs).toLong()) },
                    enabled = c.playback.durationMs > 0, modifier = Modifier.weight(1f),
                )
                Text(fmtTime(c.playback.durationMs), style = MaterialTheme.typography.labelSmall, color = Ws.muted, modifier = Modifier.width(38.dp))
            }
        }

        // ---- volume & options ----
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            WsIconButton(Icons.Rounded.AutoAwesome, "Autoplay similar songs", { c.autoplay = !c.autoplay }, tint = if (c.autoplay) accent.highlight else Ws.faint)
            WsIconButton(if (c.volume == 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, "Mute", { c.changeVolume(if (c.volume == 0f) 0.8f else 0f) })
            NeonSlider({ c.volume }, { c.changeVolume(it) }, Modifier.width(110.dp))
        }
    }
}

/** Own composable so only this label (not the whole player bar) recomposes as time advances. */
@Composable
private fun ElapsedLabel(c: Controller) {
    Text(fmtTime(c.positionMs), style = MaterialTheme.typography.labelSmall, color = Ws.muted, modifier = Modifier.width(38.dp), textAlign = TextAlign.End)
}

// =====================================================================================
//  Dialogs
// =====================================================================================

@Composable
fun NameDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(22.dp),
        title = { Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = Ws.text) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }, shape = RoundedCornerShape(14.dp)) },
        confirmButton = { GradientButton(confirmLabel, { onConfirm(text) }, enabled = text.isNotBlank()) },
        dismissButton = { GhostButton("Cancel", onDismiss) },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(22.dp),
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = Ws.text) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, color = Ws.muted) },
        confirmButton = { GhostButton(confirmLabel, onConfirm, danger = true) },
        dismissButton = { GhostButton("Cancel", onDismiss) },
    )
}

@Composable
private fun ImportDialog(c: Controller) {
    when (val s = c.importUi) {
        ImportUi.Idle -> Unit
        is ImportUi.Running -> AlertDialog(
            onDismissRequest = {}, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(22.dp),
            title = { Text("IMPORTING…", style = MaterialTheme.typography.titleLarge, color = Ws.text) },
            text = { Text("Reading ${s.fileName}. This takes a few seconds.", style = MaterialTheme.typography.bodyMedium, color = Ws.muted) },
            confirmButton = {},
        )
        is ImportUi.Done -> AlertDialog(
            onDismissRequest = { c.importUi = ImportUi.Idle }, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(22.dp),
            title = { Text("IMPORT COMPLETE", style = MaterialTheme.typography.titleLarge, color = Ws.text) },
            text = { Text(s.result.summary(), style = MaterialTheme.typography.bodyMedium, color = Ws.muted) },
            confirmButton = { GradientButton("Done", { c.importUi = ImportUi.Idle }) },
        )
        is ImportUi.Failed -> AlertDialog(
            onDismissRequest = { c.importUi = ImportUi.Idle }, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(22.dp),
            title = { Text("IMPORT FAILED", style = MaterialTheme.typography.titleLarge, color = Ws.danger) },
            text = { Text(s.message, style = MaterialTheme.typography.bodyMedium, color = Ws.muted) },
            confirmButton = { GhostButton("Close", { c.importUi = ImportUi.Idle }) },
        )
    }
}
