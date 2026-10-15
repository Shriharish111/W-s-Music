package echopc

import java.io.RandomAccessFile
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import net.sourceforge.jaad.aac.Decoder

/** Decoded-PCM source used by [CompatPlayer]: 16-bit little-endian samples. */
interface PcmSource : AutoCloseable {
    val format: AudioFormat
    val durationMs: Long
    /** Fills [buf] with PCM bytes; returns the count, or -1 at the end. */
    fun read(buf: ByteArray): Int
    fun seek(ms: Long)
}

/**
 * Reads AAC from fragmented MP4 (the SABR/DASH audio YouTube serves: moov + many moof/mdat pairs),
 * which the decoder library's own file reader cannot open, and decodes it frame by frame.
 */
class FragmentedAacSource private constructor(
    private val file: RandomAccessFile,
    private val frames: LongArray,   // file offset of each AAC frame
    private val sizes: IntArray,
    private val asc: ByteArray,
) : PcmSource {
    private var decoder = Decoder.create(asc)
    private var next = 0
    private var pending = ByteArray(0)
    private var pendingPos = 0
    private var scratch = ByteArray(8192)
    override val format: AudioFormat = decoder.audioFormat.let {
        AudioFormat(AudioFormat.Encoding.PCM_SIGNED, it.sampleRate, 16, it.channels, it.channels * 2, it.sampleRate, false)
    }
    override val durationMs: Long = (frames.size * 1024L * 1000L / format.sampleRate).toLong()

    private val receiver = net.sourceforge.jaad.aac.Receiver { channels, length, _ -> // (samples per channel, sample rate)
        val ch = channels.size
        val out = ByteArray(length * ch * 2)
        var o = 0
        for (i in 0 until length) for (c in 0 until ch) {
            val v = channels[c][i].toInt().coerceIn(-32768, 32767)
            out[o++] = v.toByte(); out[o++] = (v shr 8).toByte()
        }
        pending = out; pendingPos = 0
    }

    private fun decodeNext(): Boolean {
        if (next >= frames.size) return false
        val size = sizes[next]
        if (scratch.size < size) scratch = ByteArray(size)
        file.seek(frames[next]); file.readFully(scratch, 0, size)
        next++
        pending = ByteArray(0); pendingPos = 0
        try { decoder.decodeFrame(scratch.copyOf(size), receiver) } catch (e: Exception) { Device.log("AAC frame skipped: ${e.stackTraceToString().take(700)}") }
        return true
    }

    override fun read(buf: ByteArray): Int {
        while (pendingPos >= pending.size) if (!decodeNext()) return -1
        val n = minOf(buf.size, pending.size - pendingPos)
        System.arraycopy(pending, pendingPos, buf, 0, n)
        pendingPos += n
        return n
    }

    override fun seek(ms: Long) {
        val target = (ms * format.sampleRate / 1000 / 1024).toInt().coerceIn(0, frames.size)
        decoder = Decoder.create(asc) // fresh state; two frames of warm-up (discarded) avoid a click
        next = maxOf(0, target - 2)
        while (next < target && decodeNext()) { /* discard */ }
        pending = ByteArray(0); pendingPos = 0
    }

    override fun close() { runCatching { file.close() } }

    companion object {
        private fun int(b: ByteArray, i: Int) = ((b[i].toInt() and 255) shl 24) or ((b[i + 1].toInt() and 255) shl 16) or ((b[i + 2].toInt() and 255) shl 8) or (b[i + 3].toInt() and 255)

        /** Returns null if [path] is not a fragmented MP4 with an AAC track. */
        fun open(path: Path): FragmentedAacSource? {
            val f = RandomAccessFile(path.toFile(), "r")
            try {
                val len = f.length()
                val offsets = ArrayList<Long>(); val sizeList = ArrayList<Int>()
                var asc: ByteArray? = null
                var pos = 0L
                val head = ByteArray(16)
                while (pos + 8 <= len) {
                    f.seek(pos); f.readFully(head, 0, 8)
                    var size = int(head, 0).toLong() and 0xFFFFFFFFL
                    val type = String(head, 4, 4, Charsets.ISO_8859_1)
                    if (size == 1L) { f.readFully(head, 8, 8); size = java.nio.ByteBuffer.wrap(head, 8, 8).long }
                    if (size == 0L) size = len - pos
                    if (size < 8) break
                    if (type == "moov") {
                        f.seek(pos + 8); val body = ByteArray(size.toInt() - 8); f.readFully(body); asc = findAsc(body)
                    } else if (type == "moof") {
                        f.seek(pos + 8); val body = ByteArray(size.toInt() - 8); f.readFully(body)
                        parseMoof(body, pos, offsets, sizeList)
                    }
                    pos += size
                }
                val config = asc
                if (config == null || offsets.isEmpty()) { f.close(); return null }
                return FragmentedAacSource(f, offsets.toLongArray(), sizeList.toIntArray(), config)
            } catch (e: Throwable) {
                runCatching { f.close() }
                throw e
            }
        }

        /** Finds the AudioSpecificConfig inside the esds box. */
        private fun findAsc(moov: ByteArray): ByteArray? {
            val tag = "esds".toByteArray(Charsets.ISO_8859_1)
            var i = 0
            while (i + 4 < moov.size) {
                if (moov[i] == tag[0] && moov[i + 1] == tag[1] && moov[i + 2] == tag[2] && moov[i + 3] == tag[3]) break
                i++
            }
            if (i + 4 >= moov.size) return null
            var p = i + 4 + 4 // box type + version/flags
            fun readLen(): Int {
                var l = 0; var n = 0
                do { val b = moov[p++].toInt() and 255; l = (l shl 7) or (b and 0x7F); n++; if (b and 0x80 == 0) break } while (n < 4)
                return l
            }
            while (p < moov.size - 2) {
                val t = moov[p++].toInt() and 255
                val l = readLen()
                when (t) {
                    0x03 -> p += 3                 // ES_ID + flags
                    0x04 -> p += 13                // object type, stream type, buffer size, bitrates
                    0x05 -> {
                        val cfg = moov.copyOfRange(p, minOf(moov.size, p + l))
                        // Plain AAC-LC needs only the first two bytes; trailing padding upsets the decoder.
                        return if (cfg.size > 2 && (cfg[0].toInt() shr 3) == 2) cfg.copyOf(2) else cfg
                    }
                    else -> p += l
                }
            }
            return null
        }

        private fun parseMoof(body: ByteArray, moofPos: Long, offsets: MutableList<Long>, sizes: MutableList<Int>) {
            var p = 0
            while (p + 8 <= body.size) {
                val size = int(body, p); val type = String(body, p + 4, 4, Charsets.ISO_8859_1)
                if (size < 8) return
                if (type == "traf") parseTraf(body, p + 8, p + size, moofPos, offsets, sizes)
                p += size
            }
        }

        // [moofStart] is the file offset of the moof box itself (sample data offsets are relative to it).
        private fun parseTraf(b: ByteArray, start: Int, end: Int, moofStart: Long, offsets: MutableList<Long>, sizes: MutableList<Int>) {
            var p = start
            var defaultSize = 0
            var base = moofStart
            while (p + 8 <= end) {
                val size = int(b, p); val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
                if (size < 8) return
                val q = p + 8
                if (type == "tfhd") {
                    val flags = int(b, q) and 0xFFFFFF
                    var o = q + 8
                    if (flags and 0x1 != 0) { base = java.nio.ByteBuffer.wrap(b, o, 8).long; o += 8 }
                    if (flags and 0x2 != 0) o += 4
                    if (flags and 0x8 != 0) o += 4
                    if (flags and 0x10 != 0) defaultSize = int(b, o)
                } else if (type == "trun") {
                    val flags = int(b, q) and 0xFFFFFF
                    val count = int(b, q + 4)
                    var o = q + 8
                    var dataOffset = 0
                    if (flags and 0x1 != 0) { dataOffset = int(b, o); o += 4 }
                    if (flags and 0x4 != 0) o += 4
                    var at = base + dataOffset
                    repeat(count) {
                        if (flags and 0x100 != 0) o += 4
                        val s = if (flags and 0x200 != 0) int(b, o).also { o += 4 } else defaultSize
                        if (flags and 0x400 != 0) o += 4
                        if (flags and 0x800 != 0) o += 4
                        offsets.add(at); sizes.add(s); at += s
                    }
                }
                p += size
            }
        }
    }
}

