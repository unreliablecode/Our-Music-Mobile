package com.github.musicyou.ui.export

import android.Manifest
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import com.github.musicyou.database
import com.github.musicyou.downloads.DownloadStatus
import com.github.musicyou.downloads.OfflineDownloads
import com.github.musicyou.export.VideoAspectRatio
import com.github.musicyou.export.VideoBackground
import com.github.musicyou.export.VideoExportConfig
import com.github.musicyou.export.VideoExportRequest
import com.github.musicyou.export.VideoExports
import com.github.musicyou.export.VideoLyricCue
import com.github.musicyou.export.VideoResolution
import com.github.musicyou.export.VideoVisualization
import com.github.musicyou.export.VideoAccentPalette
import com.github.musicyou.export.VideoStylePreset
import com.github.musicyou.ui.glass.AdaptiveIcon
import com.github.musicyou.ui.glass.AdaptiveText
import com.github.musicyou.ui.glass.GlassButton
import com.github.musicyou.ui.glass.GlassIconButton
import com.github.musicyou.ui.glass.GlassSurface
import com.github.musicyou.ui.glass.GlassToggle
import com.github.musicyou.ui.glass.GlassSlider
import com.github.musicyou.utils.fetchAndSaveLyrics
import com.github.musicyou.utils.parseLrc
import com.github.musicyou.utils.preferences
import com.github.musicyou.utils.thumbnail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/** Configuration, preview and progress for a saved video made from complete offline audio. */
@Composable
fun VideoExportDialog(media: MediaItem, durationMs: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext
    val manager = remember(application) { VideoExports.get(application) }
    val state by manager.state.collectAsState()
    val downloads = remember(application) { OfflineDownloads.get(application) }
    val records by downloads.downloads.collectAsState()
    val record = records.firstOrNull { it.mediaId == media.mediaId }
    val downloadProgress = record?.progress ?: 0f
    val storedLyrics by remember(media.mediaId) { database.lyrics(media.mediaId) }.collectAsState(null)
    val cues = remember(storedLyrics?.synced, media.mediaId) {
        val delay = application.preferences.getInt("lyrics_delay_${media.mediaId}", 0)
        parseLrc(storedLyrics?.synced).lines.filter { it.text.isNotBlank() }
            .map { VideoLyricCue(it.timeMs + delay, it.text) }
    }
    var config by remember { mutableStateOf(loadConfig(application)) }
    var includeLyrics by remember(media.mediaId) { mutableStateOf(application.getSharedPreferences("video_export", Context.MODE_PRIVATE).getBoolean("lyrics", true)) }
    var offlineReady by remember(media.mediaId) { mutableStateOf(false) }
    var loadingLyrics by remember(media.mediaId) { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val settingsScroll = rememberScrollState()
    LaunchedEffect(config) { delay(180); saveConfig(application, config) }
    val latestConfig by rememberUpdatedState(config)
    DisposableEffect(application) { onDispose { saveConfig(application, latestConfig) } }
    LaunchedEffect(includeLyrics) { application.getSharedPreferences("video_export", Context.MODE_PRIVATE).edit().putBoolean("lyrics", includeLyrics).apply() }
    LaunchedEffect(media.mediaId, record?.status, record?.downloadedBytes) {
        offlineReady = if (record?.status == DownloadStatus.COMPLETE) {
            withContext(Dispatchers.IO) { downloads.localFile(media.mediaId) != null }
        } else false
    }
    LaunchedEffect(state.outputUri, state.error) {
        if (state.outputUri != null || state.error != null) {
            withFrameNanos { }
            settingsScroll.animateScrollTo(settingsScroll.maxValue)
        }
    }
    val request = remember(media.mediaId, media.mediaMetadata, config, cues, includeLyrics) {
        VideoExportRequest(
            mediaId = media.mediaId,
            title = media.mediaMetadata.title?.toString().orEmpty().ifBlank { "Untitled song" },
            artist = media.mediaMetadata.artist?.toString().orEmpty(),
            artworkUri = media.mediaMetadata.artworkUri?.let { it.thumbnail(1600)?.toString() },
            lyrics = if (includeLyrics) cues else emptyList(),
            config = config,
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { message = null; manager.start(request) }
        else message = "Storage access is needed to save videos on this Android version."
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                application.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                config = config.copy(background = VideoBackground.CUSTOM, customBackgroundUri = uri.toString())
                message = null
            } catch (_: SecurityException) {
                message = "This photo provider could not keep access to the image. Choose it from device storage."
            }
        }
    }
    fun start() {
        message = null
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else manager.start(request)
    }
    val canExport = offlineReady && !state.running &&
        (config.background != VideoBackground.CUSTOM || !config.customBackgroundUri.isNullOrBlank())

    val options: @Composable ColumnScope.() -> Unit = {
        SelectionGroup("Video shape", VideoAspectRatio.entries, config.aspectRatio, !state.running, { if (it == VideoAspectRatio.LANDSCAPE) "Landscape 16:9" else "Portrait 9:16" }) { config = config.copy(aspectRatio = it) }
        SelectionGroup("Resolution", VideoResolution.entries, config.resolution, !state.running, { if (it == VideoResolution.HD) "720p" else "1080p" }) { config = config.copy(resolution = it) }
        SelectionGroup("Visualization", VideoVisualization.entries, config.visualization, !state.running, ::visualizationLabel) { config = config.copy(visualization = it) }
        SelectionGroup("Style preset", VideoStylePreset.entries.filter { it != VideoStylePreset.CUSTOM }, config.stylePreset, !state.running,
            { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { config = preset(config, it) }
        if (config.stylePreset == VideoStylePreset.CUSTOM) AdaptiveText("Custom style adjustments", fontSize = 12.sp)
        SelectionGroup("Accent palette", VideoAccentPalette.entries, config.accentPalette, !state.running,
            { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { config = config.copy(accentPalette = it, stylePreset = VideoStylePreset.CUSTOM) }
        StyleSlider("Glow", config.glow, 0f..1f, !state.running) { config = config.copy(glow = it, stylePreset = VideoStylePreset.CUSTOM) }
        StyleSlider("Audio intensity", config.intensity, 0f..2f, !state.running) { config = config.copy(intensity = it, stylePreset = VideoStylePreset.CUSTOM) }
        StyleSlider("Visualizer size", config.visualizerScale, 0.65f..1.3f, !state.running) { config = config.copy(visualizerScale = it, stylePreset = VideoStylePreset.CUSTOM) }
        StyleSlider("Lyrics size", config.lyricsScale, 0.8f..1.4f, !state.running) { config = config.copy(lyricsScale = it, stylePreset = VideoStylePreset.CUSTOM) }
        StyleSlider("Background dim", config.backgroundDim, 0f..0.8f, !state.running) { config = config.copy(backgroundDim = it, stylePreset = VideoStylePreset.CUSTOM) }
        StyleSlider("Background motion", config.backgroundMotion, 0f..1f, !state.running) { config = config.copy(backgroundMotion = it, stylePreset = VideoStylePreset.CUSTOM) }
        SelectionGroup("Background", VideoBackground.entries, config.background, !state.running, ::backgroundLabel) { selected ->
            if (selected == VideoBackground.CUSTOM && config.customBackgroundUri.isNullOrBlank()) photo.launch(arrayOf("image/*"))
            else config = config.copy(background = selected)
        }
        if (config.background == VideoBackground.CUSTOM) {
            ExportButton("Choose background photo", !state.running) { photo.launch(arrayOf("image/*")) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                AdaptiveText("Include timed lyrics", fontWeight = FontWeight.SemiBold)
                AdaptiveText(if (cues.isEmpty()) "No synchronized timing is saved for this song." else "${cues.size} timed lines with your playback timing adjustment.", fontSize = 12.sp)
            }
            GlassToggle(includeLyrics && cues.isNotEmpty(), { includeLyrics = it },
                modifier = Modifier.testTag("export-include-lyrics").semantics { contentDescription = "Include timed lyrics" },
                enabled = cues.isNotEmpty() && !state.running)
        }
        if (cues.isEmpty()) {
            AdaptiveText("The video will contain the visualization and song details. Fetch timed lyrics or add timing in the lyrics editor to include words.", fontSize = 12.sp)
            ExportButton(if (loadingLyrics) "Finding timed lyrics" else "Find timed lyrics", !loadingLyrics && !state.running) {
                scope.launch {
                    loadingLyrics = true
                    try {
                        val result = fetchAndSaveLyrics(media, durationMs)
                        if (parseLrc(result.lyrics?.synced).lines.none { it.text.isNotBlank() }) message = "Synchronized lyrics are unavailable for this song. You can add timing in the lyrics editor."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "Lyrics could not load. Check your connection and try again." }
                    finally { loadingLyrics = false }
                }
            }
        }
        if (!offlineReady) {
            AdaptiveText("A complete offline download is required for reliable audio export.", fontWeight = FontWeight.SemiBold)
            when (record?.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                    LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                    AdaptiveText("Downloading song: ${(downloadProgress * 100).toInt()}%", fontSize = 12.sp)
                }
                else -> {
                    record?.error?.let { AdaptiveText(it, fontSize = 12.sp) }
                    ExportButton("Download song first", !state.running) { downloads.download(media) }
                }
            }
        }
        message?.let { AdaptiveText(it, fontSize = 13.sp) }
        state.error?.let { AdaptiveText(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
        if (state.running) {
            state.sourceTitle?.let { AdaptiveText("Exporting $it", fontWeight = FontWeight.SemiBold) }
            AdaptiveText(state.stage.ifBlank { "Preparing video" }, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            AdaptiveText("${(state.progress.coerceIn(0f, 1f) * 100).toInt()}% · Export continues when this window is closed.", fontSize = 12.sp)
        }
        state.outputUri?.takeIf { !state.running }?.let { uri ->
            AdaptiveText("Saved video: ${state.sourceTitle ?: "Music video"}", fontWeight = FontWeight.SemiBold)
            AdaptiveText("Saved to Movies/OurMusic", fontSize = 12.sp)
            ExportButton("Open saved video") {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { message = "No installed video app could open this file." }
            }
            ExportButton("Share saved video") {
                try {
                    val share = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Exported music video", uri) }
                    context.startActivity(Intent.createChooser(share, "Share music video").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { message = "The sharing menu could not open. The video is saved in Movies/OurMusic." }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), contentAlignment = Alignment.Center) {
            GlassSurface(
                Modifier.widthIn(max = 1100.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(24.dp), tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            AdaptiveText("Export music video", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            AdaptiveText("Fixed 60 FPS MP4 with audio", fontSize = 13.sp)
                        }
                        GlassIconButton(onDismiss) { AdaptiveIcon(Icons.Rounded.Close, "Close video export") }
                    }
                    HorizontalDivider()
                    BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        val wide = maxWidth >= 650.dp
                        val previewHeight = if (wide) (maxHeight - 120.dp).coerceIn(80.dp, 380.dp)
                            else (maxHeight * 0.29f).coerceIn(110.dp, 240.dp)
                        if (wide) Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AdaptiveText(request.title, fontWeight = FontWeight.SemiBold)
                                LiveVideoPreview(request, media, Modifier.fillMaxWidth(), previewHeight = previewHeight, compact = true)
                            }
                            Column(Modifier.weight(1f).verticalScroll(settingsScroll).testTag("video-export-settings").padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp), content = options)
                        } else Column(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AdaptiveText(request.title, fontWeight = FontWeight.SemiBold)
                                LiveVideoPreview(request, media, Modifier.fillMaxWidth(), previewHeight = previewHeight)
                            }
                            Column(Modifier.weight(1f, fill = false).verticalScroll(settingsScroll).testTag("video-export-settings").padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp), content = options)
                        }
                    }
                    HorizontalDivider()
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.running) {
                            AdaptiveText("${state.stage.ifBlank { "Preparing video" }} · ${(state.progress.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 12.sp)
                            LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        } else if (state.outputUri == null && state.error == null && state.stage.isNotBlank()) {
                            AdaptiveText(state.stage, fontSize = 12.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ExportButton("Close", modifier = Modifier.weight(1f), onClick = onDismiss)
                            if (state.running) ExportButton("Cancel export", modifier = Modifier.weight(1f)) { manager.cancel() }
                            else ExportButton("Export MP4", canExport, Modifier.weight(1f).testTag("video-export-start"), ::start)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> SelectionGroup(label: String, values: List<T>, selectedValue: T, enabled: Boolean, labelOf: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AdaptiveText(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { value ->
                val checked = selectedValue == value
                GlassButton(
                    onClick = { onSelect(value) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { selected = checked },
                    enabled = enabled,
                    role = Role.RadioButton,
                    shape = RoundedCornerShape(14.dp),
                    tint = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Unspecified,
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AdaptiveText(labelOf(value))
                        if (checked) AdaptiveIcon(Icons.Rounded.Check, "Selected", Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportButton(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassButton(onClick, modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 13.dp), contentAlignment = Alignment.Center) {
            AdaptiveText(label, fontWeight = FontWeight.SemiBold, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun visualizationLabel(value: VideoVisualization) = when (value) {
    VideoVisualization.CIRCULAR -> "Circular spectrum"
    VideoVisualization.SPECTRUM -> "Frequency spectrum"
    VideoVisualization.WAVEFORM -> "Stereo waveform"
    VideoVisualization.OSCILLOSCOPE -> "Oscilloscope"
}

private fun backgroundLabel(value: VideoBackground) = when (value) {
    VideoBackground.AURORA -> "Aurora"
    VideoBackground.MIDNIGHT -> "Midnight"
    VideoBackground.SUNSET -> "Sunset"
    VideoBackground.ARTWORK -> "Album artwork"
    VideoBackground.CUSTOM -> "Your photo"
}

@Composable
private fun StyleSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AdaptiveText(label, fontWeight = FontWeight.SemiBold)
            AdaptiveText("${(value * 100).toInt()}%", fontSize = 13.sp)
        }
        GlassSlider(value, onChange, valueRange = range, enabled = enabled, contentDescription = label)
    }
}

private fun preset(config: VideoExportConfig, preset: VideoStylePreset): VideoExportConfig = when (preset) {
    VideoStylePreset.NEON -> config.copy(stylePreset = preset, accentPalette = VideoAccentPalette.MINT, glow = 0.8f,
        intensity = 1.1f, visualizerScale = 1f, lyricsScale = 1f, backgroundDim = 0.3f, backgroundMotion = 0.6f)
    VideoStylePreset.SOFT -> config.copy(stylePreset = preset, accentPalette = VideoAccentPalette.OCEAN, glow = 0.25f,
        intensity = 0.7f, visualizerScale = 0.95f, lyricsScale = 1f, backgroundDim = 0.4f, backgroundMotion = 0.25f)
    VideoStylePreset.PRISM -> config.copy(stylePreset = preset, accentPalette = VideoAccentPalette.ROSE, glow = 1f,
        intensity = 1.4f, visualizerScale = 1.08f, lyricsScale = 1.1f, backgroundDim = 0.25f, backgroundMotion = 0.9f)
    VideoStylePreset.CUSTOM -> config.copy(stylePreset = preset)
}

private fun loadConfig(context: Context): VideoExportConfig {
    val saved = context.getSharedPreferences("video_export", Context.MODE_PRIVATE)
    fun <T : Enum<T>> read(name: String, values: List<T>, fallback: T) = values.firstOrNull { it.name == saved.getString(name, null) } ?: fallback
    return VideoExportConfig(
        aspectRatio = read("aspect", VideoAspectRatio.entries, VideoAspectRatio.LANDSCAPE),
        resolution = read("resolution", VideoResolution.entries, VideoResolution.HD),
        visualization = read("visualization", VideoVisualization.entries, VideoVisualization.CIRCULAR),
        background = read("background", VideoBackground.entries, VideoBackground.AURORA),
        customBackgroundUri = saved.getString("photo", null),
        accentPalette = read("palette", VideoAccentPalette.entries, VideoAccentPalette.MINT),
        stylePreset = read("style", VideoStylePreset.entries, VideoStylePreset.NEON),
        glow = saved.getFloat("glow", 0.7f), intensity = saved.getFloat("intensity", 1f),
        visualizerScale = saved.getFloat("visualizer_size", 1f), lyricsScale = saved.getFloat("lyrics_size", 1f),
        backgroundDim = saved.getFloat("background_dim", 0.35f), backgroundMotion = saved.getFloat("background_motion", 0.5f),
    ).normalized()
}

private fun saveConfig(context: Context, config: VideoExportConfig) {
    context.getSharedPreferences("video_export", Context.MODE_PRIVATE).edit()
        .putString("aspect", config.aspectRatio.name).putString("resolution", config.resolution.name)
        .putString("visualization", config.visualization.name).putString("background", config.background.name)
        .putString("photo", config.customBackgroundUri).putString("palette", config.accentPalette.name).putString("style", config.stylePreset.name)
        .putFloat("glow", config.glow).putFloat("intensity", config.intensity).putFloat("visualizer_size", config.visualizerScale)
        .putFloat("lyrics_size", config.lyricsScale).putFloat("background_dim", config.backgroundDim).putFloat("background_motion", config.backgroundMotion).apply()
}
