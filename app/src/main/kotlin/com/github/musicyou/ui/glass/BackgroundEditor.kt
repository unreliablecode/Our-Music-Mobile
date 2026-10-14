package com.github.musicyou.ui.glass

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import com.github.musicyou.ui.glass.AdaptiveIcon as Icon
import com.github.musicyou.ui.glass.AdaptiveText as Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MusicPink = Color(0xFFFA2D48)
private val EditorInk = Color(0xFF202023)
private val EditorSecondary = Color(0xFF69696E)

/** All edits stay in this draft until Save; dismissing also removes unsaved photo imports. */
@Composable
fun BackgroundEditor(settings: BackgroundSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var draft by remember(settings) { mutableStateOf(settings.value) }
    val contrast = rememberWallpaperContrast(draft)
    val readability = rememberWallpaperReadability(contrast)
    var positionStart by remember { mutableStateOf(draft) }
    var repositioning by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(draft.imageUri) { mutableStateOf(IntSize.Zero) }
    val importedPhotos = remember { mutableSetOf<String>() }

    DisposableEffect(settings) {
        onDispose {
            importedPhotos.filter { it != settings.value.imageUri }.forEach {
                deletePrivateBackground(context, it)
            }
        }
    }

    val choosePhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            error = null
            try {
                // Import into app storage so moving or removing the original cannot break the background.
                val imported = importBackgroundPhoto(context, uri)
                importedPhotos.add(imported)
                val previous = draft.imageUri
                draft = draft.copy(imageUri = imported, zoom = 1f, positionX = 0f, positionY = 0f)
                if (previous != null && previous in importedPhotos) {
                    deletePrivateBackground(context, previous)
                    importedPhotos.remove(previous)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "This image could not be opened. Try another photo."
            } finally {
                importing = false
            }
        }
    }

    val cancelPosition = {
        draft = draft.copy(
            zoom = positionStart.zoom,
            positionX = positionStart.positionX,
            positionY = positionStart.positionY,
        )
        repositioning = false
    }
    BackHandler {
        if (repositioning) cancelPosition() else onDismiss()
    }

    CompositionLocalProvider(
        LocalWallpaperContrast provides contrast,
        LocalGlassStyle provides GlassStyle(draft.glassEnabled, draft.glassTint, draft.glassBlur),
    ) {
        ProvideWallpaperReadability(readability) {
            GlassBackdropRoot(
                modifier = Modifier.fillMaxSize(),
                background = {
                    BackgroundLayer(
                        appearance = draft,
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewport = it }
                            .pointerInput(repositioning, draft.imageUri, viewport, imageSize) {
                                if (repositioning && draft.imageUri != null) {
                                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                                        draft = repositionBackground(draft, viewport, imageSize, centroid, pan, zoomChange)
                                    }
                                }
                            },
                        onImageSize = { imageSize = it },
                        readabilityOverlay = readability.overlay,
                    )
                },
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GlassSurface(
                        modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        tint = Color.White.copy(alpha = 0.58f),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { if (repositioning) cancelPosition() else onDismiss() }) {
                                Text("Cancel", color = MusicPink, fontSize = 16.sp)
                            }
                            Text(
                                text = if (repositioning) "Move & Scale" else "Background",
                                modifier = Modifier.weight(1f),
                                color = EditorInk,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            TextButton(
                                enabled = !importing,
                                onClick = {
                                    if (repositioning) {
                                        repositioning = false
                                    } else {
                                        settings.apply(draft)
                                        onDismiss()
                                    }
                                },
                            ) {
                                Text(if (repositioning) "Done" else "Save", color = MusicPink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    if (repositioning) {
                        GlassSurface(
                            modifier = Modifier.widthIn(max = 430.dp).fillMaxWidth(),
                            tint = Color.White.copy(alpha = 0.62f),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(Icons.Rounded.CropFree, contentDescription = null, tint = EditorInk, modifier = Modifier.size(25.dp))
                                Spacer(Modifier.height(9.dp))
                                Text("Drag to move. Pinch to zoom.", color = EditorInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text("The photo stays full screen as you adjust it.", color = EditorSecondary, fontSize = 12.sp)
                                GlassSlider("Zoom", draft.zoom, 1f..4f) { draft = draft.copy(zoom = it) }
                                Spacer(Modifier.height(4.dp))
                                TextButton(onClick = { draft = draft.copy(zoom = 1f, positionX = 0f, positionY = 0f) }) {
                                    Icon(Icons.Rounded.Restore, null, tint = MusicPink, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Reset position", color = MusicPink)
                                }
                            }
                        }
                    } else {
                        GlassSurface(
                            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                            shape = RoundedCornerShape(30.dp),
                            tint = Color.White.copy(alpha = 0.63f),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 22.dp, vertical = 18.dp),
                            ) {
                                Text("Make it yours", color = EditorInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                Text("Choose a photo and set the glass to your taste.", color = EditorSecondary, fontSize = 13.sp)
                                Spacer(Modifier.height(15.dp))

                                EditorAction(
                                    label = if (draft.imageUri == null) "Choose a photo" else "Change photo",
                                    icon = { Icon(Icons.Rounded.AddPhotoAlternate, null, tint = MusicPink) },
                                    enabled = !importing,
                                    trailing = {
                                        if (importing) CircularProgressIndicator(Modifier.size(18.dp), color = MusicPink, strokeWidth = 2.dp)
                                        else Icon(Icons.Rounded.KeyboardArrowRight, null, tint = EditorSecondary)
                                    },
                                ) { choosePhoto.launch(arrayOf("image/*")) }

                                if (draft.imageUri != null) {
                                    EditorAction(
                                        label = "Move & scale",
                                        icon = { Icon(Icons.Rounded.CropFree, null, tint = MusicPink) },
                                        enabled = !importing,
                                        trailing = { Icon(Icons.Rounded.KeyboardArrowRight, null, tint = EditorSecondary) },
                                    ) {
                                        positionStart = draft
                                        repositioning = true
                                    }
                                    EditorAction(
                                        label = "Remove photo",
                                        icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MusicPink) },
                                        enabled = !importing,
                                    ) {
                                        draft = draft.copy(imageUri = null, zoom = 1f, positionX = 0f, positionY = 0f)
                                        error = null
                                    }
                                }

                                error?.let {
                                    Text(it, color = MusicPink, fontSize = 12.sp, modifier = Modifier.padding(vertical = 7.dp))
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.Black.copy(alpha = 0.08f))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Liquid Glass", color = EditorInk, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                        Text("Live blur and refraction", color = EditorSecondary, fontSize = 12.sp)
                                    }
                                    GlassToggle(checked = draft.glassEnabled, onCheckedChange = { draft = draft.copy(glassEnabled = it) })
                                }
                                if (draft.glassEnabled) {
                                    Spacer(Modifier.height(13.dp))
                                    GlassSlider("Glass tint", draft.glassTint, 0.04f..0.7f) { draft = draft.copy(glassTint = it) }
                                    GlassSlider("Blur", draft.glassBlur, 0f..40f) { draft = draft.copy(glassBlur = it) }
                                    GlassSurface(
                                        modifier = Modifier.fillMaxWidth().height(44.dp),
                                        shape = RoundedCornerShape(15.dp),
                                        tint = Color.White.copy(alpha = draft.glassTint),
                                    ) {
                                        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Rounded.Check, null, tint = EditorInk, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(7.dp))
                                            Text("Live glass preview", color = EditorInk, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorAction(
    label: String,
    icon: @Composable () -> Unit,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(25.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), color = EditorInk, fontSize = 16.sp)
        trailing()
    }
}

@Composable
private fun GlassSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(80.dp), color = EditorSecondary, fontSize = 13.sp)
        GlassSlider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            activeTrackColor = adaptiveContentColor(MusicPink),
            contentDescription = label,
        )
    }
}

