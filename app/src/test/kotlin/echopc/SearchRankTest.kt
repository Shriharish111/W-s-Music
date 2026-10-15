package echopc

import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import com.music.innertube.pages.SearchSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/** The song that was typed comes first, using real results YouTube Music returned for these searches. */
class SearchRankTest {
    private fun song(id: String, title: String, artist: String, video: Boolean = false) = SongItem(
        id = id, title = title, artists = listOf(Artist(artist, null)), thumbnail = "",
        musicVideoType = if (video) "MUSIC_VIDEO_TYPE_OMV" else "MUSIC_VIDEO_TYPE_ATV",
    )

    @Test
    fun theOriginalSongBeatsAVideoUpload() {
        val video = song("v", "drop dead", "Olivia Rodrigo", video = true)
        val studio = song("s", "drop dead", "Olivia Rodrigo")
        assertEquals("s", SearchRank.rank("drop dead olivia rodrigo", listOf(video, studio)).first().id)
    }

    @Test
    fun exactTitleBeatsOfficialVideoAndRemixes() {
        val list = listOf(
            song("ov", "Blinding Lights (Official Video)", "The Weeknd", video = true),
            song("rx", "Blinding Lights (Chromatics remix)", "The Weeknd"),
            song("ok", "Blinding Lights", "The Weeknd"),
        )
        assertEquals(listOf("ok", "ov", "rx"), SearchRank.rank("blinding lights", list).map { it.id })
    }

    @Test
    fun coversAndKaraokeSinkUnlessAskedFor() {
        val list = listOf(
            song("k", "drop dead (By Olivia Rodrigo) (Instrumental Karaoke Version)", "ZZang KARAOKE"),
            song("o", "drop dead", "Olivia Rodrigo"),
        )
        assertEquals("o", SearchRank.rank("drop dead", list).first().id)
        assertEquals("k", SearchRank.rank("drop dead karaoke instrumental", list).first().id)
    }

    @Test
    fun aSimilarTitleDoesNotBeatTheExactOne() {
        val list = listOf(
            song("a", "Kadhal Sadugudu", "Samyu Mohan"),
            song("b", "Kadhal Sadugudu - Chill Mix", "Alvin Bruno"),
            song("c", "Sungadi Shellai", "Mano"),
        )
        assertEquals("a", SearchRank.rank("kadhal sadugudu", list).first().id)
    }

    @Test
    fun theTopResultCardShowsTheBestVersionOfTheSong() {
        val video = song("v", "drop dead", "Olivia Rodrigo", video = true)
        val studio = song("s", "drop dead", "Olivia Rodrigo")
        val cover = song("c", "Drop Dead (Acoustic Cover)", "Zoe Blake")
        val ranked = SearchRank.apply(
            "drop dead olivia rodrigo",
            listOf(SearchSummary("Top result", listOf(video, cover)), SearchSummary("Songs", listOf(cover, studio))),
        )
        assertEquals("s", ranked.summaries[0].items.first().id)
        assertEquals("s", ranked.summaries[1].items.first().id)
        assertEquals("s", ranked.songs.first().id)
    }

    @Test
    fun accentsAndPunctuationDoNotMatter() {
        val list = listOf(song("x", "Other Thing", "Nobody"), song("y", "Beyoncé - Halo!", "Beyoncé"))
        assertEquals("y", SearchRank.rank("beyonce halo", list).first().id)
    }
}
