package echopc

import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** End-to-end check of Controller + real DesktopPlayer (muted): search, play, lyrics, accent, prefetch, history. */
fun main(): Unit = runBlocking {
    val file = File.createTempFile("ws-e2e", ".db").also { it.delete(); it.deleteOnExit() }
    val repo = LibraryRepository(LibraryDatabase.open(file))
    LyricsRepository.store = repo
    val c = Controller(CoroutineScope(SupervisorJob() + Dispatchers.Default), repo, null) { s, e -> DesktopPlayer(s, e) }
    c.changeVolume(0f) // muted: this is a test
    suspend fun until(label: String, timeoutMs: Long = 90_000, cond: () -> Boolean): Long {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < timeoutMs) delay(50)
        val ms = System.currentTimeMillis() - t0
        println("${if (cond()) "OK  " else "FAIL"} $label (${ms} ms)"); return ms
    }
    c.search("daft punk get lucky")
    until("search results") { c.results.isNotEmpty() }
    println("   ${c.results.size} results, first: ${c.results.first().title}")
    val defaultAccent = c.accent.primary
    c.playFromList(c.results, 0)
    until("track 1 playing") { c.playback.playing }
    until("position advances past 4s") { c.positionMs > 4000 }
    until("lyrics loaded") { c.lyrics is LyricsUiState.Loaded || c.lyrics == LyricsUiState.NotFound }
    println("   lyrics: ${(c.lyrics as? LyricsUiState.Loaded)?.let { "${it.provider}, ${it.lines.size} lines, synced=${it.synced}" } ?: c.lyrics}")
    until("accent follows album art") { c.accent.primary != defaultAccent }
    println("   accent: ${c.accent.primary} / ${c.accent.secondary}")
    until("listened 11s", 30_000) { c.positionMs > 11_000 }
    val t = System.currentTimeMillis()
    c.skipNext()
    until("track 2 playing (prefetched)") { c.playback.playing && c.queue.index == 1 && c.positionMs < 3000 }
    println("   skip -> playing in ${System.currentTimeMillis() - t} ms")
    delay(1500)
    val hist = repo.history().first()
    println(if (hist.isNotEmpty()) "OK   history logged: ${hist.first().song.song.title} (${hist.first().event.playTime / 1000}s)" else "FAIL history empty")
    c.togglePause(); delay(500); println("OK   paused=${!c.playback.playing}")
    c.shutdown(); repo.close()
    kotlin.system.exitProcess(0)
}
