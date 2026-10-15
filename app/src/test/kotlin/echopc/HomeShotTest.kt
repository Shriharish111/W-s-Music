package echopc

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.music.innertube.YouTube
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class HomeShotTest {
    private val out = File("build/shots").also { it.mkdirs() }

    @Test
    fun renderHome() = runDesktopComposeUiTest(width = 1340, height = 1700, testTimeout = 6.minutes) {
        val repo = LibraryRepository(LibraryDatabase.open(File.createTempFile("ws-home", ".db").also { it.delete(); it.deleteOnExit() }))
        val c = Controller(CoroutineScope(SupervisorJob() + Dispatchers.Default), repo, null) { _, _ -> FakeEngine() }
        runBlocking {
            YouTube.visitorData().onSuccess { YouTube.visitorData = it }
            val songs = YouTube.search("synthwave gaming", YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.filterIsInstance<SongItem>().take(8)
            songs.take(4).forEach { repo.toggleLike(it) }
            val p = repo.createPlaylist("Late night drive"); songs.take(3).forEach { repo.addToPlaylist(p.id, it) }
            repo.createPlaylist("Gym grind")
            songs.take(5).forEachIndexed { i, s -> repo.recordPlay(s, 60_000L + i) }
            // Listening signals, then a real recommender run, so "Made for you" shows generated mixes.
            songs.forEachIndexed { i, s -> repeat(3 - i % 3) { repo.recordSignal(s, 200_000, 200_000, echopc.db.StartReason.CLICK_ROW, echopc.db.EndReason.TRACK_DONE, "search") } }
            c.recommender.refresh(force = true)
        }
        // Real feed data, loaded through the same controller code the app uses.
        c.loadHome()
        val t0 = System.currentTimeMillis()
        while ((c.homeLoading || c.homeSections.isEmpty() || c.moods.isEmpty()) && System.currentTimeMillis() - t0 < 60_000) Thread.sleep(200)
        println("HOME LOADED: ${c.homeSections.size} sections, ${c.homeChips.size} chips, ${c.newReleases.size} new releases, ${c.moods.size} moods, more=${c.homeMore != null}")
        c.panelOpen = false

        setContent { WsTheme(c.accent) { AppContent(c, onPickAndroidFile = {}) } }

        fun shot(name: String) {
            Thread.sleep(3500); waitForIdle()
            val bmp = onRoot().captureToImage().asSkiaBitmap()
            File(out, "$name.png").writeBytes(org.jetbrains.skia.Image.makeFromBitmap(bmp).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            println("SHOT $name ${bmp.width}x${bmp.height}")
        }
        shot("h1-home")

        // A generated mix page.
        val t2 = System.currentTimeMillis()
        while (c.mixes.isEmpty() && System.currentTimeMillis() - t2 < 10_000) Thread.sleep(200)
        println("MIXES: ${c.mixes.map { "${it.title} (${c.mixSongs(it).size})" }}")
        c.mixes.firstOrNull { it.kind == echopc.reco.MixKind.DAILY }?.let { c.navigate(Route.Mix(it.id)); shot("h3-mix") }
        c.goHome()

        // Playlist detail page with real songs.
        val playlist = c.homeSections.flatMap { it.items }.filterIsInstance<PlaylistItem>().first()
        c.openPlaylist(playlist)
        val t1 = System.currentTimeMillis()
        fun detail() = (c.route as? Route.Online)?.let { c.details[it.key] }
        while (detail() !is DetailUi.Songs && System.currentTimeMillis() - t1 < 40_000) Thread.sleep(200)
        println("DETAIL: ${(detail() as? DetailUi.Songs)?.let { "${it.title} / ${it.songs.size} songs" } ?: detail()}")
        shot("h2-detail")
        repo.close()
    }
}
