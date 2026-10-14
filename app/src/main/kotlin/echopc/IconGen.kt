package echopc

import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** Writes app/icon.ico (and icon.png) from the in-code icon. Run: gradle :app:smoke -Pmain=echopc.IconGenKt */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: ".")
    val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
    val pngs = sizes.map { s -> ByteArrayOutputStream().also { ImageIO.write(renderAppIcon(s), "png", it) }.toByteArray() }
    val out = ByteArrayOutputStream()
    fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
    fun le32(v: Int) { le16(v and 0xFFFF); le16((v shr 16) and 0xFFFF) }
    le16(0); le16(1); le16(sizes.size)
    var offset = 6 + 16 * sizes.size
    sizes.forEachIndexed { i, s ->
        out.write(if (s >= 256) 0 else s); out.write(if (s >= 256) 0 else s); out.write(0); out.write(0)
        le16(1); le16(32); le32(pngs[i].size); le32(offset)
        offset += pngs[i].size
    }
    pngs.forEach { out.write(it) }
    File(dir, "icon.ico").writeBytes(out.toByteArray())
    ImageIO.write(renderAppIcon(512), "png", File(dir, "icon.png"))
    println("wrote ${File(dir, "icon.ico").absolutePath} (${out.size()} bytes)")
}
