package echopc

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import echo.music.iad1tya.db.entities.Playlist
import echopc.db.toSongItem
import java.time.LocalTime
import kotlinx.coroutines.launch

// =====================================================================================
//  Cards & shelves
// =====================================================================================

/** Cover card: square art (round for artists), title and two lines of detail; play button on hover. */
@Composable
fun MediaCard(
    thumb: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = onClick,
    round: Boolean = false,
    size: Dp = 184.dp,
    coverLabel: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val cover = size - 24.dp
    Column(
        modifier.width(size)
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) Ws.surfaceHi else Color.Transparent)
            .hoverable(source).clickable(source, null, onClick = onClick).handCursor()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(cover)) {
            Thumb(thumb, cover, corner = if (round) cover / 2 else 6.dp, seed = title)
            if (coverLabel != null) MixCoverLabel(coverLabel, cover)
            if (onPlay != null && hovered) PlayButton(false, false, true, onPlay, Modifier.align(Alignment.BottomEnd).padding(8.dp), size = 48.dp, onAccent = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ws.muted, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Generated-mix covers: the lead song's art with the mix name set large across a dark fade. */
@Composable
private fun MixCoverLabel(label: String, cover: Dp) {
    Box(
        Modifier.size(cover).clip(RoundedCornerShape(6.dp))
            .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(label, Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ItemCard(c: Controller, item: YTItem, siblings: List<YTItem>, onAction: () -> Unit = {}) {
    val sub = when (item) {
        is SongItem -> item.artists.joinToString { it.name }
        is AlbumItem -> listOfNotNull(item.year?.toString(), item.artists?.joinToString { it.name }).joinToString(" • ").ifBlank { "Album" }
        is PlaylistItem -> item.author?.name?.let { "Playlist • $it" } ?: item.songCountText ?: "Playlist"
        is ArtistItem -> "Artist"
        else -> ""
    }
    MediaCard(item.thumbnail, item.title, sub, { onAction(); c.openItem(item, siblings) }, onPlay = { onAction(); c.playItem(item, siblings) }, round = item is ArtistItem)
}

/** A titled horizontal row of cards with left/right scroll buttons (a mouse wheel can't scroll sideways). */
@Composable
fun Shelf(title: String, subtitle: String? = null, content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.padding(start = 24.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ws.muted, maxLines = 1)
            }
            WsIconButton(Icons.Rounded.ChevronLeft, "Scroll $title left", { scope.launch { state.animateScrollBy(-640f) } }, size = 32.dp, iconSize = 22.dp, background = Ws.surfaceHi)
            Spacer(Modifier.width(8.dp))
            WsIconButton(Icons.Rounded.ChevronRight, "Scroll $title right", { scope.launch { state.animateScrollBy(640f) } }, size = 32.dp, iconSize = 22.dp, background = Ws.surfaceHi)
        }
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = 12.dp), content = content)
    }
}

// =====================================================================================
//  Home feed
// =====================================================================================

private class QuickTile(val key: String, val title: String, val thumb: String?, val icon: ImageVector?, val onOpen: () -> Unit, val onPlay: (() -> Unit)?)

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Late night listening"
}

