package echopc.reco

import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import echopc.db.EndReason

internal const val DAY = 86_400_000L
internal const val NOW = 1_800_000_000_000L

/** A song by [artist] (artist id "UC_<artist>"), so artist keys are predictable. */
internal fun song(id: String, artist: String) = SongItem(id = id, title = "Song $id", artists = listOf(Artist(artist, "UC_$artist")), duration = 200, thumbnail = "")

internal fun full(s: SongItem, daysAgo: Double = 1.0) = Play(s.id, "UC_${s.artists[0].name}", s.artists[0].name, NOW - (daysAgo * DAY).toLong(), 200_000, 200_000, EndReason.TRACK_DONE)
internal fun skip(s: SongItem, daysAgo: Double = 1.0) = Play(s.id, "UC_${s.artists[0].name}", s.artists[0].name, NOW - (daysAgo * DAY).toLong(), 5_000, 200_000, EndReason.FWD_BUTTON)

/** Candidate source with fixed answers; counts calls so tests can check caching/limits. */
internal class FakeSource(private val related: Map<String, List<SongItem>>, private val releases: Map<String, List<SongItem>> = emptyMap()) : CandidateSource {
    var relatedCalls = 0
    override suspend fun related(seed: SongItem): List<SongItem> { relatedCalls++; return related[seed.id].orEmpty() }
    override suspend fun recentReleases(artistId: String): List<SongItem> = releases[artistId].orEmpty()
}
