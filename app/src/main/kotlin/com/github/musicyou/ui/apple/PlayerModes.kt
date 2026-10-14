package com.github.musicyou.ui.apple

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.audio.visualization.AudioVisualizationFrame
import com.github.musicyou.models.LocalMenuState
import com.github.musicyou.ui.glass.*
import com.github.musicyou.ui.glass.AdaptiveText as Text
import com.github.musicyou.ui.glass.AdaptiveIcon as Icon
import com.github.musicyou.ui.visualization.AudioVisualization
import com.github.musicyou.ui.visualization.VisualizationMode
import com.github.musicyou.utils.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun rememberAudioVisualization(active: Boolean): AudioVisualizationFrame? {
    val binder = LocalPlayerServiceBinder.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val frames: Flow<AudioVisualizationFrame?> = remember(binder, active) {
        if (active) binder?.visualization ?: flowOf(null) else flowOf(null)
    }
    val frame by frames.collectAsState(initial = null)
    DisposableEffect(binder, lifecycle, active) {
        var subscription: AutoCloseable? = null
        fun update() {
            if (active && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (subscription == null) subscription = binder?.acquireVisualization()
            } else { subscription?.close(); subscription = null }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); subscription?.close() }
    }
    return if (active) frame else null
}

@Composable
internal fun AmbientBackdrop(media: MediaItem, frame: AudioVisualizationFrame?, modifier: Modifier = Modifier) {
    val energy by animateFloatAsState((frame?.rms ?: 0f).coerceIn(0f, 1f), spring(1f, 90f), label = "ambient energy")
    AsyncImage(
        model = media.mediaMetadata.artworkUri.thumbnail(600), contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize().graphicsLayer {
            scaleX = 1.08f + energy * 0.12f; scaleY = scaleX; alpha = 0.42f + energy * 0.18f
        }.blur(70.dp),
    )
}

@Composable
internal fun CarPlayer(media: MediaItem, settings: PlayerDisplaySettings, close: () -> Unit) {
    val binder = LocalPlayerServiceBinder.current ?: return
    val player = binder.player
    val menu = LocalMenuState.current
    val view = LocalView.current
    val frame = rememberAudioVisualization(settings.value.visualization != VisualizationMode.OFF)
    var playing by remember(player) { mutableStateOf(player.shouldBePlaying) }
    var error by remember(player) { mutableStateOf(playbackErrorMessage(player.playerError)) }
    player.DisposableListener { object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { playing = player.shouldBePlaying; error = playbackErrorMessage(player.playerError) }
    } }
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    GlassSurface(Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp)) {
        CarPlayerContent(
            media = media,
            playing = playing,
            frame = frame,
            visualization = settings.value.visualization,
            error = error,
            onClose = close,
            onOptions = { menu.display { PlayerDisplayOptions(settings, menu::hide) } },
            onPrevious = { player.forceSeekToPrevious() },
            onPlayPause = {
                if (playing) player.pause() else {
                    if (player.playbackState == Player.STATE_IDLE || player.playerError != null) player.prepare()
                    player.play()
                }
            },
            onNext = { player.forceSeekToNext() },
            onExit = { settings.update(settings.value.copy(car = false)) },
            modifier = Modifier.align(Alignment.Center).widthIn(max = 1_100.dp).fillMaxSize().systemBarsPadding(),
        )
    }
}

