package echopc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/** Gap between the panels; the black window shows through. */
private val Gap = 8.dp

@Composable
fun AppContent(c: Controller, onPickAndroidFile: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ws.bg0)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(c, onPickAndroidFile)
            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Gap)) {
                LibrarySidebar(c, onPickAndroidFile)
                SidebarResizer(c)
                MainView(c, Modifier.weight(1f).fillMaxHeight())
                if (c.panelOpen) {
                    Box(Modifier.width(Gap))
                    RightPanel(c)
                }
            }
            PlayerBar(c)
        }
        // Toast: just above the player bar, like a snackbar.
        c.toast?.let { message ->
            Text(
                message,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Ws.text).padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium, color = Color.Black,
            )
        }
        if (c.newPlaylistOpen) NameDialog("New playlist", "", "Create", onConfirm = c::createPlaylist, onDismiss = { c.newPlaylistOpen = false; c.newPlaylistFor = null })
        ImportDialog(c)
    }
}

/** The page area: one rounded panel whose content follows [Controller.route]. */
@Composable
private fun MainView(c: Controller, modifier: Modifier) {
    Box(modifier.panel()) {
        when (val r = c.route) {
            Route.Home -> HomeScreen(c)
            Route.Search -> SearchPage(c)
            Route.Liked -> LikedPage(c)
            Route.LibrarySongs -> LibrarySongsPage(c)
            Route.History -> HistoryPage(c)
            Route.Lyrics -> LyricsPage(c)
            is Route.LocalPlaylist -> LocalPlaylistPage(c, r.id)
            is Route.Online -> OnlinePage(c, c.details[r.key])
            is Route.Mix -> MixPage(c, r.id)
        }
    }
}

/** Drag handle between the library and the page; drag to resize, the sidebar collapses when narrow. */
@Composable
private fun SidebarResizer(c: Controller) {
    val density = LocalDensity.current.density
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var dragTotal by remember { mutableStateOf(0f) }
    Box(
        Modifier.width(Gap).fillMaxHeight()
            .hoverable(source)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(
                rememberDraggableState { px ->
                    val dp = px / density
                    if (c.sidebarCollapsed) {
                        dragTotal += dp
                        if (dragTotal > 60f) { c.sidebarCollapsed = false; c.sidebarWidth = SIDEBAR_MIN; dragTotal = 0f }
                    } else {
                        val w = c.sidebarWidth + dp
                        if (w < SIDEBAR_MIN - 80f) { c.sidebarCollapsed = true; dragTotal = 0f } else c.sidebarWidth = w.coerceIn(SIDEBAR_MIN, SIDEBAR_MAX)
                    }
                },
                Orientation.Horizontal,
                onDragStopped = { dragTotal = 0f },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (hovered) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(Ws.faint))
    }
}

// =====================================================================================
//  Top bar
// =====================================================================================

@Composable
private fun TopBar(c: Controller, onPickAndroidFile: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandMark()
            Box(Modifier.width(8.dp))
            WsIconButton(Icons.Rounded.ChevronLeft, "Go back", { c.goBack() }, size = 32.dp, iconSize = 24.dp, enabled = c.canGoBack, background = Ws.surface)
            WsIconButton(Icons.Rounded.ChevronRight, "Go forward", { c.goForward() }, size = 32.dp, iconSize = 24.dp, enabled = c.canGoForward, background = Ws.surface)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val home = c.route == Route.Home
            WsIconButton(if (home) Icons.Rounded.Home else Icons.Outlined.Home, "Home", { c.goHome() }, size = 48.dp, iconSize = 24.dp, tint = if (home) Ws.text else Ws.muted, background = Ws.surfaceHi)
            TopSearch(c)
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            WsIconButton(Icons.Rounded.FileUpload, "Import from the Android app", onPickAndroidFile, size = 32.dp, iconSize = 18.dp)
        }
    }
}

/** The W mark: fixed brand violet, not the album-art accent. */
@Composable
private fun BrandMark() {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Ws.defaultAccent), contentAlignment = Alignment.Center) {
        Text("W", style = MaterialTheme.typography.titleMedium.copy(fontFamily = BrandFont), color = Color.White)
    }
}

@Composable
private fun TopSearch(c: Controller) {
    var focused by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.widthIn(min = 280.dp, max = 480.dp).width(420.dp).height(48.dp).clip(shape).background(if (hovered || focused) Ws.surfaceTop else Ws.surfaceHi)
            .border(if (focused) 2.dp else 0.dp, if (focused) Ws.text else Color.Transparent, shape)
            .hoverable(source).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(22.dp), tint = if (focused) Ws.text else Ws.muted)
        Box(Modifier.weight(1f)) {
            if (c.query.isEmpty()) Text("What do you want to play?", style = MaterialTheme.typography.bodyLarge, color = Ws.muted, maxLines = 1)
            BasicTextField(
                c.query, { c.query = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ws.text),
                cursorBrush = SolidColor(Ws.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { c.search() }),
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused; c.typing = it.isFocused; if (it.isFocused) c.navigate(Route.Search) }
                    .onKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { c.search(); true } else false },
            )
        }
        if (c.query.isNotEmpty()) WsIconButton(Icons.Rounded.Close, "Clear search", { c.query = "" }, size = 32.dp, iconSize = 18.dp)
    }
}

// =====================================================================================
//  Dialogs
// =====================================================================================

@Composable
fun NameDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
        title = { Text(title, style = MaterialTheme.typography.headlineMedium, color = Ws.text) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }, shape = RoundedCornerShape(4.dp)) },
        confirmButton = { PrimaryButton(confirmLabel, { onConfirm(text) }, enabled = text.isNotBlank()) },
        dismissButton = { OutlineButton("Cancel", onDismiss) },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
        title = { Text(title, style = MaterialTheme.typography.headlineMedium, color = Ws.text) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, color = Ws.muted) },
        confirmButton = { OutlineButton(confirmLabel, onConfirm, danger = true) },
        dismissButton = { OutlineButton("Cancel", onDismiss) },
    )
}

@Composable
private fun ImportDialog(c: Controller) {
    when (val s = c.importUi) {
        ImportUi.Idle -> Unit
        is ImportUi.Running -> AlertDialog(
            onDismissRequest = {}, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
            title = { Text("Importing", style = MaterialTheme.typography.headlineMedium, color = Ws.text) },
            text = { Text("Reading ${s.fileName}. This takes a few seconds.", style = MaterialTheme.typography.bodyMedium, color = Ws.muted) },
            confirmButton = {},
        )
        is ImportUi.Done -> AlertDialog(
            onDismissRequest = { c.importUi = ImportUi.Idle }, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
            title = { Text("Import complete", style = MaterialTheme.typography.headlineMedium, color = Ws.text) },
            text = { Text(s.result.summary(), style = MaterialTheme.typography.bodyMedium, color = Ws.muted) },
            confirmButton = { PrimaryButton("Done", { c.importUi = ImportUi.Idle }) },
        )
        is ImportUi.Failed -> AlertDialog(
            onDismissRequest = { c.importUi = ImportUi.Idle }, containerColor = Ws.surfaceHi, shape = RoundedCornerShape(8.dp),
            title = { Text("Import failed", style = MaterialTheme.typography.headlineMedium, color = Ws.danger) },
            text = { Text(s.message, style = MaterialTheme.typography.bodyMedium, color = Ws.muted) },
            confirmButton = { OutlineButton("Close", { c.importUi = ImportUi.Idle }) },
        )
    }
}
