package com.github.musicyou.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import com.github.musicyou.audio.visualization.AudioOscilloscopeFrame
import com.github.musicyou.audio.visualization.AudioVisualizationFrame
import com.github.musicyou.audio.visualization.CircularSpectrumGeometry
import com.github.musicyou.audio.visualization.SphereInteriorGeometry
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayInputStream
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The same deterministic layout is used for the editor preview and every encoded frame. */
internal class VideoFramePainter private constructor(
    private val request: VideoExportRequest,
    val width: Int,
    val height: Int,
    private var backgroundImage: Bitmap?,
    private var artwork: Bitmap?,
) : AutoCloseable {
    private var exportBitmap: Bitmap? = null
    private var exportCanvas: Canvas? = null
    private var canvas = Canvas()
    private var config = request.config.normalized()
    private val unit = min(width, height) / 720f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val path = Path()
    private val circle = CircularSpectrumGeometry()
    private val interior = SphereInteriorGeometry()
    private val filamentPath = Path()
    private val displaySpectrum = DisplaySpectrum()
    private val progressOverlay = VideoProgressOverlay()
    private var lastScopeSequence = Long.MIN_VALUE
    private var lastScopeSource: AudioVisualizationFrame? = null
    private var lastScopeFrame: AudioOscilloscopeFrame? = null
    private val cues = VideoLyricMotion.normalize(request.lyrics)
    private val layouts = HashMap<Int, StaticLayout>()
    private val titleLayout = layout(request.title, 30f * unit, width - (96f * unit).toInt(), Typeface.BOLD)
    private val artistLayout = layout(request.artist, 20f * unit, width - (96f * unit).toInt(), Typeface.NORMAL)
    private val noLyricsLayout = layout("Visualization from the original audio", 18f * unit, (width * .83f).toInt(), Typeface.NORMAL)
    private val portrait = height > width

    fun draw(frame: AudioVisualizationFrame?, timeUs: Long): Bitmap {
        val bitmap = exportBitmap ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { exportBitmap = it }
        val target = exportCanvas ?: Canvas(bitmap).also { exportCanvas = it }
        drawTo(target, frame, timeUs)
        return bitmap
    }

    fun drawTo(target: Canvas, frame: AudioVisualizationFrame?, timeUs: Long, style: VideoExportConfig = config) {
        if (style != config) {
            val nextConfig = style.normalized()
            if (nextConfig.lyricsScale != config.lyricsScale) layouts.clear()
            config = nextConfig
        }
        canvas = target
        val seconds = timeUs / 1_000_000f
        drawBackground(seconds * config.backgroundMotion * 2f)
        val upperDim = ((.06f + config.backgroundDim * .35f) * 255).toInt().shl(24)
        val lowerDim = ((.25f + config.backgroundDim * .75f) * 255).toInt().shl(24)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(upperDim, upperDim, lowerDim), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint); paint.shader = null
        val centerX = width / 2f
        val centerY = height * if (portrait) .40f else .43f
        val radius = min(width * .25f, height * .245f) * config.visualizerScale
        when (config.visualization) {
            VideoVisualization.CIRCULAR -> drawCircular(frame, centerX, centerY, radius)
            VideoVisualization.SPECTRUM -> drawSpectrum(frame, centerY)
            VideoVisualization.WAVEFORM -> drawWaveform(frame, centerY, scope = false)
            VideoVisualization.OSCILLOSCOPE -> drawWaveform(frame, centerY, scope = true)
        }
        canvas.save(); canvas.translate((width - titleLayout.width) / 2f, 38f * unit); titleLayout.draw(canvas)
        canvas.translate((titleLayout.width - artistLayout.width) / 2f, titleLayout.height + 8f * unit)
        paint.alpha = 220; artistLayout.draw(canvas); paint.alpha = 255; canvas.restore()
        drawLyrics(timeUs)
        progressOverlay.draw(canvas, timeUs, request.durationUs, width, height, unit, config.accentPalette.argb, config)
    }

    private fun drawBackground(seconds: Float) {
        canvas.drawColor(0xFF07101F.toInt())
        val image = backgroundImage
        if (image != null) {
            cover(image, RectF(0f, 0f, width.toFloat(), height.toFloat()))
            return
        }
        val colors = when (config.background) {
            VideoBackground.SUNSET -> intArrayOf(0xFF25113E.toInt(), 0xFFAE416B.toInt(), 0xFFED9C67.toInt())
            VideoBackground.MIDNIGHT -> intArrayOf(0xFF050F2B.toInt(), 0xFF123E62.toInt(), 0xFF32647E.toInt())
            else -> intArrayOf(0xFF090E32.toInt(), 0xFF42336F.toInt(), 0xFF157E83.toInt())
        }
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), colors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = RadialGradient(width * (.25f + .10f * sin(seconds * .16f)), height * (.4f + .12f * cos(seconds * .13f)), width * .8f, 0x6076E5C8, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = RadialGradient(width * .8f, height * (.65f + .1f * sin(seconds * .2f)), width * .6f, 0x605D3CBA, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint); paint.shader = null
    }

    private fun drawCircular(frame: AudioVisualizationFrame?, cx: Float, cy: Float, radius: Float) {
        displaySpectrum.source = frame?.spectrum.orEmpty()
        displaySpectrum.gain = config.intensity
        circle.fill(displaySpectrum, (frame?.rms ?: 0f) * config.intensity, (frame?.peak ?: 0f) * config.intensity, cx, cy, radius)
        path.reset()
        val points = circle.coordinates
        for (index in points.indices step 2) {
            if (index == 0) path.moveTo(points[index], points[index + 1]) else path.lineTo(points[index], points[index + 1])
        }
        path.close()
        paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND
        paint.color = accent(.28f * config.glow); paint.strokeWidth = (2 + 20 * config.glow) * unit; canvas.drawPath(path, paint)
        paint.color = accent(.55f * config.glow); paint.strokeWidth = (2 + 9 * config.glow) * unit; canvas.drawPath(path, paint)
        paint.color = accent(1f, .65f); paint.strokeWidth = 2.5f * unit; canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        val album = artwork
        if (album != null) {
            canvas.save(); path.reset(); path.addCircle(cx, cy, radius * .79f, Path.Direction.CW); canvas.clipPath(path)
            cover(album, RectF(cx - radius * .79f, cy - radius * .79f, cx + radius * .79f, cy + radius * .79f)); canvas.restore()
            paint.color = 0xB0101C26.toInt(); canvas.drawCircle(cx, cy, radius * .79f, paint)
        } else {
            paint.shader = RadialGradient(cx, cy, radius, intArrayOf(0xAA213E4A.toInt(), 0x99101B30.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, radius * .79f, paint); paint.shader = null
        }
        val energy = ((frame?.rms ?: 0f) * config.intensity).coerceIn(0f, 1f)
        interior.fill(displaySpectrum, energy, frame?.positionUs ?: 0L, cx, cy, radius)
        canvas.save(); path.reset(); path.addCircle(cx, cy, radius * .8f, Path.Direction.CW); canvas.clipPath(path)
        paint.style = Paint.Style.STROKE
        for (strand in 0 until interior.strandCount) {
            val coordinates = interior.strands[strand]
            filamentPath.reset()
            for (point in 0 until interior.pointsPerStrand) {
                if (point == 0) filamentPath.moveTo(coordinates[0], coordinates[1])
                else filamentPath.lineTo(coordinates[point * 2], coordinates[point * 2 + 1])
            }
            paint.color = accent((.06f + energy * .08f) * config.glow); paint.strokeWidth = 4f * unit; canvas.drawPath(filamentPath, paint)
            paint.color = accent(.20f + energy * .28f, .3f); paint.strokeWidth = .8f * unit; canvas.drawPath(filamentPath, paint)
        }
        paint.style = Paint.Style.FILL
        for (particle in 0 until interior.particleCount) {
            val depth = interior.particles[particle * 3 + 2]
            paint.color = accent(depth * (.4f + energy * .5f), .4f)
            canvas.drawCircle(interior.particles[particle * 3], interior.particles[particle * 3 + 1], (.6f + depth * .8f) * unit, paint)
        }
        canvas.restore()
    }

    private fun drawSpectrum(frame: AudioVisualizationFrame?, cy: Float) {
        val spectrum = frame?.spectrum.orEmpty()
        val count = spectrum.size.takeIf { it > 0 } ?: 32
        val left = width * .10f
        val graphWidth = width * .80f
        val gap = 4f * unit
        val bar = graphWidth / count
        paint.shader = LinearGradient(0f, cy - height * .2f, 0f, cy + height * .12f, accent(1f, .35f), accent(.75f), Shader.TileMode.CLAMP)
        repeat(count) { index ->
            val value = spectrum.getOrElse(index) { 0f }
            val size = maxOf(2f * unit, value * config.intensity * height * .30f * config.visualizerScale)
            canvas.drawRoundRect(left + index * bar, cy + height * .15f - size, left + (index + 1) * bar - gap, cy + height * .15f, 3f * unit, 3f * unit, paint)
        }
        paint.shader = null
    }

    private fun drawWaveform(frame: AudioVisualizationFrame?, cy: Float, scope: Boolean) {
        val left = width * .10f; val right = width * .90f
        val amplitude = height * .18f * config.visualizerScale
        val scopeFrame = if (scope) scopeFrame(frame) else frame?.oscilloscope
        if (scope) {
            paint.color = 0x3089E8D0; paint.strokeWidth = unit
            repeat(11) { val x = left + (right - left) * it / 10; canvas.drawLine(x, cy - amplitude, x, cy + amplitude, paint) }
            repeat(9) { val y = cy - amplitude + amplitude * 2 * it / 8; canvas.drawLine(left, y, right, y, paint) }
            text.textSize = 13f * unit; text.textAlign = Paint.Align.LEFT; text.color = 0xC0FFFFFF.toInt()
            val scale = scopeFrame?.configuration?.fullScalePerDivision ?: request.oscilloscopeConfig.fullScalePerDivision
            val voltsPerFullScale = scopeFrame?.configuration?.calibratedVoltsPerFullScale
            val value = scale * (voltsPerFullScale ?: 1f)
            val unitLabel = if (voltsPerFullScale == null) "FS" else "V"
            val timebase = (scopeFrame?.configuration?.timebaseUsPerDivision ?: request.oscilloscopeConfig.timebaseUsPerDivision) / 1000f
            canvas.drawText("$value $unitLabel/div  ·  $timebase ms/div", left, cy - amplitude - 14f * unit, text)
        }
        val waves = frame?.waveformChannels.orEmpty().take(2)
        val channels = if (scope) scopeFrame?.channels.orEmpty().take(2) else emptyList()
        val laneCount = if (scope) 1 else maxOf(1, waves.size)
        repeat(if (scope) maxOf(1, channels.size) else laneCount) { channelIndex ->
            val laneCenter = if (laneCount == 1) cy else cy + (if (channelIndex == 0) -.52f else .52f) * amplitude
            val laneAmplitude = amplitude * if (laneCount == 1) 1f else .44f
            val channel = channels.getOrNull(channelIndex)
            val wave = waves.getOrNull(channelIndex)
            val count = if (scope) channel?.size ?: 0 else minOf(wave?.minimum?.size ?: 0, wave?.maximum?.size ?: 0)
            path.reset()
            if (count < 2) { path.moveTo(left, laneCenter); path.lineTo(right, laneCenter) }
            else repeat(count) { index ->
                val fraction = if (scope && scopeFrame != null && scopeFrame.sampleRate > 0 && scopeFrame.durationUs > 0) {
                    ((index - scopeFrame.originSamples) / (scopeFrame.durationUs * scopeFrame.sampleRate / 1_000_000.0)).toFloat()
                } else index.toFloat() / (count - 1)
                val x = left + (right - left) * fraction
                if (scope) {
                    val scale = (scopeFrame?.configuration?.fullScalePerDivision ?: request.oscilloscopeConfig.fullScalePerDivision) * 4
                    val y = cy - channel!![index] / scale * amplitude
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                } else {
                    // Preserve the signed native envelope of each channel; opposite channels never cancel.
                    path.moveTo(x, laneCenter - wave!!.minimum[index] * laneAmplitude * config.intensity)
                    path.lineTo(x, laneCenter - wave.maximum[index] * laneAmplitude * config.intensity)
                }
            }
            canvas.save(); canvas.clipRect(left, laneCenter - laneAmplitude, right, laneCenter + laneAmplitude)
            val tint = if (scope && channelIndex == 1) .15f else .65f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (2f + 9f * config.glow) * unit; paint.color = accent(.35f * config.glow); canvas.drawPath(path, paint)
            paint.strokeWidth = 2f * unit; paint.color = accent(1f, tint); canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            canvas.restore()
            if (!scope && laneCount == 2) {
                text.textSize = 13f * unit; text.textAlign = Paint.Align.LEFT; text.color = 0xC0FFFFFF.toInt()
                canvas.drawText(if (channelIndex == 0) "L" else "R", left, laneCenter - laneAmplitude - 4f * unit, text)
            }
        }
    }

    private fun scopeFrame(frame: AudioVisualizationFrame?): AudioOscilloscopeFrame? {
        if (frame == null) return null
        if (lastScopeSource === frame && lastScopeSequence == frame.sequence) return lastScopeFrame
        lastScopeSource = frame
        lastScopeSequence = frame.sequence
        lastScopeFrame = videoOscilloscopeCapture(frame, request.oscilloscopeConfig)
        return lastScopeFrame
    }

    private fun drawLyrics(timeUs: Long) {
        val motion = VideoLyricMotion.sample(cues, timeUs)
        val index = motion.currentIndex
        val bottom = height - (if (config.showProgressBar) 76f else 32f) * unit
        val gap = 20f * unit
        fun paragraph(index: Int): StaticLayout? = cues.getOrNull(index)?.let { cue ->
            layouts.getOrPut(index) { layout(cue.text, (if (portrait) 37f else 33f) * unit * config.lyricsScale, (width * .83f).toInt(), Typeface.BOLD) }
        }
        fun anchor(paragraph: StaticLayout) = min(height * if (portrait) .70f else .735f, bottom - paragraph.height).coerceAtLeast(0f)
        fun draw(paragraph: StaticLayout, top: Float, opacity: Float) {
            val alpha = paragraph.paint.alpha
            paragraph.paint.alpha = opacity.toInt().coerceIn(0, 255)
            canvas.save(); canvas.translate((width - paragraph.width) / 2f, top); paragraph.draw(canvas); canvas.restore()
            paragraph.paint.alpha = alpha
        }
        val current = paragraph(index)
        val next = paragraph(index + 1)
        canvas.save(); canvas.clipRect(0f, 0f, width.toFloat(), bottom)
        if (current != null) {
            val top = anchor(current)
            val nextTop = next?.let(::anchor) ?: top
            // Both rows finish their movement at the cue, including when line wrapping differs.
            draw(current, top + (nextTop - current.height - gap - top) * motion.advance, 255f - 125f * motion.advance)
            paragraph(index - 1)?.let { previous ->
                draw(previous, top - previous.height - gap - 6f * unit * motion.reveal, 130f * (1f - motion.reveal))
            }
            if (next != null) {
                val previewTop = top + current.height + gap
                val previewAlpha = if (previewTop + next.height <= bottom) 130f * motion.reveal else 0f
                draw(next, previewTop + (nextTop - previewTop) * motion.advance, previewAlpha + (255f - previewAlpha) * motion.advance)
            }
        } else if (next != null) {
            draw(next, anchor(next) + (next.height + gap) * (1f - motion.advance), 255f * motion.advance)
        } else if (cues.isEmpty()) draw(noLyricsLayout, anchor(noLyricsLayout), 255f)
        canvas.restore()
    }

    private fun layout(value: String, size: Float, maxWidth: Int, style: Int): StaticLayout {
        val paragraph = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = size; typeface = Typeface.create("sans-serif", style)
            setShadowLayer(4f * unit, 0f, 2f * unit, 0xB0000000.toInt())
        }
        return StaticLayout.Builder.obtain(value, 0, value.length, paragraph, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setMaxLines(4).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
    }

    private fun cover(image: Bitmap, destination: RectF) {
        val scale = maxOf(destination.width() / image.width, destination.height() / image.height)
        val w = image.width * scale; val h = image.height * scale
        val target = RectF(destination.centerX() - w / 2, destination.centerY() - h / 2, destination.centerX() + w / 2, destination.centerY() + h / 2)
        paint.color = Color.WHITE; paint.alpha = 255
        canvas.save(); canvas.clipRect(destination); canvas.drawBitmap(image, null, target, paint); canvas.restore()
    }

    private fun accent(alpha: Float, white: Float = 0f): Int {
        val base = config.accentPalette.argb
        fun component(value: Int) = (value + (255 - value) * white).toInt().coerceIn(0, 255)
        return Color.argb((alpha * 255).toInt().coerceIn(0, 255), component(Color.red(base)), component(Color.green(base)), component(Color.blue(base)))
    }

    override fun close() {
        exportBitmap?.recycle()
        exportBitmap = null
        exportCanvas = null
        canvas = Canvas()
        // Hardware display lists can retain these images after their view or painter is replaced.
        // Dropping our references lets the graphics pipeline release them after its last draw.
        backgroundImage = null
        artwork = null
        layouts.clear()
    }

    companion object {
        fun create(context: Context, request: VideoExportRequest, width: Int = request.config.width, height: Int = request.config.height): VideoFramePainter {
            val artwork = request.artworkUri?.let { loadBitmap(context, it) }
            val background = when (request.config.background) {
                VideoBackground.CUSTOM -> loadBitmap(context, requireNotNull(request.config.customBackgroundUri) { "Choose a background photo first." })
                    ?: error("The background photo could not be opened. Choose it again.")
                VideoBackground.ARTWORK -> artwork ?: error("This song does not have available artwork. Choose another background.")
                else -> null
            }
            return VideoFramePainter(request, width, height, background, artwork)
        }

        internal fun loadBitmap(context: Context, source: String): Bitmap? = runCatching {
            val uri = Uri.parse(source)
            if (uri.scheme == "https" || uri.scheme == "http") {
                val connection = URL(source).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000; connection.readTimeout = 8_000
                try {
                    check(connection.responseCode == 200)
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024)
                        while (true) { val count = input.read(buffer); if (count < 0) break; check(output.size() + count <= 16 * 1024 * 1024); output.write(buffer, 0, count) }
                        output.toByteArray()
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, scaledOptions(bounds))
                    val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
                    oriented(bitmap, exif)
                } finally { connection.disconnect() }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, scaledOptions(bounds)) }
                val exif = runCatching { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
                oriented(bitmap, exif)
            }
        }.getOrNull()

        private fun oriented(bitmap: Bitmap?, exif: ExifInterface?): Bitmap? {
            if (bitmap == null || exif == null || (!exif.isFlipped && exif.rotationDegrees == 0)) return bitmap
            val matrix = Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        }

        private fun scaledOptions(bounds: BitmapFactory.Options) = BitmapFactory.Options().apply {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }
}

private class DisplaySpectrum : AbstractList<Float>() {
    var source: List<Float> = emptyList()
    var gain: Float = 1f
    override val size: Int get() = source.size
    override fun get(index: Int): Float = source[index] * gain
}
