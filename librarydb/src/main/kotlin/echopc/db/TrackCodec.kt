package echopc.db

import com.music.innertube.models.Album
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem

/**
 * Compact text form of a list of songs for cache/mix tables: one song per line, tab-separated
 * fields, artists joined with U+001F. Storing candidates this way keeps hundreds of radio results
 * out of the `song` table (which is the user's library).
 */
object TrackCodec {
    private const val SEP = '\t'
    private const val ARTIST_SEP = '\u001F'
    private const val ID_SEP = '\u001E'

    private fun clean(s: String?) = s.orEmpty().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        .replace(ARTIST_SEP, ' ').replace(ID_SEP, ' ')

    fun encode(songs: List<SongItem>): String = songs.joinToString("\n") { s ->
        listOf(
            clean(s.id),
            clean(s.title),
            s.artists.joinToString(ARTIST_SEP.toString()) { a -> clean(a.name) + ID_SEP + clean(a.id) },
            clean(s.album?.name),
            clean(s.album?.id),
            (s.duration ?: -1).toString(),
            clean(s.thumbnail),
            if (s.explicit) "1" else "0",
        ).joinToString(SEP.toString())
    }

    fun decode(text: String): List<SongItem> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
        val f = line.split(SEP)
        if (f.size < 8 || f[0].isBlank()) return@mapNotNull null
        SongItem(
            id = f[0],
            title = f[1],
            artists = f[2].split(ARTIST_SEP).filter { it.isNotEmpty() }.map { a ->
                val name = a.substringBefore(ID_SEP)
                Artist(name, a.substringAfter(ID_SEP, "").ifBlank { null })
            },
            album = if (f[4].isNotBlank()) Album(f[3], f[4]) else null,
            duration = f[5].toIntOrNull()?.takeIf { it >= 0 },
            thumbnail = f[6],
            explicit = f[7] == "1",
        )
    }.toList()
}

/** Stable artist key for a song: its first artist's channel id, else its lowercased name. */
fun SongItem.artistKey(): String {
    val a = artists.firstOrNull() ?: return "name:unknown"
    return a.id?.takeIf { it.isNotBlank() && !it.startsWith("LA") } ?: "name:${a.name.trim().lowercase()}"
}
