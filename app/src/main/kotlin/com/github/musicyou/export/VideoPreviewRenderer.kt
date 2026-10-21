package com.github.musicyou.export

import android.content.Context
import android.graphics.Canvas
import com.github.musicyou.audio.visualization.AudioVisualizationFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Draws the export's exact layout directly into a live preview's hardware canvas. */
class VideoPreviewRenderer private constructor(private var painter: VideoFramePainter?) : AutoCloseable {
    private var references = 1
    private var ownerReleased = false

    @Synchronized
    fun draw(canvas: Canvas, frame: AudioVisualizationFrame?, timeUs: Long, config: VideoExportConfig) {
        painter?.drawTo(canvas, frame, timeUs, config)
    }

    /** The native view keeps its renderer alive until its next update or final release. */
    @Synchronized
    internal fun retain(): Boolean {
        if (references == 0) return false
        references++
        return true
    }

    @Synchronized
    internal fun release() {
        check(references > 0)
        references--
        if (references == 0) {
            painter?.close()
            painter = null
        }
    }

    @Synchronized
    override fun close() {
        if (ownerReleased) return
        ownerReleased = true
        release()
    }

    companion object {
        suspend fun create(context: Context, request: VideoExportRequest, width: Int, height: Int): VideoPreviewRenderer = withContext(Dispatchers.IO) {
            require(width > 0 && height > 0)
            VideoPreviewRenderer(VideoFramePainter.create(context.applicationContext, request, width, height))
        }
    }
}
