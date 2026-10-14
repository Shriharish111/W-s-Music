package echopc

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.net.URI
import java.util.Collections
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LoadedThumb(val bitmap: ImageBitmap, val dominant: Color)

/**
 * Album-art loader: asks the server for a small image (instead of 544px for a 44px row), decodes
 * off the UI thread, keeps a bounded LRU cache and works out a vivid dominant colour for theming.
 */
object ThumbCache {
    private val cache: MutableMap<String, LoadedThumb> =
        Collections.synchronizedMap(object : LinkedHashMap<String, LoadedThumb>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LoadedThumb>?) = size > 400
        })
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val sizeRegex = Regex("=w\\d+-h\\d+[^/?]*$")

    /** googleusercontent thumbnails accept a size suffix; other hosts are used as they are. */
    fun sized(url: String, px: Int): String =
        if (sizeRegex.containsMatchIn(url)) url.replace(sizeRegex, "=w$px-h$px-l90-rj") else url

    fun peek(url: String, px: Int): LoadedThumb? = cache[sized(url, px)]

    suspend fun load(url: String, px: Int): LoadedThumb? {
        val key = sized(url, px)
        cache[key]?.let { return it }
        if (key in failed) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val src = ImageIO.read(URI.create(key).toURL()) ?: return@runCatching null
                val fitted = fit(src, px)
                LoadedThumb(fitted.toComposeImageBitmap(), dominantColor(fitted)).also { cache[key] = it }
            }.getOrNull().also { if (it == null) failed += key }
        }
    }

    private fun fit(src: BufferedImage, px: Int): BufferedImage {
        // Square-crop, then scale down to at most 2x the display size (sharp on HiDPI, small in memory).
        val side = minOf(src.width, src.height)
        val crop = src.getSubimage((src.width - side) / 2, (src.height - side) / 2, side, side)
        val target = minOf(side, px * 2)
        if (target == side) return crop
        val out = BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(crop, 0, 0, target, target, null)
            dispose()
        }
        return out
    }

    /** Most vivid colour in the picture (saturation-weighted average of a 12x12 sample), brightened for dark UIs. */
    fun dominantColor(img: BufferedImage): Color {
        val n = 12
        val small = BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB)
        small.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, n, n, null)
            dispose()
        }
        var r = 0.0; var g = 0.0; var b = 0.0; var wSum = 0.0
        val hsb = FloatArray(3)
        for (y in 0 until n) for (x in 0 until n) {
            val p = small.getRGB(x, y)
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            java.awt.Color.RGBtoHSB(pr, pg, pb, hsb)
            val w = 0.05 + hsb[1] * hsb[1] * (0.3 + hsb[2]) // vivid and not-too-dark pixels count more
            r += pr * w; g += pg * w; b += pb * w; wSum += w
        }
        if (wSum == 0.0) return Ws.defaultAccent
        java.awt.Color.RGBtoHSB((r / wSum).toInt(), (g / wSum).toInt(), (b / wSum).toInt(), hsb)
        // Greyscale art falls back to the brand violet; otherwise boost saturation/brightness.
        if (hsb[1] < 0.12f) return Ws.defaultAccent
        val rgb = java.awt.Color.HSBtoRGB(hsb[0], hsb[1].coerceIn(0.55f, 0.95f), hsb[2].coerceIn(0.78f, 1f))
        return Color(rgb or (0xFF shl 24))
    }
}

/** Placeholder art: soft gradient plus the first letter, used until (or instead of) the image. */
@Composable
private fun Placeholder(seed: String, modifier: Modifier = Modifier) {
    val h = (seed.hashCode().toLong() and 0xFFFF) / 65535f * 360f
    val a = Color.hsv(h, 0.55f, 0.55f)
    val b = Color.hsv((h + 50f) % 360f, 0.6f, 0.30f)
    Box(modifier.background(Brush.linearGradient(listOf(a, b))), contentAlignment = Alignment.Center) {
        Text(
            seed.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪",
            style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.75f),
        )
    }
}

@Composable
fun Thumb(url: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 10.dp, seed: String = url.orEmpty()) {
    val px = (size.value.toInt() * 2).coerceIn(64, 600)
    val loaded by produceState<LoadedThumb?>(url?.takeIf { it.isNotBlank() }?.let { ThumbCache.peek(it, px) }, url) {
        value = if (url.isNullOrBlank()) null else ThumbCache.peek(url, px) ?: ThumbCache.load(url, px)
    }
    Box(modifier.size(size).clip(RoundedCornerShape(corner))) {
        val l = loaded
        if (l == null) Placeholder(seed, Modifier.fillMaxSize())
        else Image(l.bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
