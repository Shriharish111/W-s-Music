package echopc

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow

// ---------- surfaces & effects ----------

fun Modifier.panel(radius: Dp = 18.dp, color: Color = Ws.surface): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.background(color, shape).border(1.dp, Ws.border, shape)
}

/** Soft radial glow behind an element; drawn only, no layout or recomposition cost. */
fun Modifier.glow(color: Color, radius: Dp, alpha: Float = 0.45f): Modifier = drawBehind {
    val r = radius.toPx()
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, r), r, center)
}

@Composable
fun Modifier.handCursor(): Modifier = this.pointerHoverIcon(PointerIcon.Hand)

// ---------- buttons ----------

@Composable
fun WsIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    tint: Color = Ws.muted,
    hoverTint: Color = Ws.text,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(stiffness = 600f))
    val color by animateColorAsState(if (!enabled) Ws.faint else if (hovered) hoverTint else tint, tween(120))
    val bg by animateColorAsState(if (hovered && enabled) Color.White.copy(alpha = 0.07f) else Color.Transparent, tween(120))
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(bg)
            .hoverable(source)
            .clickable(source, null, enabled = enabled, onClick = onClick)
            .handCursor(),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(iconSize), tint = color) }
}

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val accent = LocalAccent.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else if (hovered) 1.03f else 1f, spring(stiffness = 500f))
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.45f }
            .shadow(if (hovered) 14.dp else 8.dp, shape, ambientColor = accent.primary, spotColor = accent.primary)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(accent.primary, accent.secondary)))
            .hoverable(source)
            .clickable(source, null, enabled = enabled, onClick = onClick)
            .handCursor()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, danger: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val accent = LocalAccent.current
    val base = if (danger) Ws.danger else accent.primary
    val border by animateColorAsState(if (hovered && enabled) base else Ws.border, tween(150))
    val bg by animateColorAsState(if (hovered && enabled) base.copy(alpha = 0.12f) else Color.Transparent, tween(150))
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = 500f))
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .clip(shape).background(bg).border(1.dp, border, shape)
            .hoverable(source)
            .clickable(source, null, enabled = enabled, onClick = onClick)
            .handCursor()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = if (danger) Ws.danger else Ws.text)
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (danger) Ws.danger else Ws.text, maxLines = 1)
    }
}

/** Big circular play/pause with a coloured glow; shows a spinner while the next track loads. */
@Composable
fun PlayButton(playing: Boolean, loading: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val accent = LocalAccent.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else if (hovered) 1.08f else 1f, spring(stiffness = 500f))
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.5f }
            .shadow(if (hovered) 22.dp else 14.dp, CircleShape, ambientColor = accent.primary, spotColor = accent.primary)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(accent.primary, accent.secondary)))
            .hoverable(source)
            .clickable(source, null, enabled = enabled, onClick = onClick)
            .handCursor(),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(size * 0.45f), color = Color.White, strokeWidth = 2.5.dp)
        else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(size * 0.55f), tint = Color.White)
    }
}

@Composable
fun WsChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val accent = LocalAccent.current
    val border by animateColorAsState(if (hovered || selected) accent.primary else Ws.border, tween(120))
    val bg by animateColorAsState(if (selected) accent.primary.copy(alpha = 0.35f) else if (hovered) accent.primary.copy(alpha = 0.14f) else Ws.surface, tween(120))
    val shape = RoundedCornerShape(50)
    Text(
        text,
        modifier
            .clip(shape).background(bg).border(1.dp, border, shape)
            .hoverable(source).clickable(source, null, onClick = onClick).handCursor()
            .padding(horizontal = 14.dp, vertical = 7.dp),
        style = MaterialTheme.typography.bodyMedium, color = if (hovered || selected) Ws.text else Ws.muted,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

// ---------- tabs ----------

/** Pill-shaped tab group whose highlight slides to the selected option. */
@Composable
fun SegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(14.dp)
    BoxWithConstraints(modifier.height(42.dp).clip(shape).background(Ws.surface).border(1.dp, Ws.border, shape).padding(3.dp)) {
        val tabW = maxWidth / options.size
        val x by animateDpAsState(tabW * selected, spring(dampingRatio = 0.8f, stiffness = 500f))
        Box(
            Modifier.offset(x = x).width(tabW).fillMaxSize()
                .clip(RoundedCornerShape(11.dp))
                .background(Brush.horizontalGradient(listOf(accent.primary.copy(alpha = 0.85f), accent.secondary.copy(alpha = 0.70f)))),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val c by animateColorAsState(if (i == selected) Ws.text else Ws.muted, tween(150))
                Box(
                    Modifier.width(tabW).fillMaxSize().clip(RoundedCornerShape(11.dp)).clickable(remember { MutableInteractionSource() }, null) { onSelect(i) }.handCursor(),
                    contentAlignment = Alignment.Center,
                ) { Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = c, maxLines = 1) }
            }
        }
    }
}

// ---------- sliders ----------

/**
 * Seek / volume bar. [value] is read while drawing, so a fast-changing playback position redraws
 * only this small canvas instead of recomposing the screen.
 */
@Composable
fun NeonSlider(
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
    val thumbScale by animateFloatAsState(if (dragging != null || hovered) 1f else 0f, tween(120))
    Box(
        modifier.height(22.dp).hoverable(source).handCursor()
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
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val h = 4.dp.toPx()
            val y = (size.height - h) / 2f
            val v = (dragging ?: value()).coerceIn(0f, 1f)
            drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
            val w = size.width * v
            if (w > 0f) {
                drawRoundRect(
                    Brush.horizontalGradient(listOf(accent.primary, accent.secondary), 0f, size.width.coerceAtLeast(1f)),
                    Offset(0f, y), Size(w, h), CornerRadius(h / 2),
                )
            }
            if (thumbScale > 0.01f && enabled) {
                val r = 7.dp.toPx() * thumbScale
                drawCircle(accent.secondary.copy(alpha = 0.35f), r * 2f, Offset(w, size.height / 2))
                drawCircle(Color.White, r, Offset(w, size.height / 2))
            }
        }
    }
}

// ---------- animated bits ----------

/** Little equalizer shown on the row that is playing. Animation runs in the draw phase only. */
@Composable
fun EqBars(active: Boolean, modifier: Modifier = Modifier, color: Color = LocalAccent.current.primary) {
    val t = rememberInfiniteTransition(label = "eq")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(430, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.4f, 0.95f, infiniteRepeatable(tween(610, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    androidx.compose.foundation.Canvas(modifier.size(18.dp)) {
        val barW = size.width / 5f
        val hs = if (active) listOf(a, b, c) else listOf(0.3f, 0.3f, 0.3f)
        hs.forEachIndexed { i, hh ->
            val h = size.height * hh
            drawRoundRect(color, Offset(i * barW * 2f, size.height - h), Size(barW, h), CornerRadius(barW / 2))
        }
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelMedium, color = Ws.faint)
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
    val accent = LocalAccent.current
    val bg by animateColorAsState(
        when { selected -> accent.primary.copy(alpha = 0.16f); hovered -> Color.White.copy(alpha = 0.06f); else -> Color.Transparent },
        tween(120),
    )
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.fillMaxWidth().clip(shape).background(bg)
            .then(if (selected) Modifier.border(1.dp, accent.primary.copy(alpha = 0.35f), shape) else Modifier)
            .hoverable(source).clickable(source, null, onClick = onClick).handCursor(),
    ) { content(hovered) }
}
