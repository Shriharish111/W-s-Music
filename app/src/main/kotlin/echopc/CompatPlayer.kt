package echopc

import java.nio.file.Path
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Compatible audio engine: decodes AAC/m4a in pure Java (JAAD, through Java Sound) and plays it on
 * the system mixer. It needs no JavaFX, no Windows Media Foundation and no native libraries, and
 * uses far less memory, so it is the engine on weak PCs and the fallback when JavaFX fails.
 */
class CompatPlayer(
    private val onState: (PlayerState) -> Unit,
    private val onEnded: () -> Unit = {},
    private val loader: AudioLoader = AudioLoader(),
) : PlayerEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var state = PlayerState()
    private var volume = 0.8
    private var generation = 0
    private var playJob: Job? = null
    @Volatile private var paused = false
    @Volatile private var seekToMs = -1L

    private fun update(block: PlayerState.() -> PlayerState) {
        state = state.block()
        onState(state)
    }

    override fun play(videoId: String, nextVideoId: String?) {
        val gen = ++generation
        playJob?.cancel()
        paused = false
        seekToMs = -1
        update { PlayerState(loading = true) }
        loader.retainOnly(setOfNotNull(videoId, nextVideoId))
        val current = loader.load(videoId)
        playJob = scope.launch {
            try {
                val file = current.await()
                if (gen != generation) return@launch
                if (nextVideoId != null) loader.load(nextVideoId)
                decodeAndPlay(file, gen)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Device.log("CompatPlayer failed for $videoId: ${e.stackTraceToString().take(800)}")
                if (gen == generation) update { PlayerState(error = e.message ?: e.toString()) }
            }
        }
    }

    private fun decodeAndPlay(file: Path, gen: Int) {
        val source = openPcm(file)
        val format = source.format
        var startMs = 0L
        var baseUs = 0L
        val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
        line.open(format, 32 * 1024)
        line.start()
        update { copy(loading = false, playing = true, durationMs = source.durationMs) }
        val buf = ByteArray(8 * 1024)
        var lastReport = 0L
        try {
            while (gen == generation) {
                val seek = seekToMs
                if (seek >= 0) {
                    seekToMs = -1
                    line.stop(); line.flush()
                    source.seek(seek)
                    startMs = seek
                    baseUs = line.microsecondPosition
                    line.start()
                    lastReport = -1000
                }
                if (paused) {
                    line.stop()
                    while (paused && gen == generation && seekToMs < 0) Thread.sleep(40)
                    if (gen != generation) break
                    line.start()
                    continue
                }
                val n = source.read(buf)
                if (n < 0) {
                    line.drain()
                    if (gen == generation) {
                        update { copy(playing = false, positionMs = 0) }
                        onEnded()
                    }
                    break
                }
                applyVolume(buf, n)
                var off = 0
                while (off < n && gen == generation && seekToMs < 0 && !paused) off += line.write(buf, off, n - off)
                val pos = startMs + (line.microsecondPosition - baseUs) / 1000
                if (kotlin.math.abs(pos - lastReport) >= 250) { lastReport = pos; update { copy(positionMs = pos) } }
            }
        } finally {
            runCatching { line.stop(); line.close() }
            source.close()
        }
    }

    /** Scales 16-bit little-endian samples in place. */
    private fun applyVolume(buf: ByteArray, n: Int) {
        val v = volume
        if (v >= 0.999) return
        var i = 0
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            val o = (s * v).toInt()
            buf[i] = o.toByte()
            buf[i + 1] = (o shr 8).toByte()
            i += 2
        }
    }

    override fun prefetch(videoId: String) { loader.load(videoId) }

    override fun togglePause() {
        paused = !paused
        update { copy(playing = !paused) }
    }

    override fun seek(ms: Long) {
        seekToMs = ms.coerceAtLeast(0)
        update { copy(positionMs = ms) }
    }

    override fun setVolume(v: Double) { volume = v.coerceIn(0.0, 1.0) }

    /** Stops playback without discarding downloaded files. */
    fun release() {
        generation++
        playJob?.cancel()
    }

    override fun stop() {
        release()
        loader.retainOnly(emptySet())
        update { PlayerState() }
    }
}

/**
 * Chooses the audio engine for this PC: JavaFX on capable machines, the pure-Java engine on weak ones.
 * If JavaFX fails to start or open a track, it switches to the compatible engine for good and replays
 * the current song, so music keeps playing instead of showing an error.
 */
class AdaptivePlayer(
    private val onState: (PlayerState) -> Unit,
    private val onEnded: () -> Unit = {},
) : PlayerEngine {
    private val loader = AudioLoader()
    private var volume = 0.8
    private var lastId: String? = null
    private var lastNext: String? = null
    private val compat by lazy { CompatPlayer(onState, onEnded, loader).also { it.setVolume(volume) } }
    private val fx by lazy { DesktopPlayer(onState, onEnded, loader, ::fxFailed).also { it.setVolume(volume) } }

    private fun fxFailed(reason: String) {
        Device.useCompatAudio(reason)
        fx.release()
        val id = lastId ?: return
        compat.setVolume(volume)
        compat.play(id, lastNext)
    }

    private val engine: PlayerEngine get() = if (Device.audioCompat) compat else fx

    override fun play(videoId: String, nextVideoId: String?) {
        lastId = videoId; lastNext = nextVideoId
        engine.play(videoId, nextVideoId)
    }
    override fun prefetch(videoId: String) = engine.prefetch(videoId)
    override fun togglePause() = engine.togglePause()
    override fun seek(ms: Long) = engine.seek(ms)
    override fun setVolume(v: Double) { volume = v; engine.setVolume(v) }
    override fun stop() = engine.stop()
}
