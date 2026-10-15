package echopc

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.SongItem
import echopc.db.LibraryDatabase
import echopc.db.LibraryRepository
import echopc.db.TastePick
import echopc.reco.DAY
import echopc.reco.FakeSource
import echopc.reco.MixKind
import echopc.reco.NOW
import echopc.reco.Recommender
import echopc.reco.Saved
import echopc.reco.TasteProfile
import echopc.reco.Weights
import echopc.reco.song
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** New users: picking artists, "Popular right now", and what they turn into. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class NewUserTest {
    private fun tmpDb() = File.createTempFile("ws-new", ".db").also { it.delete(); it.deleteOnExit() }
    private suspend fun until(what: String, check: suspend () -> Boolean) = withTimeout(10_000) { while (!check()) delay(25) }.also { println("ok: $what") }

    private fun artist(id: String, name: String) = ArtistItem(id = id, title = name, thumbnail = null, shuffleEndpoint = null, radioEndpoint = null)
    private fun fresh(prefix: String, n: Int = 15) = List(n) { song("$prefix$it", "$prefix-artist$it") }

    /** Fixed answers, and a count of calls so caching can be checked. */
    private class FakeDiscovery(
        val popular: PopularNow? = PopularNow("The Hit List", List(8) { song("hit$it", "Hitmaker$it") }),
        val suggested: List<ArtistItem> = emptyList(),
        val similar: Map<String, List<ArtistItem>> = emptyMap(),
        val search: Map<String, List<ArtistItem>> = emptyMap(),
    ) : DiscoverySource {
        var popularCalls = 0
        override suspend fun popularNow(): PopularNow? { popularCalls++; return popular }
        override suspend fun suggestedArtists() = suggested
        override suspend fun searchArtists(query: String) = search[query].orEmpty()
        override suspend fun similarArtists(artistId: String) = similar[artistId].orEmpty()
    }

    // ------------------------------------------------------------ taste weight

    @Test
    fun pickedArtistsSeedTasteBelowALikeAndFadeAway() {
        val songs = List(3) { song("p$it", "Alpha") }
        val picks = songs.map { Saved(it.id, "UC_Alpha", "Alpha", NOW - DAY) }
        val picked = TasteProfile.build(emptyList(), emptyList(), emptyList(), emptyList(), NOW, pickSongs = picks)
        assertTrue(picked.artistAffinity("UC_Alpha") > 0 && "UC_Alpha" in picked.topArtists(), "a pick is a taste")
        val pickScore = picked.tracks.values.sumOf { it.longScore }
        // Three top songs together: more than adding one song to a playlist, less than liking a song.
        assertTrue(Weights.PLAYLIST_ADD < 3 * Weights.PICK_SONG && 3 * Weights.PICK_SONG < Weights.LIKE)
        assertEquals(3 * Weights.PICK_SONG, pickScore, 0.05)
        val liked = TasteProfile.build(emptyList(), listOf(Saved("l", "UC_Beta", "Beta", NOW - DAY)), emptyList(), emptyList(), NOW)
        assertTrue(liked.tracks.getValue("l").longScore > pickScore, "a like outweighs a pick")
        // The short-term view fades within weeks, so listening takes over from a stale guess.
        val old = TasteProfile.build(emptyList(), emptyList(), emptyList(), emptyList(), NOW, pickSongs = songs.map { Saved(it.id, "UC_Alpha", "Alpha", NOW - 60 * DAY) })
        assertTrue(old.tracks.values.sumOf { it.shortScore } < 0.02 * pickScore + 0.02)
    }

    // ------------------------------------------------------------ database

    @Test
    fun v2DatabaseMigratesToV3AndKeepsPicks() = runBlocking {
        val file = tmpDb()
        LibraryDatabase.open(file).let { db -> LibraryRepository(db).toggleLike(song("keep", "Alpha")); db.close() }
        BundledSQLiteDriver().open(file.absolutePath).let { conn ->
            conn.execSQL("DROP TABLE `taste_pick`"); conn.execSQL("PRAGMA user_version = 2"); conn.close()
        }
        val repo = LibraryRepository(LibraryDatabase.open(file))
        assertEquals(emptyList(), repo.tastePicksNow(), "the new table exists and is empty")
        assertEquals(1, repo.likedSongs.first().size, "earlier data is untouched")
        repo.replaceTastePicks(listOf(TastePick("UC_a", "Alpha", "t", LocalDateTime.now()), TastePick("UC_b", "Beta", "", LocalDateTime.now())))
        repo.replaceTastePicks(listOf(TastePick("UC_c", "Gamma", "", LocalDateTime.now())))
        assertEquals(listOf("UC_c"), repo.tastePicksNow().map { it.artistKey }, "saving replaces the earlier picks")
        repo.close()
    }

    // ------------------------------------------------------------ picks become mixes

    @Test
    fun picksAloneBuildDiscoverAndDailyMixes() = runBlocking {
        val repo = LibraryRepository(LibraryDatabase.open(tmpDb()))
        val alpha = List(3) { song("a$it", "Alpha") }; val beta = List(3) { song("b$it", "Beta") }
        val source = FakeSource(
            related = (alpha + beta).associate { it.id to fresh(it.id + "n") },
            releases = mapOf("UC_Alpha" to List(6) { song("ra$it", "Alpha") }),
            tops = mapOf("UC_Alpha" to alpha, "UC_Beta" to beta),
        )
        repo.replaceTastePicks(listOf(TastePick("UC_Alpha", "Alpha", "", LocalDateTime.now()), TastePick("UC_Beta", "Beta", "", LocalDateTime.now())))
        val rebuilt = Recommender(repo, source).refresh()
        assertTrue(MixKind.DISCOVER in rebuilt && MixKind.DAILY in rebuilt && MixKind.RADAR in rebuilt, "rebuilt: $rebuilt")
        val mixes = repo.mixesNow()
        val daily = echopc.db.TrackCodec.decode(mixes.first { it.kind == MixKind.DAILY }.songs)
        assertTrue(daily.size >= 10 && daily.any { it.id.startsWith("a") || it.id.startsWith("b") }, "the picked artists' songs are in the Daily Mix")
        assertTrue(mixes.any { it.kind == MixKind.DISCOVER } && mixes.any { it.kind == MixKind.RADAR }, "Release Radar works for a picked artist")
        // No history at all, and nothing was played: the two history mixes correctly stay empty.
        assertTrue(mixes.none { it.kind == MixKind.ON_REPEAT || it.kind == MixKind.REWIND })
        repo.close()
    }

    @Test
    fun aPickWhoseSongsDontLoadStillSteersReleaseRadar() = runBlocking {
        val repo = LibraryRepository(LibraryDatabase.open(tmpDb()))
        val source = FakeSource(related = emptyMap(), releases = mapOf("UC_Gamma" to List(6) { song("rg$it", "Gamma") }), tops = emptyMap())
        repo.replaceTastePicks(listOf(TastePick("UC_Gamma", "Gamma", "", LocalDateTime.now())))
        Recommender(repo, source).refresh()
        assertTrue(repo.mixesNow().any { it.kind == MixKind.RADAR }, "the artist alone is enough for new releases")
        repo.close()
    }

    // ------------------------------------------------------------ controller

    private class Setup(val c: Controller, val repo: LibraryRepository, val scope: TestScope, val discovery: FakeDiscovery) {
        fun close() { scope.stop(); repo.close() }
    }

    private fun setup(discovery: FakeDiscovery, source: FakeSource = FakeSource(emptyMap()), settings: File? = null): Setup {
        val repo = LibraryRepository(LibraryDatabase.open(tmpDb()))
        val scope = TestScope()
        val c = Controller(scope, repo, settings, recoSource = source, discoverySource = discovery) { _, _ -> FakeEngine() }
        return Setup(c, repo, scope, discovery)
    }

    private val alphaTop = List(3) { song("a$it", "Alpha") }
    private val betaTop = List(3) { song("b$it", "Beta") }
    private fun mixSource() = FakeSource((alphaTop + betaTop).associate { it.id to fresh(it.id + "n") }, tops = mapOf("UC_Alpha" to alphaTop, "UC_Beta" to betaTop))
    private val artists = listOf(artist("UC_Alpha", "Alpha"), artist("UC_Beta", "Beta"), artist("UC_Gamma", "Gamma"), artist("UC_Delta", "Delta"))

    @Test
    fun pickerSuggestsSimilarArtistsAndSavesThePicks() = runBlocking {
        val discovery = FakeDiscovery(
            suggested = artists.take(3),
            similar = mapOf("UC_Alpha" to listOf(artists[1], artists[3]), "UC_Beta" to emptyList()), // Beta is already shown; Delta is new
            search = mapOf("del" to listOf(artists[3])),
        )
        val t = setup(discovery, mixSource()); val c = t.c
        c.openPicker()
        assertEquals(Route.Picker, c.route)
        until("starter artists load") { c.suggestedArtists.size == 3 }

        c.togglePick(artists[0])
        assertEquals(listOf("UC_Alpha"), c.pickerSelected.map { it.id })
        until("similar artists suggested") { c.pickerSimilar.map { it.id } == listOf("UC_Delta") } // Beta was already shown, so only Delta is new
        c.togglePick(artists[0])
        assertTrue(c.pickerSelected.isEmpty(), "tapping again un-picks")

        c.searchPickerArtists("del")
        until("search results") { c.pickerResults.map { it.id } == listOf("UC_Delta") }
        c.searchPickerArtists("")
        assertTrue(c.pickerResults.isEmpty() && !c.pickerSearching)

        c.savePicks() // nothing picked: nothing happens
        assertEquals(Route.Picker, c.route)
        c.togglePick(artists[0]); c.togglePick(artists[1])
        c.savePicks()
        assertEquals(Route.Home, c.route, "back where the picker was opened from")
        assertTrue(c.tastePromptDismissed)
        until("picks stored") { t.repo.tastePicksNow().map { it.artistKey }.toSet() == setOf("UC_Alpha", "UC_Beta") }
        until("first mixes built from the picks") { c.mixes.any { it.kind == MixKind.DISCOVER } && c.mixes.any { it.kind == MixKind.DAILY } }
        t.close()
    }

    @Test
    fun skippingSavesNothingAndEditingStartsFromTheCurrentPicks() = runBlocking {
        val t = setup(FakeDiscovery(suggested = artists), mixSource()); val c = t.c
        c.openPicker(); c.skipPicker()
        assertEquals(Route.Home, c.route); assertTrue(c.tastePromptDismissed)
        assertEquals(emptyList(), t.repo.tastePicksNow())

        t.repo.replaceTastePicks(listOf(TastePick("UC_Beta", "Beta", "", LocalDateTime.now())))
        until("picks observed") { c.tastePicks.size == 1 }
        c.openPicker()
        assertEquals(listOf("UC_Beta"), c.pickerSelected.map { it.id }, "the saved picks are ticked when reopened")
        t.close()
    }

    @Test
    fun popularRightNowLoadsOnceAndNotNowIsRemembered() = runBlocking {
        val settings = File.createTempFile("ws-settings", ".properties").also { it.deleteOnExit() }
        val t = setup(FakeDiscovery(), settings = settings); val c = t.c
        c.loadPopular(); c.loadPopular()
        until("popular songs") { c.popularNow != null }
        c.loadPopular()
        assertEquals(1, t.discovery.popularCalls, "fetched once")
        assertEquals("The Hit List", c.popularNow?.title)

        c.dismissTastePrompt()
        c.shutdown() // saves settings
        t.close()
        val again = setup(FakeDiscovery(), settings = settings)
        assertTrue(again.c.tastePromptDismissed, "Not now survives a restart")
        again.close()
    }

    // ------------------------------------------------------------ screens

    private fun ui(t: Setup, block: androidx.compose.ui.test.DesktopComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 1340, height = 1700, testTimeout = 4.minutes) {
            setContent { WsTheme(t.c.accent) { AppContent(t.c, onPickAndroidFile = {}) } }
            block()
            t.close()
        }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.has(text: String, substring: Boolean = false) =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aNewUserSeesThePopularShelfAndTheCardAndPicksTheirWayToMixes() {
        val t = setup(FakeDiscovery(suggested = artists), mixSource())
        ui(t) {
            // Home for a brand-new user: YouTube's hit playlist, labelled, plus the invitation.
            waitUntil(timeoutMillis = 30_000L) { has("Popular right now") }
            assertTrue(has("From “The Hit List” on YouTube Music", substring = true), "the shelf says where its songs come from")
            assertTrue(has("Tell us what you like") && has("Pick artists") && has("Not now"))
            assertTrue(has("Hitmaker0") || has("Song hit0"), "its songs are shown")

            onNodeWithText("Pick artists").performClick()
            waitUntil(timeoutMillis = 30_000L) { has("Choose artists you like") && has("Alpha") }
            assertTrue(has("Pick artists you like"), "footer before anything is picked")
            onNodeWithText("Alpha").performClick()
            onNodeWithText("Beta").performClick()
            waitUntil(timeoutMillis = 30_000L) { has("2 picked", substring = true) }
            assertTrue(has("1 more gives better mixes", substring = true))
            onNodeWithText("Gamma").performClick()
            waitUntil(timeoutMillis = 30_000L) { has("3 picked", substring = true) && has("plenty", substring = true) }

            onNodeWithText("Done").performClick()
            waitUntil(timeoutMillis = 30_000L) { t.c.route == Route.Home && has("Thanks! Building your first mixes now") }
            // The invitation is gone, the way back stays, and once the mixes exist they replace the popular shelf.
            waitUntil(timeoutMillis = 30_000L) { !has("Tell us what you like") && has("Edit your artists") }
            waitUntil(timeoutMillis = 40_000L) { t.c.mixes.any { it.kind == MixKind.DISCOVER } && !has("Popular right now") }
            waitUntil(timeoutMillis = 30_000L) { has("Discover Weekly") } // the new mixes are on Home
        }
    }

    @Test
    fun notNowHidesTheCardButKeepsAWayToPick() {
        val t = setup(FakeDiscovery(suggested = artists))
        ui(t) {
            waitUntil(timeoutMillis = 30_000L) { has("Tell us what you like") }
            onNodeWithText("Not now").performClick()
            waitUntil(timeoutMillis = 30_000L) { !has("Tell us what you like") && has("Choose artists you like") }
            assertTrue(has("Popular right now"), "still has something to listen to")
        }
    }

    // ------------------------------------------------------------ Home is never blank

    /** The kind of text YouTube's server really returned during a 503 outage. */
    private val raw503 = RuntimeException(
        """Server error(POST https://music.youtube.com/youtubei/v1/browse?prettyPrint=false: 503 . Text: "{
  "error": { "code": 503, "message": "The service is currently unavailable.", "status": "UNAVAILABLE" }
}"""",
    )

    @Test
    fun errorsAreWrittenForPeopleNotCopiedFromTheServer() {
        assertEquals("YouTube Music is temporarily unavailable", friendlyError(raw503))
        assertEquals("There's no internet connection", friendlyError(RuntimeException("wrapped", java.net.UnknownHostException("music.youtube.com"))))
        assertEquals("YouTube Music is busy right now", friendlyError(RuntimeException("HTTP 429 Too Many Requests")))
        assertEquals("The connection is too slow right now", friendlyError(java.net.SocketTimeoutException("timeout")))
        assertEquals("Something went wrong loading this", friendlyError(IllegalStateException("odd")))
        for (e in listOf(raw503, RuntimeException("""{ "x": 1 }"""))) {
            assertTrue(friendlyError(e).none { it == '{' || it == '"' }, "no raw JSON reaches the screen")
        }
    }

    @Test
    fun whenYouTubeFailsHomeShowsAFriendlyMessageAndStillHasTheSavedPopularSongs() {
        val t = setup(FakeDiscovery(suggested = artists))
        t.c.homeError = friendlyError(raw503)
        ui(t) {
            waitUntil(timeoutMillis = 30_000L) { has("Couldn't load YouTube Music") }
            assertTrue(has("YouTube Music is temporarily unavailable", substring = true) && has("We'll keep trying", substring = true) && has("Try again"))
            assertTrue(!has("UNAVAILABLE", substring = true) && !has("prettyPrint", substring = true), "no raw server text")
            waitUntil(timeoutMillis = 30_000L) { has("Popular right now") } // the page still has something to play
            assertTrue(has("Tell us what you like"), "and the invitation")
        }
    }

    @Test
    fun homeShowsPlaceholdersNotABlankPageWhileNothingHasArrived() {
        val t = setup(FakeDiscovery(popular = null)) // no feed, no popular songs, no error yet
        ui(t) {
            waitUntil(timeoutMillis = 30_000L) { has("Tell us what you like") }
            onNodeWithTag("home-placeholder").assertExists()
        }
    }
}
