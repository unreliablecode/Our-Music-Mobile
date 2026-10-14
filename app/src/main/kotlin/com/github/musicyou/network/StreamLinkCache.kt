package com.github.musicyou.network

import com.github.innertube.requests.streamUrlExpiresAt

internal data class StreamLink(val url: String, val headers: Map<String, String>, val expiresAtMillis: Long)

/** A short-lived cache of already verified, signed audio links. */
internal class StreamLinkCache(private val clock: () -> Long = System::currentTimeMillis) {
    private val links = LinkedHashMap<String, StreamLink>()
    private val formats = LinkedHashMap<String, Int>()

    @Synchronized
    fun get(mediaId: String): StreamLink? {
        val link = links[mediaId] ?: return null
        if (link.expiresAtMillis <= clock() + 30_000) { links.remove(mediaId); return null }
        return link
    }

    @Synchronized
    fun put(mediaId: String, url: String, headers: Map<String, String>, expiresInSeconds: Long?, itag: Int? = null): StreamLink {
        val now = clock()
        val serverExpiry = streamUrlExpiresAt(url) ?: expiresInSeconds?.let { now + it * 1_000 } ?: Long.MAX_VALUE
        val link = StreamLink(url, headers.toMap(), minOf(serverExpiry, now + 120_000))
        links[mediaId] = link
        if (itag != null) formats[mediaId] = itag
        while (links.size > 8) {
            val oldest = links.keys.first()
            links.remove(oldest)
            formats.remove(oldest)
        }
        while (formats.size > 8) formats.remove(formats.keys.first())
        return link
    }

    @Synchronized fun invalidate(mediaId: String) { links.remove(mediaId) }
    @Synchronized fun rememberedItag(mediaId: String): Int? = formats[mediaId]
    @Synchronized fun clear() { links.clear(); formats.clear() }
}
