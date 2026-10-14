package com.github.musicyou.network

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource

/** Each cache hole resolves upstream independently, including holes opened after eviction. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun cachedStreamingDataSourceFactory(cache: Cache, upstream: DataSource.Factory): DataSource.Factory =
    CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