@Composable
fun HomeScreen(c: Controller) {
    val accent = LocalAccent.current
    val liked by c.library.likedSongs.collectAsState(emptyList())
    val history by c.library.history(80).collectAsState(emptyList())
    val playlists by c.library.playlists.collectAsState(emptyList())
    val recents = remember(history) { history.map { it.song.toSongItem() }.distinctBy { it.id }.take(14) }
    val likedItems = remember(liked) { liked.map { it.toSongItem() } }
    // One mix per artist, seeded from what was played or liked most recently.
    val seeds = remember(recents, likedItems) { (recents.take(4) + likedItems.take(4)).distinctBy { it.artists.firstOrNull()?.name ?: it.id }.take(6) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to accent.flood.copy(alpha = 0.7f), 0.35f to Ws.surface))) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(32.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)) {
            item("chips") {
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WsChip("All", { if (c.homeChip != null) c.loadHome(null) }, selected = c.homeChip == null)
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(c.homeChips, key = { i, ch -> "$i-${ch.title}" }) { _, chip -> WsChip(chip.title, { c.selectChip(chip) }, selected = c.homeChip?.title == chip.title) }
                    }
                    WsIconButton(Icons.Rounded.Refresh, "Refresh Home", { c.loadHome(c.homeChip) }, size = 32.dp, iconSize = 18.dp)
                }
            }
            item("quick") {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(greeting(), style = MaterialTheme.typography.headlineLarge, color = Ws.text)
                    QuickTiles(c, playlists, recents, c.homeSections.firstOrNull()?.items)
                }
            }
            when {
                c.homeLoading && c.homeSections.isEmpty() && c.mixes.isEmpty() && recents.isEmpty() ->
                    item("loading") { Column(verticalArrangement = Arrangement.spacedBy(32.dp)) { SkeletonShelf(); SkeletonShelf() } }
                c.homeError != null && c.homeSections.isEmpty() -> item("error") {
                    EmptyState("Couldn't reach YouTube Music", "${c.homeError}. Check your internet connection, then try again.") {
                        PrimaryButton("Try again", { c.loadHome(c.homeChip) }, icon = Icons.Rounded.Refresh)
                    }
                }
            }
            // Shelves in the order the bandit picked for this visit (see ShelfBandit).
            val shelves = LinkedHashMap<String, @Composable () -> Unit>()
            if (c.mixes.isNotEmpty() || seeds.isNotEmpty()) shelves["made-for-you"] = { MadeForYouShelf(c, seeds) }
            if (recents.isNotEmpty()) shelves["recently-played"] = {
                Shelf("Recently played") {
                    itemsIndexed(recents, key = { _, s -> "recent-${s.id}" }) { i, song ->
                        MediaCard(song.thumbnail, song.title, song.artists.joinToString { it.name }, { c.noteShelfClick("recently-played"); c.playFromList(recents, i) })
                    }
                }
            }
            c.homeSections.forEachIndexed { i, section ->
                val key = "yt:${section.title}"
                if (key !in shelves) shelves[key] = {
                    Shelf(section.title, section.label?.lowercase()?.replaceFirstChar { it.uppercase() }) {
                        itemsIndexed(section.items) { _, item -> ItemCard(c, item, section.items) { c.noteShelfClick(key) } }
                    }
                }
                if (i == 0 && c.newReleases.isNotEmpty()) shelves["new-releases"] = {
                    Shelf("New releases", "Albums and singles that just came out") {
                        itemsIndexed(c.newReleases, key = { _, a -> "new-${a.id}" }) { _, album -> ItemCard(c, album, c.newReleases) { c.noteShelfClick("new-releases") } }
                    }
                }
            }
            for (key in c.orderShelves(shelves.keys.toList())) item("shelf-$key") {
                LaunchedEffect(key) { c.noteShelfImpressions(listOf(key)) } // composed = on (or near) screen
                shelves.getValue(key)()
            }
            if (c.moods.isNotEmpty()) item("moods") {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Browse all", style = MaterialTheme.typography.headlineMedium, color = Ws.text)
                    BrowseGrid(c, c.moods.take(20))
                }
            }
            if (c.homeMore != null) item("more") {
                LaunchedEffect(c.homeMore) { c.loadMoreHome() } // only composed near the bottom: infinite scroll
                Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) { MetaText("Loading more") }
            }
        }
    }
}

/** Generated mixes (Daily Mixes, Discover Weekly, ...); before the first build, radio mixes from recent artists. */
@Composable
private fun MadeForYouShelf(c: Controller, seeds: List<SongItem>) {
    val subtitle = if (c.mixesRefreshing && c.mixes.isEmpty()) "Building your mixes from what you play" else "Mixes built from what you play and like"
    Shelf("Made for you", subtitle) {
        if (c.mixes.isNotEmpty()) itemsIndexed(c.mixes, key = { _, m -> "gen-${m.id}" }) { _, mix ->
            val cover = remember(mix.songs) { c.mixSongs(mix).firstOrNull()?.thumbnail }
            MediaCard(
                cover, mix.title, mix.subtitle,
                onClick = { c.noteShelfClick("made-for-you"); c.navigate(Route.Mix(mix.id)) },
                onPlay = { c.noteShelfClick("made-for-you"); c.playGeneratedMix(mix) },
                coverLabel = mix.title,
            )
        } else itemsIndexed(seeds, key = { _, s -> "mix-${s.id}" }) { _, seed ->
            val artist = seed.artists.firstOrNull()?.name ?: seed.title
            MediaCard(seed.thumbnail, "$artist Mix", "Starts with ${seed.title}, then similar songs", { c.noteShelfClick("made-for-you"); c.playMix(seed) }, coverLabel = "$artist Mix")
        }
    }
}

