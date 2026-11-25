package echopc

import com.music.innertube.models.SongItem
import echopc.db.TrackCodec
import java.io.File

/**
 * The queue as it was when the app closed, so the next launch picks up where you left off
 * (Echo Music calls this "persistent queue"). The song comes back paused at the same spot.
 */
data class SavedQueue(
    val items: List<SongItem>,
    val index: Int,
    val positionMs: Long,
    val durationMs: Long,
    val context: String,
    val recommended: Set<String>,
    val radioIds: Set<String>,
    /** The list the queue was started from, in its own order (for turning shuffle off). */
    val original: List<SongItem>,
)

/** Reads and writes [SavedQueue] as a small text file (`queue.txt` next to the settings). */
class QueueStore(private val file: File) {
    private val lock = Any()

    /** [snapshot] is read under the lock, so a slow earlier save can never overwrite a newer queue with an old one. */
    fun save(snapshot: () -> SavedQueue) = synchronized(lock) {
        runCatching {
            val q = snapshot()
            if (q.items.isEmpty() || q.index !in q.items.indices) { file.delete(); return@runCatching }
            file.parentFile?.mkdirs()
            val text = buildString {
                appendLine(HEADER)
                appendLine("index=${q.index}")
                appendLine("position=${q.positionMs}")
                appendLine("duration=${q.durationMs}")
                appendLine("context=${q.context.replace('\n', ' ')}")
                appendLine("recommended=${q.recommended.joinToString(",")}")
                appendLine("radio=${q.radioIds.joinToString(",")}")
                appendLine(ITEMS)
                appendLine(TrackCodec.encode(q.items))
                appendLine(ORIGINAL)
                appendLine(TrackCodec.encode(q.original))
            }
            // Write a temp file and move it over, so a crash mid-write never leaves a broken queue behind.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            // java.io rather than java.nio.file, which Android only has from version 8. Windows won't rename onto a file.
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "couldn't replace ${file.name}" } }
        }.onFailure { AppHooks.log("Saving the queue failed: ${it.message}") }
    }

    fun load(): SavedQueue? = synchronized(lock) {
        if (!file.isFile) return null
        runCatching {
            val lines = file.readLines()
            if (lines.firstOrNull() != HEADER) return null
            val itemsAt = lines.indexOf(ITEMS)
            val originalAt = lines.indexOf(ORIGINAL)
            if (itemsAt < 0 || originalAt < itemsAt) return null
            val fields = lines.subList(1, itemsAt).associate { it.substringBefore('=') to it.substringAfter('=', "") }
            fun ids(key: String) = fields[key].orEmpty().split(',').filter { it.isNotBlank() }.toSet()
            val items = TrackCodec.decode(lines.subList(itemsAt + 1, originalAt).joinToString("\n"))
            val index = fields["index"]?.toIntOrNull() ?: return null
            if (index !in items.indices) return null
            SavedQueue(
                items = items,
                index = index,
                positionMs = fields["position"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                durationMs = fields["duration"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                context = fields["context"].orEmpty().ifBlank { "home" },
                recommended = ids("recommended"),
                radioIds = ids("radio"),
                original = TrackCodec.decode(lines.drop(originalAt + 1).joinToString("\n")),
            )
        }.onFailure { AppHooks.log("Reading the saved queue failed: ${it.message}") }.getOrNull()
    }

    private companion object {
        const val HEADER = "wsmusic-queue 1"
        const val ITEMS = "[items]"
        const val ORIGINAL = "[original]"
    }
}
