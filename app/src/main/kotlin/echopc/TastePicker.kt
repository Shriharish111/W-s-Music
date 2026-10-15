package echopc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.innertube.models.ArtistItem

/** Artists to pick before the app knows the user's taste. 3 or more gives the engine enough to build mixes. */
private const val GOOD_PICK_COUNT = 3

/** "Choose artists you like": a grid of artists to tick, a search box, and Skip / Done. */
@Composable
fun TastePickerPage(c: Controller) {
    val selectedIds = c.pickerSelected.map { it.id }.toSet()
    val searching = c.pickerQuery.isNotBlank()
    // Selected artists stay at the front so a pick never scrolls out of sight; then similar artists, then the starters.
    val tiles = if (searching) c.pickerResults else (c.pickerSelected.toList() + c.pickerSimilar + c.suggestedArtists).distinctBy { it.id }
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Adaptive(152.dp), Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Choose artists you like", style = MaterialTheme.typography.displaySmall, color = Ws.text)
                    Text(
                        "Pick 3 or more and we'll build your first mixes right away. Picking an artist suggests similar ones.",
                        style = MaterialTheme.typography.bodyLarge, color = Ws.muted, modifier = Modifier.widthIn(max = 620.dp),
                    )
                    PickerSearchField(c.pickerQuery, c::searchPickerArtists) { c.typing = it }
                }
            }
            if (tiles.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                val message = when {
                    searching && c.pickerSearching -> "Searching for “${c.pickerQuery}”"
                    searching -> "No artists found for “${c.pickerQuery}”. Try another spelling."
                    c.suggestedLoading -> "Finding popular artists"
                    else -> "Couldn't load popular artists. Search for an artist above."
                }
                Text(message, style = MaterialTheme.typography.bodyMedium, color = Ws.muted)
            }
            items(tiles, key = { it.id }) { artist -> ArtistTile(artist, artist.id in selectedIds) { c.togglePick(artist) } }
        }
        PickerFooter(c)
    }
}

@Composable
private fun PickerSearchField(value: String, onChange: (String) -> Unit, onFocusChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(50)
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().height(48.dp).clip(shape).background(Ws.surfaceHi)
            .border(if (focused) 2.dp else 0.dp, if (focused) Ws.text else androidx.compose.ui.graphics.Color.Transparent, shape)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(22.dp), tint = if (focused) Ws.text else Ws.muted)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search for an artist", style = MaterialTheme.typography.bodyLarge, color = Ws.muted, maxLines = 1)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ws.text), cursorBrush = SolidColor(Ws.text),
                // Keyboard shortcuts stand down while this box has focus (see ShortcutHandler).
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; onFocusChange(it.isFocused) },
            )
        }
        if (value.isNotEmpty()) WsIconButton(Icons.Rounded.Close, "Clear search", { onChange("") }, size = 32.dp, iconSize = 18.dp)
    }
}

/** One artist: round photo, name, and a check when picked. The whole tile toggles. */
@Composable
private fun ArtistTile(artist: ArtistItem, selected: Boolean, onToggle: () -> Unit) {
    val accent = LocalAccent.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (hovered) Ws.surfaceHi else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(source)
            .toggleable(value = selected, interactionSource = source, indication = null, role = Role.Checkbox, onValueChange = { onToggle() })
            .handCursor().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(120.dp)) {
            Thumb(artist.thumbnail, 120.dp, corner = 60.dp, seed = artist.title)
            if (selected) {
                Box(Modifier.size(120.dp).clip(CircleShape).border(3.dp, accent.highlight, CircleShape).background(accent.primary.copy(alpha = 0.25f)))
                Box(
                    Modifier.align(Alignment.BottomEnd).size(32.dp).clip(CircleShape).background(accent.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = accent.onPrimary) }
            }
        }
        Text(artist.title, style = MaterialTheme.typography.titleSmall, color = Ws.text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PickerFooter(c: Controller) {
    val n = c.pickerSelected.size
    Row(
        Modifier.fillMaxWidth().background(Ws.surface).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            when {
                n == 0 -> "Pick artists you like"
                n < GOOD_PICK_COUNT -> "$n picked. ${GOOD_PICK_COUNT - n} more gives better mixes."
                else -> "$n picked. That's plenty to start with."
            },
            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Ws.muted,
        )
        OutlineButton("Skip", { c.skipPicker() })
        PrimaryButton("Done", { c.savePicks() }, enabled = n > 0)
    }
}
