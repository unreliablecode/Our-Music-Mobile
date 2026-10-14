package com.github.innertube.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class PlayerResponse(
    val playabilityStatus: PlayabilityStatus?,
    val playerConfig: PlayerConfig?,
    val streamingData: StreamingData?,
    val videoDetails: VideoDetails?,
) {
    @Serializable
    data class PlayabilityStatus(
        val status: String?,
        val reason: String? = null,
    )

    @Serializable
    data class PlayerConfig(
        val audioConfig: AudioConfig?
    ) {
        @Serializable
        data class AudioConfig(
            private val loudnessDb: Double?,
            private val perceptualLoudnessDb: Double?
        ) {
            // For music clients only
            val normalizedLoudnessDb: Float?
                get() = (loudnessDb ?: perceptualLoudnessDb)?.plus(7)?.toFloat()
        }
    }

    @Serializable
    data class StreamingData(
        val adaptiveFormats: List<AdaptiveFormat>?,
        val formats: List<AdaptiveFormat>? = null,
        val expiresInSeconds: Long? = null,
    ) {
        fun withVerifiedFormat(format: AdaptiveFormat, headers: Map<String, String>): StreamingData {
            val selected = format.copy(requestHeaders = headers)
            val audioOnly = selected.mimeType.startsWith("audio/")
            return copy(
                adaptiveFormats = if (audioOnly) listOf(selected) else emptyList(),
                formats = if (audioOnly) emptyList() else listOf(selected),
            )
        }

        val highestQualityFormat: AdaptiveFormat?
            get() {
                val combined = adaptiveFormats.orEmpty() + formats.orEmpty()
                val audioFormats = combined.filter { it.url != null && it.mimeType.startsWith("audio/") }
                if (audioFormats.isNotEmpty()) {
                    return audioFormats.find { it.itag == 251 }
                        ?: audioFormats.find { it.itag == 140 }
                        ?: audioFormats.find { it.itag == 250 }
                        ?: audioFormats.find { it.itag == 249 }
                        ?: audioFormats.find { it.itag == 139 }
                        ?: audioFormats.maxByOrNull { it.bitrate ?: 0L }
                }
                return formats.orEmpty().find {
                    it.url != null && it.mimeType.startsWith("video/") && (it.audioQuality != null || it.itag == 18 || it.itag == 22)
                }
            }

        @Serializable
        data class AdaptiveFormat(
            val itag: Int,
            val mimeType: String,
            val bitrate: Long?,
            val averageBitrate: Long?,
            val contentLength: Long?,
            val audioQuality: String?,
            val approxDurationMs: Long?,
            val lastModified: Long?,
            val loudnessDb: Double?,
            val audioSampleRate: Int?,
            val url: String?,
            @Transient val requestHeaders: Map<String, String> = emptyMap(),
        )
    }

    @Serializable
    data class VideoDetails(
        val videoId: String?
    )
}
