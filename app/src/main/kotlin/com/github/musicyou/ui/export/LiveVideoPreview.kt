package com.github.musicyou.ui.export

import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.audio.visualization.AudioVisualizationFrame
import com.github.musicyou.export.VideoAspectRatio
import com.github.musicyou.export.VideoExportConfig
import com.github.musicyou.export.VideoExportRequest
import com.github.musicyou.export.VideoPreviewRenderer
import com.github.musicyou.ui.apple.rememberAudioVisualization
import com.github.musicyou.ui.glass.AdaptiveText
import com.github.musicyou.ui.glass.GlassButton
import com.github.musicyou.ui.glass.GlassSlider
import com.github.musicyou.ui.visualization.VisualizationFrameRate
import com.github.musicyou.utils.forcePlay
import com.github.musicyou.utils.positionAndDurationState
import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Uses the export painter and the audible PCM timeline without copying frame bitmaps. */
@Composable
internal fun LiveVideoPreview(request: VideoExportRequest, media: MediaItem, modifier: Modifier = Modifier,
    previewHeight: Dp = if (request.config.aspectRatio == VideoAspectRatio.PORTRAIT) 280.dp else 220.dp,
    compact: Boolean = false,
) {
    val player = LocalPlayerServiceBinder.current?.player
    var playback by remember(player) { mutableStateOf(player.previewPlayback()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { playback = player.previewPlayback() }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    var visible by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val matches = playback.mediaId == request.mediaId
    val active = visible && foreground && matches && playback.playing
    val frame = rememberAudioVisualization(active)
    val frameProvider = remember(frame, matches) { { if (matches) frame.value else null } }
    val positionProvider = remember(player, matches, playback.positionMs) { { if (matches) (player?.currentPosition ?: 0L).coerceAtLeast(0L) * 1_000L else 0L } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LiveVideoPreviewSurface(
            request = request,
            frameProvider = frameProvider,
            positionProvider = positionProvider,
            active = active,
            enabled = visible && foreground,
            retainFrame = matches,
            modifier = Modifier.fillMaxWidth().height(previewHeight)
                .onGloballyPositioned { visible = !it.boundsInWindow().isEmpty },
        )
        AdaptiveText(
            when {
                player == null -> "Open the song in the player to preview its audio and lyrics."
                !matches -> "Play this song to preview its audio and timed lyrics."
                playback.playing -> "Live preview follows the song. These controls change playback."
                else -> "Preview paused with the song. Press Play song to continue."
            },
            fontSize = 12.sp,
        )
        if (player != null) {
            if (matches) PreviewTransport(player, compact)
            else PreviewAction("Play this song") { player.forcePlay(media) }
        }
    }
}

private data class PreviewPlayback(val mediaId: String?, val playing: Boolean, val positionMs: Long)
private fun Player?.previewPlayback() = PreviewPlayback(this?.currentMediaItem?.mediaId, this?.isPlaying == true, this?.currentPosition ?: 0L)

@Composable
private fun PreviewTransport(player: Player, compact: Boolean) {
    val position by player.positionAndDurationState()
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var seek by remember(player.currentMediaItem?.mediaId) { mutableStateOf<Float?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val duration = position.second.coerceAtLeast(1L)
    if (!compact) GlassSlider(
        value = seek ?: (position.first.toFloat() / duration).coerceIn(0f, 1f),
        onValueChange = { seek = it },
        onValueChangeFinished = { seek?.let { player.seekTo((it * duration).toLong()) }; seek = null },
        onValueChangeCanceled = { seek = null }, enabled = position.second > 0,
        contentDescription = "Song playback position in video preview",
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        AdaptiveText("${timestamp(position.first)} / ${timestamp(position.second)}", fontSize = 12.sp)
        PreviewAction(if (playing) "Pause song" else "Play song") {
            if (playing) player.pause() else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
        }
    }
}

private fun timestamp(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000L
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun PreviewAction(label: String, onClick: () -> Unit) {
    GlassButton(onClick, shape = RoundedCornerShape(14.dp)) {
        AdaptiveText(label, Modifier.padding(horizontal = 18.dp, vertical = 13.dp))
    }
}

/** The native view can render at display cadence without recomposing editor settings. */
@Composable
internal fun LiveVideoPreviewSurface(
    request: VideoExportRequest,
    frameProvider: () -> AudioVisualizationFrame?,
    positionProvider: () -> Long,
    active: Boolean,
    modifier: Modifier = Modifier,
    onView: ((VideoPreviewView) -> Unit)? = null,
    enabled: Boolean = true,
    retainFrame: Boolean = true,
) {
    VisualizationFrameRate(active && enabled)
    val context = LocalContext.current.applicationContext
    var size by remember { mutableStateOf(IntSize.Zero) }
    var renderer by remember { mutableStateOf<VideoPreviewRenderer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val rendererOwner = remember { PreviewRendererOwner() }
    DisposableEffect(rendererOwner) { onDispose { rendererOwner.close() } }
    // Style values are passed directly into each draw; only asset/layout changes reload images.
    val assetRequest = remember(request.mediaId, request.title, request.artist, request.artworkUri, request.lyrics,
        request.config.aspectRatio, request.config.background, request.config.customBackgroundUri) { request }
    LaunchedEffect(assetRequest, size, enabled) {
        renderer = null
        rendererOwner.replace(null)
        error = null
        if (!enabled || size.width <= 0 || size.height <= 0) return@LaunchedEffect
        var created: VideoPreviewRenderer? = null
        var handedOff = false
        try {
            withContext(Dispatchers.IO) { created = VideoPreviewRenderer.create(context, assetRequest, size.width, size.height) }
            ensureActive()
            if (rendererOwner.replace(created)) {
                renderer = created
                handedOff = true
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "The live preview could not load. Choose another background or photo." }
        finally { if (!handedOff) created?.close() }
    }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF101820)).testTag("video-export-preview"), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { VideoPreviewView(it).also { view -> onView?.invoke(view) } },
            modifier = Modifier.fillMaxSize().onGloballyPositioned {
                val ratio = request.config.width.toFloat() / request.config.height
                val width = minOf(it.size.width, (it.size.height * ratio).toInt()).coerceAtLeast(1)
                size = IntSize(width, (width / ratio).toInt().coerceAtLeast(1))
            },
            update = { view -> view.update(renderer, request.config, frameProvider, positionProvider, active, retainFrame) },
            onRelease = { it.release() },
        )
        if (renderer == null && enabled) {
            if (error == null) CircularProgressIndicator()
            else AdaptiveText(error.orEmpty(), Modifier.padding(16.dp), color = Color.White)
        }
    }
}

/** Owns asynchronous results even if disposal happens before their next recomposition. */
private class PreviewRendererOwner : AutoCloseable {
    private var renderer: VideoPreviewRenderer? = null
    private var closed = false

    fun replace(next: VideoPreviewRenderer?): Boolean {
        if (closed) {
            next?.close()
            return false
        }
        if (next !== renderer) {
            val previous = renderer
            renderer = next
            previous?.close()
        }
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        renderer?.close()
        renderer = null
    }
}

internal class VideoPreviewView(context: Context) : View(context), Choreographer.FrameCallback {
    private var renderer: VideoPreviewRenderer? = null
    private var config = VideoExportConfig()
    private var frames: () -> AudioVisualizationFrame? = { null }
    private var position: () -> Long = { 0L }
    private var active = false
    private var retainFrame = true
    private var scheduled = false
    private var lastVsyncNanos = Long.MIN_VALUE
    private var held: AudioVisualizationFrame? = null
    @Volatile var renderedFrames = 0L
        private set
    @Volatile var rendererChanges = 0L
        private set

    fun update(renderer: VideoPreviewRenderer?, config: VideoExportConfig, frames: () -> AudioVisualizationFrame?, position: () -> Long, active: Boolean, retainFrame: Boolean = true) {
        if (renderer !== this.renderer) {
            val next = renderer?.takeIf { it.retain() }
            val previous = this.renderer
            this.renderer = next
            if (next != null) rendererChanges++
            previous?.release()
        }
        this.config = config
        this.frames = frames
        this.position = position
        this.active = active
        this.retainFrame = retainFrame
        if (!retainFrame) held = null
        updateScheduling()
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateScheduling() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateScheduling() }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        if (!canAnimate()) return
        if (lastVsyncNanos == Long.MIN_VALUE || frameTimeNanos - lastVsyncNanos >= 15_000_000L) {
            lastVsyncNanos = frameTimeNanos
            invalidate()
        }
        updateScheduling()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(android.graphics.Color.rgb(16, 24, 32))
        val renderer = renderer ?: return
        val frame = frames()
        val timeUs = position()
        if (frame?.ready == true && retainFrame) held = frame
        else if (held?.let { kotlin.math.abs(it.positionUs - timeUs) > 250_000L } != false) held = null
        val ratio = config.width.toFloat() / config.height
        val contentWidth = minOf(width.toFloat(), height * ratio)
        val contentHeight = contentWidth / ratio
        canvas.save()
        canvas.translate((width - contentWidth) / 2f, (height - contentHeight) / 2f)
        canvas.clipRect(0f, 0f, contentWidth, contentHeight)
        renderer.draw(canvas, frame ?: held, timeUs, config)
        canvas.restore()
        renderedFrames++
    }

    private fun canAnimate() = active && renderer != null && isAttachedToWindow && windowVisibility == VISIBLE && isShown
    private fun updateScheduling() {
        if (canAnimate()) {
            if (!scheduled) { scheduled = true; Choreographer.getInstance().postFrameCallback(this) }
        } else stop()
    }
    private fun stop() {
        Choreographer.getInstance().removeFrameCallback(this)
        scheduled = false
        lastVsyncNanos = Long.MIN_VALUE
    }
    fun release() {
        active = false
        stop()
        val previous = renderer
        renderer = null
        previous?.release()
        held = null
        frames = { null }
        position = { 0L }
    }
}