internal fun repositionBackground(
    appearance: BackgroundAppearance,
    viewport: IntSize,
    imageSize: IntSize,
    centroid: Offset,
    pan: Offset,
    zoomChange: Float,
): BackgroundAppearance {
    if (imageSize.width == 0 || imageSize.height == 0) return appearance
    val oldExtent = backgroundImageExtent(viewport, imageSize, appearance.zoom)
    val nextZoom = (appearance.zoom * zoomChange).coerceIn(1f, 4f)
    val nextExtent = backgroundImageExtent(viewport, imageSize, nextZoom)
    val ratio = nextZoom / appearance.zoom
    val oldX = appearance.positionX * (oldExtent.width - viewport.width).coerceAtLeast(0) / 2f
    val oldY = appearance.positionY * (oldExtent.height - viewport.height).coerceAtLeast(0) / 2f
    // Anchor the image under the fingers while scaling, then apply their drag distance.
    val anchor = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
    val translatedX = oldX * ratio + anchor.x * (1f - ratio) + pan.x
    val translatedY = oldY * ratio + anchor.y * (1f - ratio) + pan.y
    val maxX = (nextExtent.width - viewport.width).coerceAtLeast(0) / 2f
    val maxY = (nextExtent.height - viewport.height).coerceAtLeast(0) / 2f
    return appearance.copy(
        zoom = nextZoom,
        positionX = if (maxX > 0f) (translatedX / maxX).coerceIn(-1f, 1f) else 0f,
        positionY = if (maxY > 0f) (translatedY / maxY).coerceIn(-1f, 1f) else 0f,
    )
}

private suspend fun importBackgroundPhoto(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val directory = File(context.filesDir, "backgrounds").apply { mkdirs() }
    val destination = File(directory, "photo_${UUID.randomUUID()}.image")
    try {
        val source = context.contentResolver.openInputStream(uri) ?: error("No image stream")
        source.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= 40L * 1024 * 1024) { "Photo exceeds import limit" }
                    output.write(buffer, 0, read)
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(destination.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
        currentCoroutineContext().ensureActive()
        Uri.fromFile(destination).toString()
    } catch (failure: Exception) {
        destination.delete()
        throw failure
    }
}