/** Fallback for ordinary (non-fragmented) m4a: the decoder library's Java Sound reader. */
class JavaSoundSource(private val path: Path) : PcmSource {
    private var stream = openPcm(0)
    override val format: AudioFormat get() = stream.format
    override val durationMs: Long = stream.let { if (it.frameLength > 0 && it.format.frameRate > 0) (it.frameLength / it.format.frameRate * 1000).toLong() else 0L }

    private fun openPcm(skipMs: Long): javax.sound.sampled.AudioInputStream {
        val source = javax.sound.sampled.AudioSystem.getAudioInputStream(path.toFile())
        val pcm = if (source.format.encoding == AudioFormat.Encoding.PCM_SIGNED && source.format.sampleSizeInBits == 16) source else {
            val f = source.format
            javax.sound.sampled.AudioSystem.getAudioInputStream(AudioFormat(AudioFormat.Encoding.PCM_SIGNED, f.sampleRate, 16, f.channels, f.channels * 2, f.sampleRate, false), source)
        }
        var left = (skipMs / 1000.0 * pcm.format.frameRate).toLong() * pcm.format.frameSize
        val sink = ByteArray(16 * 1024)
        while (left > 0) { val n = pcm.read(sink, 0, minOf(left, sink.size.toLong()).toInt()); if (n < 0) break; left -= n }
        return pcm
    }

    override fun read(buf: ByteArray): Int = stream.read(buf, 0, buf.size)
    override fun seek(ms: Long) { runCatching { stream.close() }; stream = openPcm(ms) }
    override fun close() { runCatching { stream.close() } }
}

/** Opens [path] with whichever reader understands it. */
fun openPcm(path: Path): PcmSource = FragmentedAacSource.open(path) ?: JavaSoundSource(path)
