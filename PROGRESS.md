# W's MUSIC - progress / handoff notes

Last updated: 2026-10-15. This file lets anyone (or another Claude session / account) continue the work.

## What exists and works
- Desktop app (Windows) in Kotlin + Compose Desktop, built from the Android app **Echo Music** (GPL-3.0).
- **Home** page (quick tiles, "Made for you" mixes, YouTube Music shelves, new releases, mood & genre browse,
  playlist/album pages with "Save to my playlists"), **Search**, **Library** (likes / songs / playlists / history),
  queue with prefetch + autoplay radio, synced lyrics (word-by-word, romanize), accent colour from album art.
- **Android backup import** (`.backup` zip or `song.db`): likes, library songs, playlists, history.
- Local data in `%APPDATA%\WsMusic` (Room/SQLite). Settings: volume, autoplay, repeat, panel.
- Packaged for friends as a standalone Windows folder (`gradlew :app:createDistributable`).

## Spotify-style redesign (branch `spotify-redesign`, 2026-10-15)
Plan: `D:\WMUSIC\PLAN.md`; client research notes: `D:\WMUSIC\_research\SPOTIFY_NOTES.md`.
- **Phase 2 UI (done):** three-panel shell (`Shell.kt`, `LibrarySidebar.kt`, `NowPlayingView.kt`, `PlayerBar.kt`),
  shared collection pages / track table (`Tracks.kt`), back/forward `Route` stack in `Controller`, Figtree font.
- **Phase 3 engine (done):** DB schema v2 (`MIGRATION_1_2` in `LibraryDatabase.kt`) adds `listen_signal`, `feedback`,
  `generated_mix`, `related_cache`, `shelf_stat`. Every play logs start/end reason + ms played (`Controller.flushPlay`).
  `reco/`: `TasteProfile` (7/180-day decay, skips negative, likes +3), `MixEngine` (Daily Mix clusters via label
  propagation, Discover Weekly co-occurrence, Release Radar, On Repeat, Repeat Rewind), `ShelfBandit` (Thompson
  sampling for Home shelf order), `Recommender` (schedule: daily / Monday / Friday, cached YouTube radio).
  Smoke: `gradlew :app:smoke -Pmain=echopc.RecoSmokeKt` builds real mixes in a temp DB (~25 s).
- **Phase 4 (next):** Smart Shuffle and feedback menu items (hide song, not interested, ban artist, exclude from taste).

## Stream playback (important background)
YouTube refuses anonymous stream URLs for most clients. What works today: InnerTubeX (`innertubex` 0.7.2)
with **SABR** audio (`allowSabr = true`) via `InnerTubeXStream.kt` + `downloadSabrAudio`; the whole AAC file is
downloaded, then played with JavaFX Media. The next queue item is prefetched. Forcing IPv4
(`-Djava.net.preferIPv4Stack=true`) fixed connection timeouts. If playback breaks, YouTube probably changed
something: start from `InnerTubeXStream.kt`.

## Performance work (latest session) - measured on a busy 4-core / 8 GB PC
| | before | after |
|---|---|---|
| Window ready (warm launch) | 8.1 s | 4.2 s |
| Idle CPU / private memory | 0.4 % core / 414 MB | 0.1 % core / 259 MB |
| Playing CPU / private memory | 52.8 % core / 367 MB | 11.8 % core / 272 MB |
| Threads (idle / playing) | 60 / 92 | 44 / 67 |

What changed: SerialGC + small heap (`-Xms24m -Xmx512m`), C1-only JIT, OpenGL render API, IPv4;
all continuous animations, spring/scale effects and blur shadows removed (`Widgets.kt`);
accent colour changes instantly; thumbnails requested at real on-screen pixel size with a 40 MB LRU
(`Thumbs.kt`); JavaFX audio toolkit starts on first play only (`Player.kt`, software prism);
playback position published at most 5x/second; home feed loads in parallel, stream warm-up delayed 4 s.
Benchmark mode: run the exe with env `WSMUSIC_BENCH=1` (temporary DB, muted autoplay, no settings saved)
and `WSMUSIC_TRACE=1` (writes `%APPDATA%\WsMusic\startup.log`).

## Ideas not done
- **Android version** of W's MUSIC. The Android app already exists (original Echo Music, `echo.music.iad1tya`).
  Plan was: rebrand it (name, icon, applicationId) and build `assembleUniversalFossDebug`.
  Needs Android SDK (compileSdk 37, NDK 28.2), Gradle 9.3.1 (wrapper), JDK 21, ~4+ GB heap; the dev PC has 8 GB RAM,
  so building there will be slow. Not started.
- Start playing before the whole file is downloaded (needs a custom decoder; JavaFX cannot play a growing file).
- AppCDS class archive for faster startup (tried once, archive was never produced; unproven).
- Sign-in (cookies) for a personalised home feed and YouTube sync of likes.
- Repo hygiene: `app/src/main/kotlin/echopc/StreamResolver.kt` is an intentionally empty stub (safe to delete).

## How to build / test
```
gradlew.bat :app:run
gradlew.bat :app:test                  # importer, UI flows, screenshots -> app/build/shots
gradlew.bat :app:createDistributable   # standalone app in app/build/compose/binaries/main/app/WsMusic
```
Tests: `ImporterTest`, `UiFlowTest`, `ScreenshotTest`, `HomeShotTest` (8 tests, all passing at last run).
Gotcha: Windows 260-char path limit - build in a short path (e.g. `C:\dev\wsmusic`) if copying fails.
