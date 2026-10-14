package echopc

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.pages.MoodAndGenres
import echo.music.iad1tya.db.entities.Playlist
import echopc.db.toSongItem
import java.time.LocalTime
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(c: Controller) {
    run { val d = c.detail
        if (d == null) HomeFeed(c) else DetailScreen(c, d)
    }
}

// =====================================================================================
//  Cards & shelves
// =====================================================================================

/** Spotify-style card: square cover, hover lift, round play button that slides in. */
@Composable
fun MediaCard(
    thumb: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = onClick,
    round: Boolean = false,
    size: Dp = 172.dp,
) {
    val accent = LocalAccent.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val cover = size - 20.dp
    Column(
        modifier.width(size)
            .clip(RoundedCornerShape(18.dp))
            .background(if (hovered) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .hoverable(source).clickable(source, null, onClick = onClick).handCursor()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(cover)) {
            Thumb(thumb, cover, corner = if (round) cover / 2 else 14.dp, seed = title)
            if (onPlay != null && hovered) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .size(44.dp)
                        .clip(CircleShape).background(Brush.linearGradient(listOf(accent.primary, accent.secondary)))
                        .clickable(onClick = onPlay).handCursor(),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, "Play", Modifier.size(26.dp), tint = Color.White) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ws.muted, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ItemCard(c: Controller, item: YTItem, siblings: List<YTItem>) {
    val sub = when (item) {
        is SongItem -> item.artists.joinToString { it.name }
        is AlbumItem -> listOfNotNull(item.artists?.joinToString { it.name }, item.year?.toString()).joinToString(" · ").ifBlank { "Album" }
        is PlaylistItem -> item.author?.name ?: item.songCountText ?: "Playlist"
        is ArtistItem -> "Artist"
        else -> ""
    }
    MediaCard(item.thumbnail, item.title, sub, { c.openItem(item, siblings) }, onPlay = { c.playItem(item, siblings) }, round = item is ArtistItem)
}

/** A titled horizontal row of cards with left/right scroll buttons (a mouse wheel can't scroll sideways). */
@Composable
fun Shelf(title: String, subtitle: String? = null, content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ws.muted, maxLines = 1)
            }
            WsIconButton(Icons.Rounded.ChevronLeft, "Scroll left", { scope.launch { state.animateScrollBy(-640f) } }, size = 34.dp)
            WsIconButton(Icons.Rounded.ChevronRight, "Scroll right", { scope.launch { state.animateScrollBy(640f) } }, size = 34.dp)
        }
        LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

// =====================================================================================
//  Home feed
// =====================================================================================

private class QuickTile(val key: String, val title: String, val thumb: String?, val icon: ImageVector?, val onClick: () -> Unit)

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Late night session"
}

