package com.github.musicyou.ui.glass

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private val NeutralWallpaper = Color(0xFFF8F7F5)
private val ReadableInk = Color(0xFF17171B)
private val CoralAccent = Color(0xFFFA2D48)

@Immutable
data class ContrastPalette(
    val primary: Color = ReadableInk,
    val secondary: Color = Color(0xFF66666D),
    val accent: Color = CoralAccent,
)

val LocalContrastPalette = staticCompositionLocalOf { ContrastPalette() }
val LocalContrastOverlay = staticCompositionLocalOf { Color.Transparent }
val LocalWallpaperContrast = staticCompositionLocalOf { WallpaperContrastState() }
private val LocalContrastSamples = staticCompositionLocalOf { listOf(NeutralWallpaper) }

@Stable
class WallpaperContrastState internal constructor() {
    internal var map by mutableStateOf<WallpaperColorMap?>(null)
    internal var appearance by mutableStateOf(BackgroundAppearance())
    internal var viewport by mutableStateOf(Rect.Zero)

    internal fun sample(bounds: Rect, overlay: Color): List<Color> {
        val image = map
        if (image == null || viewport.width <= 0f || viewport.height <= 0f) {
            return listOf(overlay.compositeOver(NeutralWallpaper))
        }
        val screen = IntSize(viewport.width.roundToInt(), viewport.height.roundToInt())
        val extent = backgroundImageExtent(screen, image.sourceSize, appearance.zoom)
        val left = viewport.left + (screen.width - extent.width) / 2f + appearance.positionX * (extent.width - screen.width) / 2f
        val top = viewport.top + (screen.height - extent.height) / 2f + appearance.positionY * (extent.height - screen.height) / 2f
        val region = if (bounds.width > 0 && bounds.height > 0) bounds else viewport
        val samples = ArrayList<Color>(35)
        for (row in 0..4) for (column in 0..6) {
            val screenX = region.left + region.width * column / 6f
            val screenY = region.top + region.height * row / 4f
            val imageX = ((screenX - left) / extent.width * image.width).toInt().coerceIn(0, image.width - 1)
            val imageY = ((screenY - top) / extent.height * image.height).toInt().coerceIn(0, image.height - 1)
            val pixel = Color(image.pixels[imageY * image.width + imageX]).compositeOver(NeutralWallpaper)
            samples.add(overlay.compositeOver(pixel))
        }
        return samples
    }
}

internal class WallpaperColorMap(val width: Int, val height: Int, val sourceSize: IntSize, val pixels: IntArray)

@Composable
fun rememberWallpaperContrast(appearance: BackgroundAppearance): WallpaperContrastState {
    val context = LocalContext.current.applicationContext
    val state = remember { WallpaperContrastState() }
    SideEffect { state.appearance = appearance }
    LaunchedEffect(appearance.imageUri) {
        state.map = null
        val imageUri = appearance.imageUri ?: return@LaunchedEffect
        state.map = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(imageUri)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
                val orientation = if (uri.scheme == "file") {
                    runCatching { ExifInterface(uri.path.orEmpty()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                        .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
                } else ExifInterface.ORIENTATION_NORMAL
                val rotated = orientation in 5..8
                val sourceSize = if (rotated) IntSize(options.outHeight, options.outWidth) else IntSize(options.outWidth, options.outHeight)
                var sampleSize = 1
                while (max(options.outWidth, options.outHeight) / sampleSize > 256) sampleSize *= 2
                options.inJustDecodeBounds = false
                options.inSampleSize = sampleSize
                val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: return@runCatching null
                val matrix = Matrix().apply {
                    when (orientation) {
                        2 -> setScale(-1f, 1f)
                        3 -> postRotate(180f)
                        4 -> setScale(1f, -1f)
                        5 -> { postRotate(90f); postScale(-1f, 1f) }
                        6 -> postRotate(90f)
                        7 -> { postRotate(270f); postScale(-1f, 1f) }
                        8 -> postRotate(270f)
                    }
                }
                val bitmap = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { decoded.recycle() }
                try {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    WallpaperColorMap(bitmap.width, bitmap.height, sourceSize, pixels)
                } finally {
                    bitmap.recycle()
                }
            }.getOrNull()
        }
    }
    return state
}

/** Samples the area behind a group and supplies colors after all inherited glass tints. */
@Composable
fun ContrastRegion(
    modifier: Modifier = Modifier,
    overlay: Color = Color.Transparent,
    drawScrim: Boolean = true,
    onScrimChanged: (Color) -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val state = LocalWallpaperContrast.current
    val inherited = LocalContrastOverlay.current
    val combined = overlay.compositeOver(inherited)
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val samples = remember(state.map, state.appearance, state.viewport, bounds, combined) { state.sample(bounds, combined) }
    val primary = remember(samples) { resolveContrast(samples) }
    SideEffect { onScrimChanged(primary.scrim) }
    val backedSamples = if (primary.scrim.alpha > 0f) samples.map { primary.scrim.compositeOver(it) } else samples
    val palette = remember(backedSamples, primary.foreground) {
        ContrastPalette(
            primary = primary.foreground,
            secondary = resolveContrast(backedSamples, Color(0xFF77777D)).foreground,
            accent = resolveContrast(backedSamples, CoralAccent).foreground,
        )
    }
    val effectiveOverlay = primary.scrim.compositeOver(combined)
    Box(
        modifier = modifier
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .drawBehind { if (drawScrim && primary.scrim.alpha > 0f) drawRect(primary.scrim) },
    ) {
        CompositionLocalProvider(
            LocalContentColor provides palette.primary,
            LocalContrastPalette provides palette,
            LocalContrastOverlay provides effectiveOverlay,
            LocalContrastSamples provides backedSamples,
        ) {
            content()
        }
    }
}

