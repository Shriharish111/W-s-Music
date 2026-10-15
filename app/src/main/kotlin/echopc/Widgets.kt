package echopc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Performance note: this UI deliberately has no continuous animations, spring/scale effects or
// blurred drop shadows. Compose only redraws when something actually changes, so an idle window
// costs ~0% CPU, and hover/press feedback is an instant colour change.

// ---------- surfaces ----------

/** A main-window panel: flat #121212 with small rounded corners, sitting on the black window. */
fun Modifier.panel(radius: Dp = 8.dp, color: Color = Ws.surface): Modifier = this.clip(RoundedCornerShape(radius)).background(color)

@Composable
fun Modifier.handCursor(): Modifier = this.pointerHoverIcon(PointerIcon.Hand)

/** Keyboard focus ring: a 2dp white outline while focused (hover alone never shows it). */
@Composable
fun Modifier.focusRing(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(50)): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this.onFocusChanged { focused = it.isFocused }.then(if (focused) Modifier.border(2.dp, Ws.text, shape) else Modifier)
}

// ---------- buttons ----------

@Composable
fun WsIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    tint: Color = Ws.muted,
    hoverTint: Color = Ws.text,
    enabled: Boolean = true,
    background: Color = Color.Transparent,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val color = if (!enabled) Ws.faint.copy(alpha = 0.6f) else if (hovered) hoverTint else tint
    Box(
        modifier
            .size(size)
            .focusRing(CircleShape)
            .clip(CircleShape)
            .background(background)
            .hoverable(source)
            .clickable(source, null, enabled = enabled, onClick = onClick)
            .handCursor(),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(iconSize), tint = color) }
}

/** Filled pill in the accent colour: the one primary action on a page. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val accent = LocalAccent.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg = if (hovered && enabled) accent.highlight else accent.primary
    Row(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .focusRing()
            .clip(RoundedCornerShape(50))
            .background(bg)
            .hoverable(source)
            .clickable(source, null, enabled = enabled, onClick = onClick)
            .handCursor()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = accent.onPrimary)
        Text(text, style = MaterialTheme.typography.labelLarge, color = accent.onPrimary, maxLines = 1)
    }
}

/** Outlined pill for secondary actions. */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, danger: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val lit = hovered && enabled
    val ink = if (danger) Ws.danger else Ws.text
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .focusRing()
            .clip(shape).border(1.dp, if (lit) ink else Ws.faint, shape)
            .hoverable(source)
            .clickable(source, null, enabled = enabled, onClick = onClick)
            .handCursor()
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = ink)
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

/**
 * Round play/pause. [onAccent] = the big accent-coloured button on pages; otherwise white (player bar).
 * While the next track loads it shows a static "…" (no spinning animation).
 */
@Composable
fun PlayButton(
    playing: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    onAccent: Boolean = false,
) {
    val accent = LocalAccent.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg = when {
        onAccent -> if (hovered) accent.highlight else accent.primary
        else -> if (hovered) Color(0xFFE6E6E6) else Color.White
    }
    val ink = if (onAccent) accent.onPrimary else Color.Black
    Box(
        modifier
            .size(size)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .focusRing(CircleShape)
            .clip(CircleShape)
            .background(bg)
            .hoverable(source)
            .clickable(source, null, enabled = enabled, onClick = onClick)
            .handCursor(),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = when { loading -> Icons.Rounded.MoreHoriz; playing -> Icons.Rounded.Pause; else -> Icons.Rounded.PlayArrow }
        Icon(glyph, if (playing) "Pause" else "Play", Modifier.size(size * 0.58f), tint = ink)
    }
}

/**
 * Three-state shuffle: grey (off), accent (shuffle), accent with a sparkle (Smart Shuffle).
 * [description] says what a click will do.
 */