@Composable
private fun HomeFeed(c: Controller) {
    val liked by c.library.likedSongs.collectAsState(emptyList())
    val history by c.library.history(80).collectAsState(emptyList())
    val playlists by c.library.playlists.collectAsState(emptyList())
    val recents = remember(history) { history.map { it.song.toSongItem() }.distinctBy { it.id }.take(14) }
    val likedItems = remember(liked) { liked.map { it.toSongItem() } }
    // One mix per artist, seeded from what was played or liked most recently.
    val seeds = remember(recents, likedItems) { (recents.take(4) + likedItems.take(4)).distinctBy { it.artists.firstOrNull()?.name ?: it.id }.take(6) }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(26.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)) {
        item("header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(greeting().uppercase(), style = MaterialTheme.typography.headlineLarge, color = Ws.text)
                    Text("Pick up where you left off, or find something new.", style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
                }
                WsIconButton(Icons.Rounded.Refresh, "Refresh", { c.loadHome(c.homeChip) })
            }
        }
        if (c.homeChips.isNotEmpty()) item("chips") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(c.homeChips, key = { i, ch -> "$i-${ch.title}" }) { _, chip -> WsChip(chip.title, { c.selectChip(chip) }, selected = c.homeChip?.title == chip.title) }
            }
        }
        item("quick") { QuickTiles(c, liked.size, playlists, recents, c.homeSections.firstOrNull()?.items) }
        if (seeds.isNotEmpty()) item("mixes") {
            Shelf("Made for you", "Mixes based on what you play and like") {
                itemsIndexed(seeds, key = { _, s -> "mix-${s.id}" }) { _, seed ->
                    val artist = seed.artists.firstOrNull()?.name ?: seed.title
                    MediaCard(seed.thumbnail, "$artist Mix", "Based on ${seed.title}", { c.playMix(seed) })
                }
            }
        }
        if (recents.isNotEmpty()) item("recent") {
            Shelf("Recently played") {
                itemsIndexed(recents, key = { _, s -> "recent-${s.id}" }) { i, song ->
                    MediaCard(song.thumbnail, song.title, song.artists.joinToString { it.name }, { c.playFromList(recents, i) })
                }
            }
        }
        when {
            c.homeLoading && c.homeSections.isEmpty() -> item("loading") { Column(verticalArrangement = Arrangement.spacedBy(26.dp)) { SkeletonShelf(); SkeletonShelf() } }
            c.homeError != null && c.homeSections.isEmpty() -> item("error") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EmptyState(Icons.Rounded.GraphicEq, "CAN'T REACH YOUTUBE MUSIC", c.homeError.orEmpty())
                    GradientButton("Try again", { c.loadHome(c.homeChip) }, icon = Icons.Rounded.Refresh)
                }
            }
            else -> itemsIndexed(c.homeSections, key = { i, s -> "sec-$i-${s.title}" }) { i, section ->
                Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                    Shelf(section.title, section.label) {
                        itemsIndexed(section.items) { _, item -> ItemCard(c, item, section.items) }
                    }
                    if (i == 0 && c.newReleases.isNotEmpty()) Shelf("New releases", "Fresh albums and singles") {
                        itemsIndexed(c.newReleases, key = { _, a -> "new-${a.id}" }) { _, album -> ItemCard(c, album, c.newReleases) }
                    }
                }
            }
        }
        if (c.moods.isNotEmpty()) item("moods") { MoodGrid(c) }
        if (c.homeMore != null) item("more") {
            LaunchedEffect(c.homeMore) { c.loadMoreHome() } // only composed near the bottom: infinite scroll
            Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) { Text("Loading more…", style = MaterialTheme.typography.bodySmall, color = Ws.faint) }
        }
    }
}

