package com.github.musicyou.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import com.github.musicyou.LocalPlayerPadding
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.ui.apple.ApplePageTitle
import com.github.musicyou.ui.components.MenuEntry
import com.github.musicyou.ui.glass.AdaptiveIcon as Icon
import com.github.musicyou.ui.glass.AdaptiveText as Text
import com.github.musicyou.ui.glass.adaptiveContentColor
import com.github.musicyou.utils.forcePlay
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DownloadMenuEntry(media: MediaItem) {
    val manager = rememberDownloadManager()
    val downloads by manager.downloads.collectAsState()
    val record = downloads.firstOrNull { it.mediaId == media.mediaId }
    LaunchedEffect(media.mediaId) { manager.refresh() }
    val active = record?.status == DownloadStatus.QUEUED || record?.status == DownloadStatus.DOWNLOADING
    val complete = record?.status == DownloadStatus.COMPLETE
    MenuEntry(
        icon = if (complete) Icons.Outlined.DeleteOutline else if (active) Icons.Outlined.Close else Icons.Outlined.Download,
        text = if (complete) "Remove Download" else if (active) "Cancel Download" else if (record?.status == DownloadStatus.FAILED) "Retry Download" else "Download",
        secondaryText = when {
            complete -> "Available offline · ${formatDownloadSize(record.downloadedBytes)}"
            active -> if (record?.totalBytes ?: 0 > 0) "${((record?.progress ?: 0f) * 100).toInt()}% downloaded" else "Preparing offline copy"
            record?.status == DownloadStatus.FAILED -> record.error
            else -> "Keep this song on your device"
        },
        onClick = { when { complete -> manager.delete(media.mediaId); active -> manager.cancel(media.mediaId); else -> manager.download(media) } },
    )
}

@Composable
fun DownloadedSongsPage(navController: NavHostController) {
    val manager = rememberDownloadManager()
    val downloads by manager.downloads.collectAsState()
    val binder = LocalPlayerServiceBinder.current
    val scope = rememberCoroutineScope()
    val bottom = LocalPlayerPadding.current
    LaunchedEffect(manager) { manager.refresh() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 24.dp)) {
        item { ApplePageTitle("Downloaded", back = { navController.popBackStack() }) }
        item {
            Text("Your saved songs stay on this device until you remove them.", Modifier.padding(horizontal = 24.dp, vertical = 4.dp), fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
        }
        if (downloads.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(44.dp))
                    Text("Music, wherever you are", Modifier.padding(top = 16.dp), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("Open a song's menu and choose Download.", Modifier.padding(top = 8.dp), fontSize = 15.sp)
                }
            }
        }
        items(downloads, key = { it.mediaId }) { record ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = record.status == DownloadStatus.COMPLETE) {
                    scope.launch {
                        val media = withContext(Dispatchers.IO) { manager.localMediaItem(record.mediaId) }
                        if (media != null) { binder?.stopRadio(); binder?.player?.forcePlay(media) } else manager.refresh()
                    }
                }.padding(horizontal = 24.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val artwork = record.localArtwork?.let(::File)?.takeIf { it.isFile }
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.LibraryMusic, null, Modifier.size(24.dp))
                    if (artwork != null) AsyncImage(artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(record.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(record.artist, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when (record.status) {
                        DownloadStatus.COMPLETE -> Text("Downloaded · ${formatDownloadSize(record.downloadedBytes)}", fontSize = 11.sp)
                        DownloadStatus.QUEUED -> Text("Queued", fontSize = 12.sp)
                        DownloadStatus.DOWNLOADING -> {
                            LinearProgressIndicator(progress = { record.progress }, Modifier.fillMaxWidth().padding(top = 6.dp), color = adaptiveContentColor())
                            Text(if (record.totalBytes > 0) "${(record.progress * 100).toInt()}%" else "Downloading…", fontSize = 11.sp)
                        }
                        DownloadStatus.FAILED -> Text(record.error ?: "Download failed", fontSize = 11.sp, maxLines = 2)
                        DownloadStatus.CANCELED -> Text("Canceled", fontSize = 12.sp)
                    }
                }
                if (record.status == DownloadStatus.FAILED || record.status == DownloadStatus.CANCELED) {
                    IconButton(onClick = { manager.retry(record.mediaId) }) { Icon(Icons.Outlined.Refresh, "Retry download") }
                }
                IconButton(onClick = {
                    if (record.status == DownloadStatus.DOWNLOADING || record.status == DownloadStatus.QUEUED) manager.cancel(record.mediaId)
                    else manager.delete(record.mediaId)
                }) {
                    Icon(if (record.status == DownloadStatus.DOWNLOADING || record.status == DownloadStatus.QUEUED) Icons.Outlined.Close else Icons.Outlined.DeleteOutline,
                        if (record.status == DownloadStatus.DOWNLOADING || record.status == DownloadStatus.QUEUED) "Cancel download" else "Remove download")
                }
            }
        }
    }
}

private fun formatDownloadSize(bytes: Long): String = if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / (1024f * 1024f)) else "${bytes / 1024} KB"
