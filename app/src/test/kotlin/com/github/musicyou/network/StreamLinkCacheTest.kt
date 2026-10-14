package com.github.musicyou.network

import com.github.innertube.models.YouTubeClient
import com.github.innertube.models.PlayerResponse
import com.github.innertube.requests.isUsableStreamResponse
import com.github.innertube.requests.streamUrlExpiresAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLinkCacheTest {
    @Test fun expiredSignatureIsDiscardedBeforeAnotherMediaRequest() {
        var now = 1_700_000_000_000L
        val cache = StreamLinkCache { now }
        cache.put("song", "https://r.googlevideo.com/videoplayback?expire=1700000060", emptyMap(), null)
        assertTrue(cache.get("song") != null)
        now += 31_000
        assertNull(cache.get("song"))
    }

    @Test fun linksWithoutExpiryStillHaveShortLifetime() {
        var now = 1_700_000_000_000L
        val cache = StreamLinkCache { now }
        cache.put("song", "https://r.googlevideo.com/videoplayback", emptyMap(), null)
        now += 91_000
        assertNull(cache.get("song"))
    }

    @Test fun serverReportedExpiryIsRespectedWhenSignatureHasNoExpiry() {
        var now = 1_700_000_000_000L
        val cache = StreamLinkCache { now }
        cache.put("song", "https://r.googlevideo.com/videoplayback", emptyMap(), 45)
        now += 16_000
        assertNull(cache.get("song"))
    }

    @Test fun rejectedLinkCannotBeReturnedAfterInvalidation() {
        val cache = StreamLinkCache { 1_700_000_000_000L }
        cache.put("song", "https://r.googlevideo.com/old", mapOf("User-Agent" to "client"), null)
        cache.invalidate("song")
        assertNull(cache.get("song"))
        cache.put("song", "https://r.googlevideo.com/new", mapOf("User-Agent" to "client"), null)
        assertEquals("https://r.googlevideo.com/new", cache.get("song")?.url)
    }

    @Test fun cachedHeadersRemainBoundToTheResolvedLink() {
        val headers = mutableMapOf("User-Agent" to "matching client")
        val cache = StreamLinkCache { 1_700_000_000_000L }
        cache.put("song", "https://r.googlevideo.com/audio", headers, null)
        headers["User-Agent"] = "unrelated client"
        assertEquals("matching client", cache.get("song")?.headers?.get("User-Agent"))
    }

    @Test fun formatIdentitySurvivesExpiryAndChangesOnlyWithANewResolvedSource() {
        var now = 1_700_000_000_000L
        val cache = StreamLinkCache { now }
        cache.put("song", "https://r.googlevideo.com/opus", emptyMap(), null, 251)
        now += 91_000
        assertNull(cache.get("song"))
        assertEquals(251, cache.rememberedItag("song"))
        cache.put("song", "https://r.googlevideo.com/aac", emptyMap(), null, 140)
        assertEquals(140, cache.rememberedItag("song"))
    }

    @Test fun mediaProbeRejectsSuccessPagesAndEmptyResponses() {
        assertTrue(isUsableStreamResponse(206, "audio/webm", 26))
        assertTrue(isUsableStreamResponse(200, "video/mp4; codecs=avc1,mp4a", 0))
        assertFalse(isUsableStreamResponse(200, "text/html", 60))
        assertFalse(isUsableStreamResponse(206, "audio/webm", -1))
        assertFalse(isUsableStreamResponse(403, "audio/webm", 26))
    }

    @Test fun expiryParsingIgnoresOtherQueryParametersAndMissingExpiry() {
        assertEquals(1_700_000_060_000L, streamUrlExpiresAt("https://r.googlevideo.com/audio?itag=251&expire=1700000060&sig=public"))
        assertNull(streamUrlExpiresAt("https://r.googlevideo.com/audio?itag=251"))
        assertNull(streamUrlExpiresAt("https://r.googlevideo.com/audio?expire=invalid"))
    }

    @Test fun verifiedProgressiveFallbackRemainsSelectableWithItsBoundHeaders() {
        val progressive = testFormat(18, "video/mp4", "AUDIO_QUALITY_MEDIUM")
        val resolved = PlayerResponse.StreamingData(adaptiveFormats = emptyList())
            .withVerifiedFormat(progressive, mapOf("User-Agent" to "matching client"))
        assertEquals(18, resolved.highestQualityFormat?.itag)
        assertEquals("matching client", resolved.highestQualityFormat?.requestHeaders?.get("User-Agent"))
        assertTrue(resolved.adaptiveFormats.isNullOrEmpty())
    }

    @Test fun verifiedAudioOnlyFormatRemainsSelectableAndVideoOnlyFormatsAreRejected() {
        val audio = testFormat(251, "audio/webm", "AUDIO_QUALITY_MEDIUM")
        val resolved = PlayerResponse.StreamingData(adaptiveFormats = emptyList()).withVerifiedFormat(audio, emptyMap())
        assertEquals(251, resolved.highestQualityFormat?.itag)
        assertTrue(resolved.formats.isNullOrEmpty())
        val videoOnly = testFormat(137, "video/mp4", null)
        assertNull(PlayerResponse.StreamingData(adaptiveFormats = listOf(videoOnly)).highestQualityFormat)
        assertNull(PlayerResponse.StreamingData(adaptiveFormats = emptyList(), formats = listOf(videoOnly)).highestQualityFormat)
    }

    private fun testFormat(itag: Int, mime: String, audioQuality: String?) = PlayerResponse.StreamingData.AdaptiveFormat(
        itag = itag, mimeType = mime, bitrate = 128_000, averageBitrate = null, contentLength = 5_956_710,
        audioQuality = audioQuality, approxDurationMs = 355_000, lastModified = null, loudnessDb = null,
        audioSampleRate = 48_000, url = "https://r.googlevideo.com/audio",
    )

    @Test fun contextContainsTheDeviceFieldsUsedForThePublicClient() {
        val client = YouTubeClient.VISION_OS.toContext().client
        assertEquals("visionOS", client.osName)
        assertEquals("RealityDevice17,1", client.deviceModel)
        assertEquals("26.5.23O471", client.osVersion)
        assertEquals(YouTubeClient.VISION_OS.userAgent, client.userAgent)
    }
}