@Composable
private fun QuickTiles(c: Controller, likedCount: Int, playlists: List<Playlist>, recents: List<SongItem>, fallback: List<YTItem>?) {
    val tiles = buildList {
        add(QuickTile("liked", "Liked Songs", null, Icons.Rounded.Favorite) { c.libraryTab = 0; c.openPlaylistId = null; c.section = Section.LIBRARY })
        playlists.take(3).forEach { p ->
            add(QuickTile("pl-${p.playlist.id}", p.playlist.name, p.thumbnails.firstOrNull(), null) { c.libraryTab = 2; c.openPlaylistId = p.playlist.id; c.section = Section.LIBRARY })
        }
        recents.take(8 - size).forEachIndexed { i, s -> add(QuickTile("song-${s.id}", s.title, s.thumbnail, null) { c.playFromList(recents, i) }) }
        // Brand-new profile: fill the grid with the feed's first suggestions so the page is never bare.
        fallback?.take(8 - size)?.forEach { item -> add(QuickTile("feed-${item.id}", item.title, item.thumbnail, null) { c.openItem(item, fallback) }) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = when { maxWidth >= 900.dp -> 4; maxWidth >= 620.dp -> 3; else -> 2 }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { QuickTileView(it, Modifier.weight(1f)) }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun QuickTileView(tile: QuickTile, modifier: Modifier) {
    val accent = LocalAccent.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val reveal = if (hovered) 1f else 0f
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.height(64.dp).clip(shape).background(if (hovered) Ws.surfaceTop else Ws.surfaceHi)
            .hoverable(source).clickable(source, null, onClick = tile.onClick).handCursor(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (tile.icon != null) Box(
            Modifier.size(64.dp).background(Brush.linearGradient(listOf(accent.primary, accent.secondary))),
            contentAlignment = Alignment.Center,
        ) { Icon(tile.icon, null, Modifier.size(28.dp), tint = Color.White) }
        else Thumb(tile.thumb, 64.dp, corner = 0.dp, seed = tile.title)
        Text(tile.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Ws.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.padding(end = 10.dp).graphicsLayer { alpha = reveal }.size(34.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(accent.primary, accent.secondary))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp), tint = Color.White) }
    }
}

@Composable
private fun MoodGrid(c: Controller) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Browse by mood & genre", style = MaterialTheme.typography.titleLarge, color = Ws.text)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = when { maxWidth >= 900.dp -> 4; maxWidth >= 620.dp -> 3; else -> 2 }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                c.moods.take(36).chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { MoodTile(it, { c.openMood(it) }, Modifier.weight(1f)) }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodTile(item: MoodAndGenres.Item, onClick: () -> Unit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val stripe = remember(item) { Color(item.stripeColor.toInt() or (0xFF shl 24)) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.height(54.dp).clip(shape).background(if (hovered) Ws.surfaceTop else Ws.surfaceHi)
            .hoverable(source).clickable(source, null, onClick = onClick).handCursor(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).fillMaxSize().background(stripe))
        Text(item.title, Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.titleSmall, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SkeletonShelf() {
    val a = remember { androidx.compose.runtime.mutableStateOf(0.4f) } // static placeholder (no pulsing animation)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(220.dp).height(22.dp).graphicsLayer { alpha = a.value }.clip(RoundedCornerShape(8.dp)).background(Ws.surfaceTop))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(6) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(152.dp).graphicsLayer { alpha = a.value }.clip(RoundedCornerShape(14.dp)).background(Ws.surfaceTop))
                    Box(Modifier.width(110.dp).height(12.dp).graphicsLayer { alpha = a.value }.clip(RoundedCornerShape(6.dp)).background(Ws.surfaceTop))
                }
            }
        }
    }
}

// =====================================================================================
//  Detail page (playlist / album / mood category)
// =====================================================================================

@Composable
private fun DetailScreen(c: Controller, d: DetailUi) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxSize().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WsIconButton(Icons.Rounded.ArrowBack, "Back", { c.closeDetail() })
            Eyebrow("Back to home")
        }
        when (d) {
            is DetailUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Opening ${d.title}…", style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
                }
            }
            is DetailUi.Failed -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(Icons.Rounded.GraphicEq, "COULDN'T OPEN IT", d.message)
            }
            is DetailUi.Songs -> {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(200.dp).glow(accent.primary, 190.dp, 0.4f))
                        Thumb(d.cover, 190.dp, corner = 18.dp, seed = d.title)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow(d.kind)
                        Text(d.title, style = MaterialTheme.typography.headlineLarge, color = Ws.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(d.subtitle, style = MaterialTheme.typography.bodyMedium, color = Ws.muted, maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                            GradientButton("Play", { c.playFromList(d.songs, 0) }, icon = Icons.Rounded.PlayArrow, enabled = d.songs.isNotEmpty())
                            GhostButton("Shuffle", { c.playShuffled(d.songs) }, icon = Icons.Rounded.Shuffle, enabled = d.songs.isNotEmpty())
                            GhostButton("Save to my playlists", { c.saveAsPlaylist(d.title, d.songs) }, icon = Icons.Rounded.PlaylistAdd, enabled = d.songs.isNotEmpty())
                        }
                    }
                }
                SongList(c, d.songs, Modifier.weight(1f))
            }
            is DetailUi.Collection -> {
                Text(d.title.uppercase(), style = MaterialTheme.typography.headlineLarge, color = Ws.text)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(d.sections, key = { i, s -> "col-$i-${s.title}" }) { _, section ->
                        Shelf(section.title ?: "Featured") { itemsIndexed(section.items) { _, item -> ItemCard(c, item, section.items) } }
                    }
                }
            }
        }
    }
}
