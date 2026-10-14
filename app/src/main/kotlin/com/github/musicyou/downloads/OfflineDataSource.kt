package com.github.musicyou.downloads

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource

/** Local resolution happens before even constructing or opening the streaming source. */
@androidx.annotation.OptIn(UnstableApi::class)
fun offlineFirstDataSourceFactory(context: Context, streaming: DataSource.Factory): DataSource.Factory {
    val manager = OfflineDownloads.get(context)
    return ResolvingDataSource.Factory(
        DataSource.Factory {
            RoutingDataSource(streaming)
        },
    ) { spec ->
        val id = spec.key ?: spec.uri.toString()
        manager.localFile(id)?.let { spec.withUri(Uri.fromFile(it)) } ?: spec
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class RoutingDataSource(private val streaming: DataSource.Factory) : DataSource {
    private var source: DataSource? = null
    private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        listeners.add(transferListener)
        source?.addTransferListener(transferListener)
    }
    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        val selected = if (dataSpec.uri.scheme == "file") FileDataSource() else streaming.createDataSource()
        source = selected
        listeners.forEach(selected::addTransferListener)
        return selected.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source?.read(buffer, offset, length) ?: -1
    override fun getUri(): Uri? = source?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = source?.responseHeaders.orEmpty()
    override fun close() { source?.close(); source = null }
}
