package echopc

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class PlayerState(
    val loading: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

/**
 * Downloads a track's audio into a temp file. Results are cached per videoId so the next song
 * can be fetched while the current one plays.
 */
/** A download that failed after the stream was resolved; remembers which client served it so the retry can use another. */
class StreamFailure(val client: String?, cause: Throwable) : Exception(cause.message ?: cause.toString(), cause)

class AudioLoader(
    /** (videoId, clients to avoid, SABR allowed) -> stream. Replaced in tests. */
    private val resolver: suspend (String, Set<String>, Boolean) -> ResolvedStream =
        { id, skip, sabr -> InnerTubeXStream.resolve(id, requireM4a = true, allowSabr = sabr, skipClients = skip) },
    /** Writes the stream into the file. Replaced in tests. */
    private val downloadOverride: (suspend (ResolvedStream, Path) -> Unit)? = null,
    private val retryDelayMs: Long = 500L,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Deferred<Path>>()
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()

    /** Starts (or reuses) the download for [videoId]. */
    fun load(videoId: String): Deferred<Path> =
        cache.computeIfAbsent(videoId) {
            scope.async {
                var lastError: Throwable? = null
                val skip = mutableSetOf<String>()
                repeat(MAX_ATTEMPTS) { attempt ->
                    try {
                        // 1: normal. 2: avoid the client that just failed. 3-4: no SABR (plain ranged URLs), then any client again.
                        if (attempt == 3) skip.clear()
                        val path = fetch(videoId, skip, allowSabr = attempt < 2)
                        if (attempt > 0) Device.log("Stream for $videoId recovered on attempt ${attempt + 1} (avoiding ${skip.ifEmpty { "nothing" }}).")
                        return@async path
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        lastError = e
                        (e as? StreamFailure)?.client?.let { skip += it }
                        val client = (e as? StreamFailure)?.client ?: "unknown client"
                        val msg = "Stream $videoId attempt ${attempt + 1}/$MAX_ATTEMPTS failed via $client: ${e.message?.take(200)}"
                        println("[loader] $msg")
                        Device.log(msg)
                        kotlinx.coroutines.delay(retryDelayMs * (attempt + 1))
                    }
                }
                cache.remove(videoId)
                Device.log("Stream $videoId gave up after $MAX_ATTEMPTS attempts.")
                throw lastError!!
            }
        }

    /** One full attempt: resolve a fresh stream URL, then download it. */
    private suspend fun fetch(videoId: String, skip: Set<String>, allowSabr: Boolean): Path {
        val t0 = System.currentTimeMillis()
        val stream = resolver(videoId, skip, allowSabr)
        val t1 = System.currentTimeMillis()
        val file = Files.createTempFile("echopc-", if (stream.mimeType.contains("webm")) ".webm" else ".m4a")
        file.toFile().deleteOnExit()
        try {
            val override = downloadOverride
            if (override != null) {
                override(stream, file)
            } else if (stream.sabr != null) {
                val bytes = Files.newOutputStream(file).use { out -> downloadSabrAudio(stream.sabr, out) }
                if (bytes == 0L) error("SABR stream returned no data (via ${stream.client})")
            } else {
                downloadRanged(stream, file)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { Files.deleteIfExists(file) }
            throw e
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(file) }
            throw StreamFailure(stream.client, e)
        }
        println("[loader] $videoId via ${stream.client}: resolve ${t1 - t0} ms, download ${System.currentTimeMillis() - t1} ms")
        return file
    }

    /** Drops cached files except those in [keep]. */
    fun retainOnly(keep: Set<String>) {
        for (id in cache.keys.toList()) {
            if (id in keep) continue
            val d = cache.remove(id) ?: continue
            if (d.isCompleted && !d.isCancelled) runCatching { Files.deleteIfExists(d.getCompleted()) } else d.cancel()
        }
    }

    private fun downloadRanged(stream: ResolvedStream, file: Path) {
        val chunk = 512 * 1024L
        var start = 0L
        var total = Long.MAX_VALUE
        Files.newOutputStream(file).use { out ->
            while (start < total) {
                val builder = HttpRequest.newBuilder(URI.create(stream.url)).header("Range", "bytes=$start-${start + chunk - 1}")
                stream.headers.forEach { (k, v) -> if (!k.equals("Range", true) && !k.equals("Host", true)) builder.setHeader(k, v) }
                val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                val code = response.statusCode()
                if (code == 416) break
                if (code !in 200..299) error("Stream download failed at byte $start: HTTP $code (via ${stream.client})")
                val body = response.body()
                out.write(body)
                start += body.size
                val range = response.headers().firstValue("Content-Range").orElse(null)
                if (range != null) total = range.substringAfter('/').toLongOrNull() ?: total
                else if (code == 200) break
                if (body.isEmpty()) break
            }
        }
        if (start == 0L) error("Empty stream (via ${stream.client})")
    }
}

private const val MAX_ATTEMPTS = 4

/** What the UI needs from a player; lets screenshot/UI tests run without audio. */
interface PlayerEngine {
    fun play(videoId: String, nextVideoId: String?)
    fun prefetch(videoId: String)
    fun togglePause()
    fun seek(ms: Long)
    fun setVolume(v: Double)
    fun stop()
}

/**
 * Desktop audio player (JavaFX Media). Stream URLs come from the shared `innertube`/InnerTubeX
 * code; no external player is needed.
 */
class DesktopPlayer(
    private val onState: (PlayerState) -> Unit,
    private val onEnded: () -> Unit = {},
    private val loader: AudioLoader = AudioLoader(),
    /** Called (instead of showing an error) when JavaFX itself cannot start or open a track. */
    private val onEngineFailure: ((String) -> Unit)? = null,
) : PlayerEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mediaPlayer: MediaPlayer? = null
    private var playJob: Job? = null
    private var generation = 0
    private var state = PlayerState()
    private var volume = 0.8

    // JavaFX (audio) costs memory, threads and a second or two of CPU, so it is only started the first
    // time something is played (in parallel with the stream download), never at app launch.
    private val fxReady = java.util.concurrent.CountDownLatch(1)
    private val fxStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var fxUsable = true

    private fun engineFailed(gen: Int, reason: String) {
        if (gen != generation) return
        val handler = onEngineFailure
        if (handler != null) handler(reason) else update { PlayerState(error = reason) }
    }

    private fun ensureFx() {
        if (!fxStarted.compareAndSet(false, true)) return
        // Audio only: skip creating a Direct3D rendering pipeline we would never use.
        System.setProperty("prism.order", "sw")
        Thread({
            try {
                Platform.startup {
                    Platform.setImplicitExit(false)
                    fxReady.countDown()
                }
            } catch (_: IllegalStateException) {
                Platform.setImplicitExit(false) // toolkit was already running
                fxReady.countDown()
            } catch (e: Throwable) {
                Device.log("JavaFX startup failed: ${e.stackTraceToString().take(600)}")
                fxUsable = false
                fxReady.countDown()
            }
        }, "javafx-init").apply { isDaemon = true; start() }
    }

    private fun update(block: PlayerState.() -> PlayerState) {
        state = state.block()
        onState(state)
    }

    /** Plays [videoId] now and starts fetching [nextVideoId] in the background. */
    override fun play(videoId: String, nextVideoId: String?) {
        ensureFx()
        val gen = ++generation
        playJob?.cancel()
        disposePlayer()
        update { PlayerState(loading = true) }
        loader.retainOnly(setOfNotNull(videoId, nextVideoId))
        val current = loader.load(videoId)
        playJob = scope.launch {
            try {
                val file = current.await()
                if (gen != generation) return@launch
                val ready = withContext(Dispatchers.IO) { fxReady.await(30, java.util.concurrent.TimeUnit.SECONDS) }
                if (!ready || !fxUsable) { engineFailed(gen, "JavaFX toolkit did not start"); return@launch }
                startPlayback(file, gen)
                // Current track is ready: now warm up the next one.
                if (nextVideoId != null && gen == generation) loader.load(nextVideoId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (gen == generation) update { PlayerState(error = e.message ?: e.toString()) }
            }
        }
    }

    private suspend fun startPlayback(file: Path, gen: Int) = suspendCancellableCoroutine<Unit> { cont ->
        Platform.runLater {
            try {
                if (gen != generation) { cont.resume(Unit); return@runLater }
                val player = MediaPlayer(Media(file.toUri().toString()))
                player.volume = volume
                mediaPlayer = player
                player.setOnReady {
                    if (gen != generation) { player.dispose(); return@setOnReady }
                    update { copy(loading = false, playing = true, durationMs = player.totalDuration.toMillis().toLong()) }
                    player.play()
                    if (cont.isActive) cont.resume(Unit)
                }
                player.currentTimeProperty().addListener { _, _, t ->
                    if (gen == generation) {
                        val ms = t.toMillis().toLong()
                        if (kotlin.math.abs(ms - state.positionMs) >= 200) update { copy(positionMs = ms) }
                    }
                }
                player.setOnEndOfMedia {
                    if (gen == generation) {
                        update { copy(playing = false, positionMs = 0) }
                        onEnded()
                    }
                }
                player.setOnError {
                    engineFailed(gen, player.error?.message ?: "Playback error")
                    if (cont.isActive) cont.resume(Unit)
                }
                // If the media engine neither plays nor reports an error (missing codecs), give up after a while.
                scope.launch {
                    kotlinx.coroutines.delay(20_000)
                    if (gen == generation && state.loading) engineFailed(gen, "JavaFX media did not become ready")
                }
            } catch (e: Throwable) {
                Device.log("JavaFX media failed: ${e.stackTraceToString().take(600)}")
                engineFailed(gen, e.message ?: e.toString())
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    override fun prefetch(videoId: String) { loader.load(videoId) }

    override fun togglePause() {
        val p = mediaPlayer ?: return
        Platform.runLater {
            if (state.playing) { p.pause(); update { copy(playing = false) } } else { p.play(); update { copy(playing = true) } }
        }
    }

    override fun seek(ms: Long) {
        val p = mediaPlayer ?: return
        Platform.runLater { p.seek(Duration.millis(ms.toDouble())) }
    }

    override fun setVolume(v: Double) {
        volume = v
        val p = mediaPlayer ?: return
        Platform.runLater { p.volume = v }
    }

    private fun disposePlayer() {
        val p = mediaPlayer
        mediaPlayer = null
        if (p != null) Platform.runLater { runCatching { p.stop(); p.dispose() } }
    }

    /** Stops playback without discarding downloaded files (used when switching engines). */
    fun release() {
        generation++
        playJob?.cancel()
        disposePlayer()
    }

    override fun stop() {
        release()
        loader.retainOnly(emptySet())
        update { PlayerState() }
    }
}