@Composable
fun ShuffleButton(mode: ShuffleMode, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 32.dp, iconSize: Dp = 18.dp, enabled: Boolean = true) {
    val accent = LocalAccent.current
    Box(modifier.size(size)) {
        WsIconButton(
            Icons.Rounded.Shuffle, description, onClick, size = size, iconSize = iconSize, enabled = enabled,
            tint = if (mode == ShuffleMode.OFF) Ws.muted else accent.highlight, hoverTint = if (mode == ShuffleMode.OFF) Ws.text else accent.primary,
        )
        if (mode == ShuffleMode.SMART) Icon(
            Icons.Rounded.AutoAwesome, null,
            Modifier.align(Alignment.TopEnd).size(iconSize * 0.55f), tint = accent.highlight,
        )
    }
}

/** What clicking a shuffle button does next, for its label. */
fun shuffleActionLabel(mode: ShuffleMode, smartAvailable: Boolean): String = when (mode) {
    ShuffleMode.OFF -> "Turn on shuffle"
    ShuffleMode.ON -> if (smartAvailable) "Turn on Smart Shuffle" else "Turn off shuffle"
    ShuffleMode.SMART -> "Turn off Smart Shuffle"
}

/** Filter chip. Selected = white with black text. */
@Composable
fun WsChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Text(
        text,
        modifier
            .focusRing()
            .clip(RoundedCornerShape(50))
            .background(if (selected) Ws.text else if (hovered) Ws.border else Ws.surfaceTop)
            .hoverable(source).clickable(source, null, onClick = onClick).handCursor()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodyMedium, color = if (selected) Color.Black else Ws.text,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

// ---------- sliders ----------

/**
 * Seek / volume bar: a thin white line that turns the accent colour on hover. [value] is read while
 * drawing, so a fast-changing playback position redraws only this small canvas.
 */
@Composable
fun SeekBar(
    value: () -> Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onChangeFinished: (Float) -> Unit = {},
) {
    val accent = LocalAccent.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val active = (dragging != null || hovered) && enabled
    Box(
        modifier.height(16.dp).hoverable(source).handCursor()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { pos ->
                    val v = (pos.x / size.width).coerceIn(0f, 1f)
                    onChange(v); onChangeFinished(v)
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { pos -> dragging = (pos.x / size.width).coerceIn(0f, 1f).also(onChange) },
                    onDragEnd = { dragging?.let(onChangeFinished); dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ ->
                    change.consume()
                    dragging = (change.position.x / size.width).coerceIn(0f, 1f).also(onChange)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = 4.dp.toPx()
            val y = (size.height - h) / 2f
            val v = (dragging ?: value()).coerceIn(0f, 1f)
            drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
            val w = size.width * v
            if (w > 0f) drawRoundRect(if (active) accent.highlight else Color.White, Offset(0f, y), Size(w, h), CornerRadius(h / 2))
            if (active) drawCircle(Color.White, 6.dp.toPx(), Offset(w, size.height / 2))
        }
    }
}

// ---------- small pieces ----------

/** "Now playing" marker: three static bars (an endlessly animated equalizer forced a redraw every frame). */
@Composable
fun EqBars(active: Boolean, modifier: Modifier = Modifier, color: Color = LocalAccent.current.highlight) {
    Canvas(modifier.size(14.dp)) {
        val barW = size.width / 5f
        val hs = if (active) listOf(0.45f, 1f, 0.7f) else listOf(0.3f, 0.3f, 0.3f)
        hs.forEachIndexed { i, hh ->
            val h = size.height * hh
            drawRoundRect(color, Offset(i * barW * 2f, size.height - h), Size(barW, h), CornerRadius(barW / 2))
        }
    }
}

/** Small grey meta line ("12 songs", "via LRCLIB"). */
@Composable
fun MetaText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = Ws.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Full-width list row with hover highlight; [content] lays out its own children. */
@Composable
fun HoverRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable (hovered: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg = when { selected -> Color.White.copy(alpha = 0.12f); hovered -> Color.White.copy(alpha = 0.07f); else -> Color.Transparent }
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(bg)
            .hoverable(source).clickable(source, null, onClick = onClick).handCursor(),
    ) { content(hovered) }
}
