package com.github.musicyou.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** The preview and encoded frames use the same media time without a separate animation clock. */
internal class VideoProgressOverlay {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    fun draw(
        canvas: Canvas,
        timeUs: Long,
        durationUs: Long,
        width: Int,
        height: Int,
        unit: Float,
        accent: Int,
        config: VideoExportConfig,
    ) {
        if (!config.showProgressBar || durationUs <= 0L) return
        val position = VideoProgressTimeline.positionUs(timeUs, durationUs)
        val fraction = VideoProgressTimeline.fraction(timeUs, durationUs).toFloat()
        val edge = (minOf(position, durationUs - position) / 180_000.0).coerceIn(0.0, 1.0).toFloat()
        val opacity = .6f + .4f * edge * edge * (3f - 2f * edge)
        val left = width * .1f
        val right = width * .9f
        val y = height - 30f * unit
        val head = left + (right - left) * fraction
        fun tint(color: Int, alpha: Float) = Color.argb(
            (alpha * opacity * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color),
        )

        paint.color = tint(Color.BLACK, .35f)
        paint.strokeWidth = 12f * unit
        canvas.drawLine(left, y, right, y, paint)
        paint.color = tint(Color.WHITE, .25f)
        paint.strokeWidth = 4f * unit
        canvas.drawLine(left, y, right, y, paint)
        if (fraction > 0f) {
            paint.color = tint(accent, .2f * config.glow)
            paint.strokeWidth = (6f + 8f * config.glow) * unit
            canvas.drawLine(left, y, head, y, paint)
            paint.color = tint(accent, 1f)
            paint.strokeWidth = 4f * unit
            canvas.drawLine(left, y, head, y, paint)
        }
        paint.color = tint(accent, .2f * config.glow)
        canvas.drawCircle(head, y, (6f + 5f * config.glow) * unit, paint)
        paint.color = tint(Color.WHITE, 1f)
        canvas.drawCircle(head, y, 3.5f * unit, paint)

        text.textSize = 13f * unit
        text.color = tint(Color.WHITE, .9f)
        text.setShadowLayer(3f * unit, 0f, unit, Color.BLACK)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(formatTime(position), left, y - 14f * unit, text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("${formatTime(durationUs - position, roundUp = true)} remaining", right, y - 14f * unit, text)
    }

    private fun formatTime(timeUs: Long, roundUp: Boolean = false): String {
        val seconds = timeUs / 1_000_000L + if (roundUp && timeUs % 1_000_000L > 0L) 1L else 0L
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}
