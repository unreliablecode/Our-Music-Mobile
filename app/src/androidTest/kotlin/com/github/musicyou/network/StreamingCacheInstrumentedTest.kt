package com.github.musicyou.network

import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises real cache transitions without depending on remote service availability. */
@androidx.annotation.OptIn(UnstableApi::class)
class StreamingCacheInstrumentedTest {
    @Test fun partialCacheContinuesPastTheFirst512KiB() = withCache { cache ->
        val bytes = ByteArray(1_300_000) { (it * 31).toByte() }
        val resolutions = AtomicInteger()
        val factory = factory(cache, bytes, resolutions)
        val request = DataSpec.Builder().setUri("song").setKey("song").build()
        seed(factory, request.subrange(0, 524_288))
        resolutions.set(0)
        val source = factory.createDataSource()
        try {
            source.open(request)
            assertArrayEquals(bytes, readAll(source))
            assertTrue("The uncached remainder must resolve its media ID", resolutions.get() > 0)
        } finally { source.close() }
    }

    @Test fun clearingAnUpcomingCachedSpanResolvesTheNewHole() = withCache { cache ->
        val bytes = ByteArray(1_300_000) { (it * 17).toByte() }
        val resolutions = AtomicInteger()
        val factory = factory(cache, bytes, resolutions)
        val request = DataSpec.Builder().setUri("song").setKey("song").build()
        seed(factory, request.subrange(0, 524_288))
        seed(factory, request.subrange(524_288, bytes.size.toLong() - 524_288))
        assertTrue(cache.isCached("song", 0, bytes.size.toLong()))
        resolutions.set(0)
        val source = factory.createDataSource()
        try {
            source.open(request)
            val prefix = ByteArray(1024)
            assertTrue(source.read(prefix, 0, prefix.size) == prefix.size)
            cache.getCachedSpans("song").filter { it.position >= 524_288 }.forEach(cache::removeSpan)
            val result = ByteArrayOutputStream().apply { write(prefix); write(readAll(source)) }.toByteArray()
            assertArrayEquals(bytes, result)
            assertTrue("A hole created during cached playback must still resolve upstream", resolutions.get() > 0)
        } finally { source.close() }
    }

    private fun factory(cache: SimpleCache, bytes: ByteArray, resolutions: AtomicInteger): DataSource.Factory {
        val upstream = DataSource.Factory {
            val source = ByteArrayDataSource(bytes)
            object : DataSource by source {
                override fun open(dataSpec: DataSpec): Long {
                    check(dataSpec.uri.toString() == "https://media.example/song") { "Unresolved media ID reached upstream" }
                    return source.open(dataSpec)
                }
            }
        }
        val resolving = ResolvingDataSource.Factory(upstream) { spec ->
            resolutions.incrementAndGet()
            check(spec.key == "song")
            spec.withUri(android.net.Uri.parse("https://media.example/song"))
        }
        return cachedStreamingDataSourceFactory(cache, resolving)
    }

    private fun seed(factory: DataSource.Factory, request: DataSpec) {
        val source = factory.createDataSource() as androidx.media3.datasource.cache.CacheDataSource
        CacheWriter(source, request, null, null).cache()
    }

    private fun readAll(source: DataSource): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count < 0) break
            if (count > 0) result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    private fun withCache(block: (SimpleCache) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "stream-cache-test-${UUID.randomUUID()}")
        val database = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(directory, NoOpCacheEvictor(), database)
        try { block(cache) } finally {
            cache.release()
            SimpleCache.delete(directory, database)
            database.close()
        }
    }
}
