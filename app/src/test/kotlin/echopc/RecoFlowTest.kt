package echopc

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import echopc.db.EndReason
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import echopc.db.StartReason
import echopc.reco.CandidateSource
import echopc.reco.MixKind
import echopc.reco.Recommender
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Database migration, signal recording through the Controller, and mix generation end to end. */
class RecoFlowTest {
    private fun tmp() = File.createTempFile("ws-reco", ".db").also { it.delete(); it.deleteOnExit() }
    private fun s(id: String, artist: String) = SongItem(id = id, title = "Song $id", artists = listOf(Artist(artist, "UC_$artist")), duration = 200, thumbnail = "")

    private class Source(val related: Map<String, List<SongItem>>) : CandidateSource {
        override suspend fun related(seed: SongItem) = related[seed.id].orEmpty()
        override suspend fun recentReleases(artistId: String) = emptyList<SongItem>()
    }

    @Test
    fun v1DatabaseMigratesAndKeepsData() = runBlocking {
        val file = tmp()
        LibraryDatabase.open(file).let { db -> LibraryRepository(db).toggleLike(s("keep", "Alpha")); db.close() }
        // Turn it back into a v1 file: no recommendation tables, schema version 1.
        BundledSQLiteDriver().open(file.absolutePath).let { conn ->
            listOf("listen_signal", "feedback", "generated_mix", "related_cache", "shelf_stat").forEach { conn.execSQL("DROP TABLE `$it`") }
            conn.execSQL("PRAGMA user_version = 1")
            conn.close()
        }
        val repo = LibraryRepository(LibraryDatabase.open(file))
        assertEquals(listOf("keep"), repo.likedSongs.first().map { it.song.id }) // migration ran and Room's schema check passed
        repo.recordSignal(s("x", "Beta"), 1000, 200_000, StartReason.CLICK_ROW, EndReason.FWD_BUTTON, "search")
        assertEquals(1, repo.signalsSince(LocalDateTime.now().minusDays(1)).size)
        repo.close()
    }

    @Test
    fun controllerRecordsWhyPlaysStartAndEnd() = runBlocking {
        val repo = LibraryRepository(LibraryDatabase.open(tmp()))
        lateinit var state: (PlayerState) -> Unit
        lateinit var ended: () -> Unit
        val c = Controller(CoroutineScope(SupervisorJob() + Dispatchers.Default), repo, null, recoSource = Source(emptyMap())) { st, en -> state = st; ended = en; FakeEngine() }
        c.autoplay = false
        val songs = listOf(s("a", "Alpha"), s("b", "Beta"), s("c", "Gamma"))

        c.navigate(Route.Liked)
        c.playFromList(songs, 0) // from the Liked Songs page
        state(PlayerState(playing = true, positionMs = 200_000, durationMs = 200_000)); ended() // a: finished
        state(PlayerState(playing = true, positionMs = 4_000, durationMs = 200_000)); c.skipNext() // b: skipped at 4 s

        val signals = withTimeout(8_000) {
            while (repo.signalsSince(LocalDateTime.now().minusDays(1)).size < 2) delay(50)
            repo.signalsSince(LocalDateTime.now().minusDays(1))
        }
        assertEquals(listOf("a", "b"), signals.map { it.songId })
        assertEquals(StartReason.CLICK_ROW to EndReason.TRACK_DONE, signals[0].startReason to signals[0].endReason)
        assertEquals(StartReason.TRACK_DONE to EndReason.FWD_BUTTON, signals[1].startReason to signals[1].endReason)
        assertEquals(listOf("liked", "liked"), signals.map { it.context })
        assertEquals(listOf(200_000L, 4_000L), signals.map { it.playedMs })
        // History keeps only real listens (10 s+); the 4 s skip is a taste signal only.
        assertEquals(listOf("a"), repo.history().first().map { it.song.song.id })
        repo.close()
    }

    @Test
    fun recommenderBuildsAndStoresMixes() = runBlocking {
        val repo = LibraryRepository(LibraryDatabase.open(tmp()))
        val mine = List(6) { s("al$it", "Alpha") } + List(6) { s("be$it", "Beta") }
        val fresh = List(40) { s("new$it", "Fresh${it % 12}") }
        for ((i, song) in mine.withIndex()) repeat(2 + i % 3) { repo.recordSignal(song, 200_000, 200_000, StartReason.CLICK_ROW, EndReason.TRACK_DONE, "liked") }
        repo.recordSignal(fresh[0], 3_000, 200_000, StartReason.CLICK_ROW, EndReason.FWD_BUTTON, "search") // tried and skipped

        val related = mine.associate { it.id to (mine.shuffled().take(3) + fresh.shuffled()) }
        val recommender = Recommender(repo, Source(related))
        val rebuilt = recommender.refresh(force = true)
        assertTrue(MixKind.DAILY in rebuilt && MixKind.DISCOVER in rebuilt)

        val mixes = repo.mixesNow()
        val discover = mixes.single { it.kind == MixKind.DISCOVER }
        val ids = echopc.db.TrackCodec.decode(discover.songs).map { it.id }
        assertTrue(ids.size >= 10 && ids.none { it.startsWith("al") || it.startsWith("be") }, "Discover Weekly = unplayed songs: $ids")
        assertTrue("new0" !in ids, "a song the user skipped counts as heard")
        assertTrue(mixes.any { it.kind == MixKind.DAILY && it.title == "Daily Mix 1" })
        assertEquals(mixes.sortedBy { it.position }.first().kind, MixKind.DISCOVER)
        // Not stale right after building: a second refresh rebuilds nothing.
        assertEquals(emptyList(), recommender.refresh())
        repo.close()
    }
}