@Composable
fun adaptiveContentColor(preferred: Color = Color.Unspecified): Color =
    if (preferred == Color.Unspecified) LocalContrastPalette.current.primary
    else resolveContrast(LocalContrastSamples.current, preferred).foreground

/** Text adapts to its own position, including image pan, zoom and scrolling. */
@Composable
fun AdaptiveText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val state = LocalWallpaperContrast.current
    val overlay = LocalContrastOverlay.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val preferred = if (color != Color.Unspecified) color else style.color
    val resolution = remember(state.map, state.appearance, state.viewport, bounds, overlay, preferred) {
        resolveContrast(state.sample(bounds, overlay), preferred)
    }
    MaterialText(
        text = text,
        modifier = modifier.onGloballyPositioned { bounds = it.boundsInWindow() }.contrastOutline(resolution.scrim),
        color = resolution.foreground,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style,
    )
}

@Composable
fun AdaptiveIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val state = LocalWallpaperContrast.current
    val overlay = LocalContrastOverlay.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val resolution = remember(state.map, state.appearance, state.viewport, bounds, overlay, tint) {
        resolveContrast(state.sample(bounds, overlay), tint)
    }
    MaterialIcon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier.onGloballyPositioned { bounds = it.boundsInWindow() }.contrastOutline(resolution.scrim),
        tint = resolution.foreground,
    )
}

/** A narrow glyph halo keeps mixed photographic edges readable without adding label boxes. */
private fun Modifier.contrastOutline(backing: Color): Modifier = drawWithContent {
    if (backing.alpha > 0f) {
        val width = 0.7.dp.toPx()
        val paint = Paint().apply { colorFilter = ColorFilter.tint(backing.copy(alpha = 0.88f), BlendMode.SrcIn) }
        val area = Rect(-width, -width, size.width + width, size.height + width)
        val offsets = listOf(
            Offset(-width, 0f), Offset(width, 0f), Offset(0f, -width), Offset(0f, width),
            Offset(-width, -width), Offset(width, -width), Offset(-width, width), Offset(width, width),
        )
        for (offset in offsets) drawIntoCanvas { canvas ->
            canvas.save()
            canvas.translate(offset.x, offset.y)
            canvas.saveLayer(area, paint)
            drawContent()
            canvas.restore()
            canvas.restore()
        }
    }
    drawContent()
}

internal data class ContrastResolution(val foreground: Color, val scrim: Color, val minimumContrast: Float)

internal fun relativeLuminance(color: Color): Float {
    fun linear(component: Float): Double = if (component <= 0.04045f) component / 12.92 else ((component + 0.055) / 1.055).pow(2.4)
    return (0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)).toFloat()
}

internal fun contrastRatio(first: Color, second: Color): Float {
    val a = relativeLuminance(first)
    val b = relativeLuminance(second)
    return (max(a, b) + 0.05f) / (min(a, b) + 0.05f)
}

/** Mixed bright/dark pixels receive the smallest backing needed for readable normal text. */
internal fun resolveContrast(colors: List<Color>, preferred: Color = Color.Unspecified, minimum: Float = 4.5f): ContrastResolution {
    val backgrounds = colors.ifEmpty { listOf(NeutralWallpaper) }
    val candidates = if (preferred == Color.Unspecified) listOf(ReadableInk, Color.White) else buildList {
        add(preferred.copy(alpha = 1f))
        for (step in 1..10) {
            add(lerp(preferred.copy(alpha = 1f), ReadableInk, step / 10f))
            add(lerp(preferred.copy(alpha = 1f), Color.White, step / 10f))
        }
    }
    val backgroundLuminances = backgrounds.map(::relativeLuminance)
    fun minimumRatio(foreground: Color, scrim: Color = Color.Transparent): Float {
        val foregroundLuminance = relativeLuminance(foreground)
        val luminances = if (scrim.alpha <= 0f) backgroundLuminances else backgrounds.map { relativeLuminance(scrim.compositeOver(it)) }
        return luminances.minOf { (max(foregroundLuminance, it) + 0.05f) / (min(foregroundLuminance, it) + 0.05f) }
    }
    val withoutBacking = candidates.firstOrNull { minimumRatio(it) >= minimum }
    if (withoutBacking != null) return ContrastResolution(withoutBacking, Color.Transparent, minimumRatio(withoutBacking))

    var best: ContrastResolution? = null
    var bestCost = Float.POSITIVE_INFINITY
    val backingCandidates = if (preferred == Color.Unspecified) candidates else listOf(
        preferred.copy(alpha = 1f),
        lerp(preferred.copy(alpha = 1f), ReadableInk, 0.5f),
        lerp(preferred.copy(alpha = 1f), Color.White, 0.5f),
        ReadableInk,
        Color.White,
    )
    backingCandidates.forEachIndexed { index, foreground ->
        for (backing in listOf(Color.White, Color.Black)) {
            if (contrastRatio(foreground, backing) < minimum) continue
            var low = 0f
            var high = 1f
            repeat(12) {
                val mid = (low + high) / 2f
                if (minimumRatio(foreground, backing.copy(alpha = mid)) >= minimum) high = mid else low = mid
            }
            val scrim = backing.copy(alpha = (high + 0.003f).coerceAtMost(1f))
            val shiftCost = if (preferred == Color.Unspecified) 0f else index * 0.018f
            val cost = scrim.alpha + shiftCost
            if (cost < bestCost) {
                bestCost = cost
                best = ContrastResolution(foreground, scrim, minimumRatio(foreground, scrim))
            }
        }
    }
    return best ?: ContrastResolution(Color.White, Color.Black, 21f)
}
