package echopc

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint

enum class RepeatMode { OFF, ALL, ONE }

/** Immutable queue snapshot so Compose can observe it. */
data class QueueState(
    val items: List<SongItem> = emptyList(),
    val index: Int = -1,
    val repeat: RepeatMode = RepeatMode.OFF,
) {
    val current: SongItem? get() = items.getOrNull(index)

    /** What will play after [current] (used for prefetching). */
    val upNext: SongItem?
        get() = when {
            current == null -> null
            repeat == RepeatMode.ONE -> current
            index + 1 < items.size -> items[index + 1]
            repeat == RepeatMode.ALL && items.isNotEmpty() -> items.first()
            else -> null
        }

    fun withItems(newItems: List<SongItem>, startIndex: Int) = copy(items = newItems, index = startIndex)

    /** Appends [song] unless it's already queued. */
    fun add(song: SongItem): QueueState = if (items.any { it.id == song.id }) this else copy(items = items + song)

    /** Inserts [song] right after the current track ("play next"). */
    fun playNext(song: SongItem): QueueState {
        val without = items.filterIndexed { i, s -> s.id != song.id || i == index }
        val cur = without.indexOfFirst { it.id == current?.id }.takeIf { it >= 0 } ?: -1
        return copy(items = without.toMutableList().apply { add(cur + 1, song) }, index = cur)
    }

    fun remove(position: Int): QueueState {
        if (position !in items.indices) return this
        val newItems = items.toMutableList().apply { removeAt(position) }
        val newIndex = when {
            position < index -> index - 1
            else -> index
        }
        return copy(items = newItems, index = newIndex.coerceAtMost(newItems.lastIndex))
    }

    fun jump(position: Int): QueueState = if (position in items.indices) copy(index = position) else this

    /** Index after finishing/skipping forward, or null when the queue is exhausted. */
    fun nextIndex(auto: Boolean): Int? = when {
        items.isEmpty() -> null
        auto && repeat == RepeatMode.ONE -> index
        index + 1 < items.size -> index + 1
        repeat == RepeatMode.ALL -> 0
        else -> null
    }

    fun previousIndex(): Int? = when {
        items.isEmpty() -> null
        index > 0 -> index - 1
        repeat == RepeatMode.ALL -> items.lastIndex
        else -> 0
    }

    /** Shuffles everything after the current track. */
    fun shuffledUpcoming(): QueueState {
        if (index < 0) return copy(items = items.shuffled())
        val head = items.take(index + 1)
        return copy(items = head + items.drop(index + 1).shuffled())
    }
}

/** YouTube Music "radio" for [song]: related songs to keep playing after the queue ends. */
suspend fun fetchRadio(song: SongItem): List<SongItem> =
    YouTube.next(WatchEndpoint(videoId = song.id, playlistId = "RDAMVM${song.id}"))
        .getOrNull()
        ?.items
        .orEmpty()
        .filter { it.id != song.id }