@Composable
private fun QuickTiles(c: Controller, playlists: List<Playlist>, recents: List<SongItem>, fallback: List<YTItem>?) {
    val tiles = buildList {
        add(QuickTile("liked", "Liked Songs", null, Icons.Rounded.Favorite, { c.navigate(Route.Liked) }, null))
        playlists.take(3).forEach { p ->
            add(QuickTile("pl-${p.playlist.id}", p.playlist.name, p.thumbnails.firstOrNull(), null, { c.navigate(Route.LocalPlaylist(p.playlist.id)) }, null))
        }
        recents.take(8 - size).forEachIndexed { i, s -> add(QuickTile("song-${s.id}", s.title, s.thumbnail, null, { c.playFromList(recents, i) }, { c.playFromList(recents, i) })) }
        // Brand-new profile: fill the grid with the feed's first suggestions so the page is never bare.
        fallback?.take(8 - size)?.forEach { item -> add(QuickTile("feed-${item.id}", item.title, item.thumbnail, null, { c.openItem(item, fallback) }, { c.playItem(item, fallback) })) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = when { maxWidth >= 860.dp -> 4; maxWidth >= 520.dp -> 2; else -> 1 }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(cols).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { QuickTileView(it, Modifier.weight(1f)) }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun QuickTileView(tile: QuickTile, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = if (hovered) 0.2f else 0.1f))
            .hoverable(source).clickable(source, null, onClick = tile.onOpen).handCursor(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (tile.icon != null) Box(
            Modifier.size(56.dp).background(Brush.linearGradient(listOf(Ws.defaultAccent, Color(0xFFB8A3F5)))),
            contentAlignment = Alignment.Center,
        ) { Icon(tile.icon, null, Modifier.size(24.dp), tint = Color.White) }
        else Thumb(tile.thumb, 56.dp, corner = 0.dp, seed = tile.title)
        Text(tile.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Ws.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(end = 8.dp).size(32.dp)) {
            if (hovered) PlayButton(false, false, true, tile.onPlay ?: tile.onOpen, size = 32.dp, onAccent = true)
        }
    }
}

@Composable
private fun SkeletonShelf() {
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(220.dp).height(24.dp).clip(RoundedCornerShape(4.dp)).background(Ws.surfaceHi))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            repeat(6) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(160.dp).clip(RoundedCornerShape(6.dp)).background(Ws.surfaceHi))
                    Box(Modifier.width(110.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Ws.surfaceHi))
                }
            }
        }
    }
}

// =====================================================================================
//  Online pages (playlist / album / mood category)
// =====================================================================================

@Composable
fun OnlinePage(c: Controller, d: DetailUi?) {
    when (d) {
        null, is DetailUi.Loading -> EmptyState(d?.let { "Opening ${it.title}" } ?: "Opening", "")
        is DetailUi.Failed -> EmptyState("Couldn't open “${d.title}”", "${d.message}. Check your connection and try again.")
        is DetailUi.Songs -> CollectionPage(
            c, d.kind, d.title, d.subtitle, CoverArt.Image(d.cover, d.title), d.songs,
            emptyText = "This ${d.kind.lowercase()} has no playable songs.",
            actions = {
                WsIconButton(Icons.Rounded.LibraryAdd, "Save to Your Library", { c.saveAsPlaylist(d.title, d.songs) }, size = 40.dp, iconSize = 26.dp, enabled = d.songs.isNotEmpty())
            },
        )
        is DetailUi.Collection -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(32.dp), contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp)) {
            item("title") { Text(d.title, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.displayMedium, color = Ws.text) }
            itemsIndexed(d.sections, key = { i, s -> "col-$i-${s.title}" }) { _, section ->
                Shelf(section.title ?: "Featured") { itemsIndexed(section.items) { _, item -> ItemCard(c, item, section.items) } }
            }
        }
    }
}