/** Fits all primary controls into either orientation without changing device orientation. */
@Composable
internal fun CarPlayerContent(
    media: MediaItem,
    playing: Boolean,
    frame: AudioVisualizationFrame?,
    visualization: VisualizationMode,
    onClose: () -> Unit,
    onOptions: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("car-player-content")) {
        val landscape = maxWidth > maxHeight && maxWidth >= 600.dp
        val compact = maxHeight < 700.dp
        val padding = if (landscape) 12.dp else 20.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = padding)) {
            CarHeader(onClose, onOptions)
            Spacer(Modifier.height(8.dp))
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(0.42f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CarArtwork(media, Modifier.weight(1f).fillMaxWidth())
                        if (visualization != VisualizationMode.OFF) {
                            Spacer(Modifier.height(8.dp))
                            AudioVisualization(frame, visualization, playing, Modifier.fillMaxWidth().height(if (visualization == VisualizationMode.BOTH) 125.dp else 90.dp))
                        }
                    }
                    BoxWithConstraints(Modifier.weight(0.58f).fillMaxHeight()) {
                        val playSize = if (maxHeight < 240.dp) 80.dp else 96.dp
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                            CarTrackInfo(media, error, compact = true, centered = false)
                            CarTransport(playing, onPrevious, onPlayPause, onNext, playSize, 72.dp)
                            CarExit(onExit)
                        }
                    }
                }
            } else if (compact) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CarArtwork(media, Modifier.size(100.dp))
                        Box(Modifier.weight(1f)) { CarTrackInfo(media, error, compact = true, centered = false) }
                    }
                    CarTransport(playing, onPrevious, onPlayPause, onNext, 96.dp, 72.dp)
                    if (visualization != VisualizationMode.OFF) {
                        AudioVisualization(frame, visualization, playing, Modifier.fillMaxWidth().height(if (visualization == VisualizationMode.BOTH) 125.dp else 100.dp))
                    }
                    CarExit(onExit)
                }
            } else {
                CarArtwork(media, Modifier.weight(1f).fillMaxWidth().padding(bottom = 12.dp))
                CarTrackInfo(media, error, compact = false, centered = true)
                Spacer(Modifier.height(12.dp))
                CarTransport(playing, onPrevious, onPlayPause, onNext, 112.dp, 84.dp)
                if (visualization != VisualizationMode.OFF) {
                    Spacer(Modifier.height(12.dp))
                    AudioVisualization(frame, visualization, playing, Modifier.fillMaxWidth().height(if (visualization == VisualizationMode.BOTH) 145.dp else 115.dp))
                }
                Spacer(Modifier.height(8.dp))
                CarExit(onExit)
            }
        }
    }
}

@Composable
private fun CarHeader(onClose: () -> Unit, onOptions: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose, modifier = Modifier.size(64.dp).testTag("car-close")) {
            Icon(Icons.Outlined.Close, "Close player", Modifier.size(32.dp))
        }
        Text("Car mode", Modifier.weight(1f).padding(horizontal = 12.dp), fontSize = 22.sp, lineHeight = 26.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        GlassIconButton(onClick = onOptions, modifier = Modifier.size(64.dp).testTag("car-options")) {
            Icon(Icons.Outlined.Settings, "Player options", Modifier.size(30.dp))
        }
    }
}

@Composable
private fun CarArtwork(media: MediaItem, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val edge = minOf(maxWidth, maxHeight, 420.dp).coerceAtLeast(0.dp)
        AsyncImage(media.mediaMetadata.artworkUri.thumbnail(800), "Album artwork",
            Modifier.size(edge).clip(RoundedCornerShape(20.dp)).testTag("car-artwork"), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun CarTrackInfo(media: MediaItem, error: String?, compact: Boolean, centered: Boolean) {
    val alignment = if (centered) TextAlign.Center else TextAlign.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(media.mediaMetadata.title?.toString().orEmpty(), Modifier.fillMaxWidth().testTag("car-song-title"),
            fontSize = if (compact) 24.sp else 30.sp, lineHeight = if (compact) 28.sp else 34.sp,
            fontWeight = FontWeight.Bold, maxLines = if (compact) 1 else 2, textAlign = alignment, overflow = TextOverflow.Ellipsis)
        Text(media.mediaMetadata.artist?.toString().orEmpty(), Modifier.fillMaxWidth().testTag("car-song-artist"),
            fontSize = if (compact) 17.sp else 20.sp, lineHeight = if (compact) 22.sp else 26.sp,
            color = MusicSecondary, maxLines = 1, textAlign = alignment, overflow = TextOverflow.Ellipsis)
        error?.let { Text(it, Modifier.fillMaxWidth().padding(top = 4.dp), fontSize = 13.sp, lineHeight = 17.sp,
            color = MusicRed, maxLines = if (compact) 1 else 2, textAlign = alignment, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun CarTransport(
    playing: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    playSize: Dp,
    skipSize: Dp,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(onClick = onPrevious, modifier = Modifier.size(skipSize).testTag("car-previous")) {
            Icon(Icons.Filled.SkipPrevious, "Previous song", Modifier.size(42.dp))
        }
        GlassIconButton(onClick = onPlayPause, modifier = Modifier.size(playSize).testTag("car-play-pause")) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(if (playSize >= 96.dp) 56.dp else 46.dp))
        }
        GlassIconButton(onClick = onNext, modifier = Modifier.size(skipSize).testTag("car-next")) {
            Icon(Icons.Filled.SkipNext, "Next song", Modifier.size(42.dp))
        }
    }
}

@Composable
private fun CarExit(onExit: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth().height(64.dp).testTag("car-exit")) {
        Text("Exit Car mode", fontSize = 17.sp, lineHeight = 22.sp, color = MusicRed)
    }
}
