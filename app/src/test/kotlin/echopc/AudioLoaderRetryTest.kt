package echopc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** A stream that stalls is retried with a different client, then without SABR, instead of the same way three times. */
class AudioLoaderRetryTest {
    private fun stream(client: String) = ResolvedStream(
        url = "https://example.invalid/$client", mimeType = "audio/mp4", bitrate = 128_000, client = client, headers = emptyMap(),
    )

    @Test
    fun aFailingClientIsAvoidedOnTheNextAttempt() = runBlocking {
        val calls = mutableListOf<Pair<Set<String>, Boolean>>()
        val loader = AudioLoader(
            resolver = { _, skip, sabr ->
                calls += skip.toSet() to sabr
                stream(if ("VISIONOS" in skip) "ANDROID" else "VISIONOS")
            },
            downloadOverride = { s, file ->
                if (s.client == "VISIONOS") error("SABR made no audio progress")
                Files.write(file, byteArrayOf(1, 2, 3))
            },
            retryDelayMs = 1,
        )
        val file = loader.load("vid1").await()
        assertTrue(Files.size(file) == 3L)
        assertEquals(2, calls.size)
        assertEquals(emptySet(), calls[0].first)
        assertEquals(setOf("VISIONOS"), calls[1].first)
    }

    @Test
    fun playerBarErrorsAreInPlainWords() {
        assertEquals(true, "limiting streams" in playbackErrorText("SABR stream protection requires attestation (status 2, retry=1/0)"))
        assertEquals(true, "no stream" in playbackErrorText("No playable stream found for this track."))
        assertEquals("There's no internet connection", playbackErrorText("java.net.UnknownHostException: music.youtube.com"))
    }

    @Test
    fun anAttestationBlockIsWaitedOutWithTheSameClient() = runBlocking {
        val calls = mutableListOf<Pair<Set<String>, Boolean>>()
        var downloads = 0
        val loader = AudioLoader(
            resolver = { _, skip, sabr -> calls += skip.toSet() to sabr; stream("VISIONOS") },
            downloadOverride = { _, file ->
                if (++downloads <= 2) error("SABR stream protection requires attestation (status 2)")
                Files.write(file, byteArrayOf(9))
            },
            retryDelayMs = 1,
        )
        loader.load("vid3").await()
        assertEquals(3, calls.size)
        assertTrue(calls.all { it.first.isEmpty() && it.second }, "same client, SABR kept: $calls")
    }

    @Test
    fun anAttestationBlockIsRetriedForAboutAMinuteThenGivenUp() = runBlocking {
        var tries = 0
        val loader = AudioLoader(
            resolver = { _, _, _ -> tries++; stream("VISIONOS") },
            downloadOverride = { _, _ -> error("SABR stream protection requires attestation (status 2)") },
            retryDelayMs = 1,
        )
        val failure = runCatching { loader.load("vid4").await() }.exceptionOrNull()
        assertTrue(failure is StreamFailure)
        assertEquals(7, tries)
    }

    @Test
    fun laterAttemptsStopUsingSabrAndEventuallyGiveUp() = runBlocking {
        val calls = mutableListOf<Boolean>()
        val loader = AudioLoader(
            resolver = { _, _, sabr -> calls += sabr; stream("X${calls.size}") },
            downloadOverride = { _, _ -> error("no audio") },
            retryDelayMs = 1,
        )
        val failure = runCatching { loader.load("vid2").await() }.exceptionOrNull()
        assertTrue(failure is StreamFailure, "gave up with a stream failure: $failure")
        assertEquals(listOf(true, true, false, false), calls)
    }
}
