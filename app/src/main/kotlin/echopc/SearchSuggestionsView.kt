package echopc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem

/**
 * Shown on the search page while the person is still typing: the artist, songs, albums and playlists
 * that match what has been typed so far (from YouTube Music), plus ways to finish the search.
 * Enter, or the first row, runs the search for exactly what was typed.
 */
@Composable
fun SuggestionsView(c: Controller) {
    val typed = c.query.trim()
    val history by c.library.searchHistory().collectAsState(emptyList())
    val recent = remember(history, typed) {
        history.map { it.query }.distinct().filter { it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }.take(3)
    }
    val fresh = c.suggestionsFor.equals(typed, ignoreCase = true)
    val suggestions = if (fresh) c.suggestions else c.suggestions?.takeIf { c.suggestionsFor.isNotEmpty() && typed.startsWith(c.suggestionsFor, ignoreCase = true) }
    val queries = suggestions?.queries.orEmpty().filter { !it.equals(typed, ignoreCase = true) && it !in recent }.take(6)
    val items = suggestions?.recommendedItems.orEmpty().take(6)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item("go") { SuggestionRow(Icons.Rounded.Search, "Search for “$typed”", null) { c.search(typed) } }
        if (items.isNotEmpty()) {
            item("items-title") { SectionTitle("Top matches") }
            itemsIndexed(items, key = { i, it -> "i$i-${it.id}" }) { _, item -> ItemSuggestion(c, item) }
        }
        if (recent.isNotEmpty() || queries.isNotEmpty()) item("queries-title") { SectionTitle("Suggestions") }
        itemsIndexed(recent, key = { i, _ -> "r$i" }) { _, q -> SuggestionRow(Icons.Rounded.History, q, null) { c.search(q) } }
        itemsIndexed(queries, key = { i, _ -> "q$i" }) { _, q -> SuggestionRow(Icons.Rounded.Search, q, null) { c.search(q) } }
        if (suggestions == null && c.suggestionsLoading) item("loading") {
            Text("Finding matches…", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium, color = Ws.text)
}

@Composable
private fun ItemSuggestion(c: Controller, item: YTItem) {
    val kind = when (item) {
        is SongItem -> listOf(if (item.isVideoSong) "Video" else "Song", item.artists.joinToString { it.name }).filter { it.isNotBlank() }.joinToString(" • ")
        is AlbumItem -> listOf("Album", item.artists?.joinToString { it.name }.orEmpty()).filter { it.isNotBlank() }.joinToString(" • ")
        is ArtistItem -> "Artist"
        is PlaylistItem -> listOf("Playlist", item.author?.name.orEmpty()).filter { it.isNotBlank() }.joinToString(" • ")
        else -> ""
    }
    SuggestionRow(null, item.title, kind, thumb = item.thumbnail, round = item is ArtistItem) {
        c.typing = false
        when (item) {
            is SongItem -> c.playFromList(listOf(item), 0)
            else -> c.openItem(item, emptyList())
        }
    }
}

@Composable
private fun SuggestionRow(icon: ImageVector?, title: String, subtitle: String?, thumb: String? = null, round: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(6.dp))
            .background(if (hovered) Ws.surfaceHi else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(source).clickable(source, null, onClick = onClick).handCursor()
            .height(if (thumb != null) 56.dp else 44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (thumb != null || icon == null) Thumb(thumb, 40.dp, corner = if (round) 20.dp else 4.dp, seed = title)
        else Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = Ws.muted) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ws.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
