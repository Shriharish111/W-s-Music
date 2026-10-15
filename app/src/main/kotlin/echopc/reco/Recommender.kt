package echopc.reco

import com.music.innertube.models.SongItem
import echopc.db.GeneratedMix
import echopc.db.LibraryRepository
import echopc.db.TrackCodec
import echopc.db.artistKey
import echopc.db.toSongItem
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stored timestamps are local wall-clock values saved as UTC epoch millis (see Converters). */
internal fun LocalDateTime.millis(): Long = atZone(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * Keeps the generated mixes fresh, on Spotify's schedule: Daily Mixes, On Repeat and Repeat Rewind
 * every day (or sooner after 20 new plays), Discover Weekly on Mondays, Release Radar on Fridays.
 */
class Recommender(
    private val library: LibraryRepository,
    private val source: CandidateSource,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    private val mutex = Mutex()
    /** Kinds that produced nothing today (too little history yet): don't retry until tomorrow. */
    private val emptyAttempts = HashMap<String, LocalDateTime>()

    val kinds = listOf(MixKind.DISCOVER, MixKind.RADAR, MixKind.DAILY, MixKind.ON_REPEAT, MixKind.REWIND)

    /** Rebuilds stale mixes (all of them with [force]). Returns the kinds that were rebuilt. */
    suspend fun refresh(force: Boolean = false): List<String> = mutex.withLock {
        val now = clock()
        val existing = library.mixesNow().groupBy { it.kind }
        val stale = kinds.filter { force || isStale(it, existing[it].orEmpty(), now) }
        if (stale.isEmpty()) return emptyList()

        val inputs = loadInputs(now)
        val engine = MixEngine(source, inputs.meta)
        val profile = inputs.profile
        for (kind in stale) {
            val drafts = when (kind) {
                MixKind.DAILY -> engine.dailyMixes(profile)
                MixKind.DISCOVER -> listOfNotNull(engine.discoverWeekly(profile, inputs.recentSeeds))
                MixKind.RADAR -> listOfNotNull(engine.releaseRadar(profile, profile.topArtists(30).filter { it.startsWith("UC") }.take(12)))
                MixKind.ON_REPEAT -> listOfNotNull(engine.onRepeat(inputs.plays, profile, now.millis()))
                MixKind.REWIND -> listOfNotNull(engine.repeatRewind(profile, now.millis()))
                else -> emptyList()
            }
            if (drafts.isEmpty()) emptyAttempts[kind] = now else emptyAttempts.remove(kind)
            library.replaceMixes(kind, drafts.mapIndexed { i, d -> d.toEntity(positionOf(kind, i), now) })
        }
        library.pruneRelated(olderThanDays = 30)
        stale
    }

    internal suspend fun isStale(kind: String, mixes: List<GeneratedMix>, now: LocalDateTime): Boolean {
        if (mixes.isEmpty()) return emptyAttempts[kind]?.toLocalDate() != now.toLocalDate()
        val at = mixes.minOf { it.generatedAt }
        return when (kind) {
            MixKind.DISCOVER -> at < now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
            MixKind.RADAR -> at < now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY)).atStartOfDay()
            MixKind.REWIND -> at.toLocalDate() != now.toLocalDate()
            else -> at.toLocalDate() != now.toLocalDate() || library.countSignalsAfter(at) >= 20
        }
    }

    /** Home order: Discover Weekly, Release Radar, Daily Mix 1–6, On Repeat, Repeat Rewind. */
    private fun positionOf(kind: String, i: Int) = when (kind) {
        MixKind.DISCOVER -> 0
        MixKind.RADAR -> 1
        MixKind.DAILY -> 2 + i
        MixKind.ON_REPEAT -> 20
        else -> 21
    }

    private fun MixDraft.toEntity(position: Int, now: LocalDateTime) =
        GeneratedMix(id, kind, title, subtitle, TrackCodec.encode(songs), position, now)

    // ---------------- inputs ----------------

    class Inputs(val plays: List<Play>, val profile: TasteProfile, val meta: Map<String, SongItem>, val recentSeeds: List<SongItem>)

    suspend fun loadInputs(now: LocalDateTime): Inputs {
        val signals = library.signalsSince(now.minusDays(365))
        val plays = signals.map { Play(it.songId, it.artistKey, it.artistName, it.timestamp.millis(), it.playedMs, it.durationMs, it.endReason) }
        val likedRows = library.likedSongs.first()
        val liked = likedRows.map { s ->
            val item = s.toSongItem()
            Saved(item.id, item.artistKey(), item.artists.firstOrNull()?.name.orEmpty(), s.song.likedDate?.millis())
        }
        val playlistIds = library.songIdsInPlaylists() - liked.map { it.songId }.toSet()
        val meta = library.songsById(plays.map { it.songId } + liked.map { it.songId } + playlistIds) + likedRows.associate { it.song.id to it.toSongItem() }
        val inPlaylists = playlistIds.mapNotNull { id -> meta[id]?.let { Saved(id, it.artistKey(), it.artists.firstOrNull()?.name.orEmpty(), null) } }
        val profile = TasteProfile.build(plays, liked, inPlaylists, library.feedbackNow(), now.millis())

        // Discover Weekly seeds: what the user has loved over the last four weeks, else their likes.
        val since = now.minusDays(28).millis()
        val recent = profile.tracks.values.filter { it.lastPlayed >= since && it.shortScore > 0 && it.songId !in profile.excluded }
            .sortedByDescending { it.shortScore }.mapNotNull { meta[it.songId] }
        val seeds = (recent + liked.mapNotNull { meta[it.songId] }).distinctBy { it.id }.take(15)
        return Inputs(plays, profile, meta, seeds)
    }
}
