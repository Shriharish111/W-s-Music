package echopc.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

// Desktop-only tables for the recommendation engine (schema v2). They are not part of the Android
// app's schema, so the Android importer never touches them.

/** Why a play started (mirrors Spotify's `reason_start`). */
object StartReason {
    const val CLICK_ROW = "clickrow" // picked from a list
    const val TRACK_DONE = "trackdone" // previous track finished
    const val FWD_BUTTON = "fwdbtn" // previous track skipped with Next
    const val BACK_BUTTON = "backbtn"
    const val AUTOPLAY = "autoplay" // queue ran out, radio continued
    const val SMART_SHUFFLE = "smartshuffle"
}

/** Why a play ended (mirrors Spotify's `reason_end`). */
object EndReason {
    const val TRACK_DONE = "trackdone"
    const val FWD_BUTTON = "fwdbtn" // skipped forward
    const val BACK_BUTTON = "backbtn"
    const val NEW_SELECTION = "clickrow" // something else was picked
    const val CLOSED = "endplay" // queue cleared / app closed
    const val ERROR = "trackerror"
}

/**
 * One play of a song, however short: the raw input of the taste profile. Unlike `event` (history),
 * short plays and skips are kept, because a quick skip is a strong "not this" signal.
 */
@Entity(tableName = "listen_signal", indices = [Index("songId"), Index("timestamp")])
data class ListenSignal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    /** Primary artist: YouTube channel id, or "name:<lowercase name>" when there is none. */
    val artistKey: String,
    val artistName: String,
    val timestamp: LocalDateTime,
    val playedMs: Long,
    val durationMs: Long,
    val startReason: String,
    val endReason: String,
    /** Where it was played from, e.g. "liked", "local:<id>", "playlist:<id>", "mix:daily-1", "search", "radio". */
    val context: String,
)

/** Explicit feedback. [targetId] is a song id, or an artist key for [Kind.BAN_ARTIST]. */
@Entity(tableName = "feedback", primaryKeys = ["targetId", "kind", "context"])
data class Feedback(
    val targetId: String,
    val kind: String,
    /** Only for [Kind.HIDE_IN_CONTEXT] (the playlist/mix it is hidden from); "" otherwise. */
    val context: String,
    val timestamp: LocalDateTime,
) {
    object Kind {
        const val NOT_INTERESTED = "not_interested" // song: never recommend, strong negative
        const val HIDE_IN_CONTEXT = "hide" // song: skip it in one playlist / mix
        const val BAN_ARTIST = "ban_artist" // artist: never play or recommend
        const val EXCLUDE_FROM_TASTE = "exclude_taste" // song: listening barely counts toward taste
    }
}

/** A generated mix (Daily Mix, Discover Weekly, ...). [songs] uses [TrackCodec]. */
@Entity(tableName = "generated_mix")
data class GeneratedMix(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val songs: String,
    val position: Int,
    val generatedAt: LocalDateTime,
)

/** Cached related songs for a seed song (YouTube Music radio), [songs] uses [TrackCodec]. */
@Entity(tableName = "related_cache")
data class RelatedCache(
    @PrimaryKey val seedId: String,
    val songs: String,
    val fetchedAt: LocalDateTime,
)

/** Home shelf statistics for the shelf-ordering bandit. */
@Entity(tableName = "shelf_stat")
data class ShelfStat(
    @PrimaryKey val shelfKey: String,
    val impressions: Long,
    val clicks: Long,
)
