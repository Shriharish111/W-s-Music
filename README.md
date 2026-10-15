# W's MUSIC

A sleek, dark "gaming" desktop music player for Windows, built with **Kotlin + Compose Desktop**.
It is a desktop port of the Android app **Echo Music** (GPL-3.0) and talks to YouTube Music the
same way that app does.

> Unofficial client. It can break when YouTube changes something, and it goes against YouTube's
> terms of service. Use it for personal use.

## Features
- **Home** front page: quick tiles, "Made for you" mixes built from what you play, curated shelves,
  new releases, mood & genre browsing, playlist / album pages with "Save to my playlists"
- **Search**, **queue** (shuffle, repeat, autoplay similar songs) and gapless-feeling playback
  (the next song is pre-loaded)
- **Synced lyrics** (word-by-word karaoke highlight, romanization, click a line to seek)
- **Library**: likes, playlists, play history, stored locally in SQLite (Room)
- **Import from the Android app**: likes, playlists and history from its `.backup` / `song.db`
- Accent colour follows the current album art
- New users: pick artists you like (or skip) and get your first mixes straight away; until then Home shows YouTube Music's popular songs
- Keyboard shortcuts for playback, volume, shuffle, repeat, search and navigation (press Ctrl+/ for the list); hover a button for its name and shortcut; right-click a song for its options

## Build and run
You need **JDK 21** (for example Temurin 21) and an internet connection. Nothing else.

```bash
./gradlew :app:run            # Windows: gradlew.bat :app:run
./gradlew :app:test           # unit + UI tests (also renders screenshots to app/build/shots)
./gradlew :app:createDistributable   # standalone Windows app folder in app/build/compose/binaries
```

The first build downloads dependencies (several minutes). Some come from JitPack.

## Project layout
| Module | What it is |
|---|---|
| `app` | The Compose Desktop app: `Controller.kt` (all state/logic), `Screens.kt`, `HomeScreen.kt`, `Widgets.kt`, `Theme.kt`, `Player.kt` (JavaFX audio), `InnerTubeXStream.kt` (stream resolving) |
| `librarydb` | Room database, entities, DAO, repository and the Android-backup importer |
| `innertube` | YouTube Music API client (from Echo Music) |
| `lrclib`, `kugou`, `betterlyrics`, `simpmusic`, `youlyplus` | Lyrics providers (from Echo Music) |
| `metadata` | Title/artist cleaning helpers |

Useful places to start changing things:
- Colours, fonts: `app/src/main/kotlin/echopc/Theme.kt`
- Layout and screens: `Screens.kt` and `HomeScreen.kt`
- Buttons, sliders, animations: `Widgets.kt`
- App behaviour (queue, search, home feed, import): `Controller.kt`
- JVM flags and packaging: `app/build.gradle.kts`

## Older and low-end PCs

The app adapts to the PC it runs on (see `Device.kt`); nothing needs to be configured.

- **Weak PC** (2 CPU cores or fewer, or about 4 GB RAM or less): smaller album-art cache, later background work and the light audio engine. Force it with `WSMUSIC_LITE=1` (or `0` to turn it off).
- **No working OpenGL / blank window**: if one start never shows a window, the next start draws with the CPU instead and remembers it. Force a renderer with `WSMUSIC_RENDER=OPENGL|SOFTWARE_COMPAT`.
- **No JavaFX audio** (Windows "N" editions, missing runtimes): playback switches to a pure-Java AAC engine (`CompatPlayer`). Force it with `WSMUSIC_AUDIO=compat|fx`.
- **No administrator rights**: the installer installs per user, and `gradlew :app:portableZip` builds a zip you can unpack and run from anywhere.
- If something still fails, `%APPDATA%\WsMusic\wsmusic.log` says why; delete `device.properties` next to it to reset the choices.

## Tests
- `ImporterTest` builds a fake Android database from the app's real schema and imports it
- `UiFlowTest` clicks through likes, playlists, import dialogs and the Home page
- `ScreenshotTest` / `HomeShotTest` render the UI to PNGs in `app/build/shots/` (handy for design work)

## Data location
`%APPDATA%\WsMusic` (library database and settings).

## License
GPL-3.0 - see [LICENSE.txt](LICENSE.txt). Based on Echo Music and its open-source dependencies
(NewPipe Extractor, InnerTubeX, and others); thanks to their authors.
