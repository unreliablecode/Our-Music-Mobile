package com.github.musicyou.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun AppBackground(settings: BackgroundSettings, modifier: Modifier = Modifier) {
    BackgroundLayer(settings.value, modifier)
}

/** Draws the photo itself, so every live glass layer can refract the same moving scene. */
@Composable
internal fun BackgroundLayer(
    appearance: BackgroundAppearance,
    modifier: Modifier = Modifier,
    onImageSize: (IntSize) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val contrast = LocalWallpaperContrast.current
    var imageSize by remember(appearance.imageUri) { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().clipToBounds().background(Color(0xFFF8F7F5))
            .onGloballyPositioned { contrast.viewport = it.boundsInWindow() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFFFBFBFA), Color(0xFFF5F3F2)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFFBE9ED).copy(alpha = 0.48f), Color.Transparent),
                    center = Offset(size.width * 0.95f, size.height * 0.23f),
                    radius = max(size.width, size.height) * 0.72f,
                )
            )
        }
        val imageUri = appearance.imageUri
        if (imageUri != null) {
            val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
            val extent = backgroundImageExtent(viewport, imageSize, appearance.zoom)
            val width = with(density) { extent.width.toDp() }
            val height = with(density) { extent.height.toDp() }
            val displacementX = (extent.width - viewport.width).coerceAtLeast(0) / 2f
            val displacementY = (extent.height - viewport.height).coerceAtLeast(0) / 2f
            AsyncImage(
                model = remember(imageUri) {
                    ImageRequest.Builder(context)
                        .data(imageUri)
                        .allowHardware(false)
                        .build()
                },
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (appearance.positionX * displacementX).roundToInt(),
                            (appearance.positionY * displacementY).roundToInt(),
                        )
                    }
                    .requiredSize(width, height),
                onSuccess = { state ->
                    val loaded = IntSize(state.result.image.width, state.result.image.height)
                    imageSize = loaded
                    onImageSize(loaded)
                },
                onError = { onImageSize(IntSize.Zero) },
            )
        }
    }
}

internal fun backgroundImageExtent(viewport: IntSize, image: IntSize, zoom: Float): IntSize {
    if (viewport.width <= 0 || viewport.height <= 0) return IntSize(1, 1)
    if (image.width <= 0 || image.height <= 0) return viewport
    val cropScale = max(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
    return IntSize(
        (image.width * cropScale * zoom).roundToInt().coerceAtLeast(viewport.width),
        (image.height * cropScale * zoom).roundToInt().coerceAtLeast(viewport.height),
    )
}
