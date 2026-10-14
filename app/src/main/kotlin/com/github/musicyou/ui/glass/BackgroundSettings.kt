package com.github.musicyou.ui.glass

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.File

/** Normalized crop positions survive changes between the folded and unfolded display. */
@Immutable
data class BackgroundAppearance(
    val imageUri: String? = null,
    val zoom: Float = 1f,
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val glassEnabled: Boolean = true,
    val glassTint: Float = 0.18f,
    val glassBlur: Float = 18f,
) {
    internal fun validated() = copy(
        zoom = zoom.coerceIn(1f, 4f),
        positionX = positionX.coerceIn(-1f, 1f),
        positionY = positionY.coerceIn(-1f, 1f),
        glassTint = glassTint.coerceIn(0.04f, 0.7f),
        glassBlur = glassBlur.coerceIn(0f, 40f),
    )
}

@Stable
class BackgroundSettings internal constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences("glass_music_appearance", Context.MODE_PRIVATE)

    var value by mutableStateOf(
        BackgroundAppearance(
            imageUri = preferences.getString("image", null),
            zoom = preferences.getFloat("zoom", 1f),
            positionX = preferences.getFloat("position_x", 0f),
            positionY = preferences.getFloat("position_y", 0f),
            glassEnabled = preferences.getBoolean("glass_enabled", true),
            glassTint = preferences.getFloat("glass_tint", 0.18f),
            glassBlur = preferences.getFloat("glass_blur", 18f),
        ).validated()
    )
        private set

    val glassEnabled: Boolean get() = value.glassEnabled
    val glassTint: Float get() = value.glassTint
    val glassBlur: Float get() = value.glassBlur

    /** A single save commits both the photo crop and the glass controls. */
    fun apply(appearance: BackgroundAppearance) {
        val previousImage = value.imageUri
        val next = appearance.validated()
        preferences.edit()
            .putString("image", next.imageUri)
            .putFloat("zoom", next.zoom)
            .putFloat("position_x", next.positionX)
            .putFloat("position_y", next.positionY)
            .putBoolean("glass_enabled", next.glassEnabled)
            .putFloat("glass_tint", next.glassTint)
            .putFloat("glass_blur", next.glassBlur)
            .apply()
        value = next
        if (previousImage != next.imageUri) deletePrivateBackground(context, previousImage)
    }
}

@Composable
fun rememberBackgroundSettings(): BackgroundSettings {
    val context = LocalContext.current.applicationContext
    return remember(context) { SharedBackgroundSettings.get(context) }
}

private object SharedBackgroundSettings {
    private var settings: BackgroundSettings? = null

    @Synchronized
    fun get(context: Context): BackgroundSettings =
        settings ?: BackgroundSettings(context.applicationContext).also { settings = it }
}

/** Only app-owned imported photos can be removed; provider or user files are never deleted. */
internal fun deletePrivateBackground(context: Context, imageUri: String?) {
    if (imageUri == null) return
    runCatching {
        val uri = android.net.Uri.parse(imageUri)
        if (uri.scheme != "file") return@runCatching
        val candidate = File(uri.path ?: return@runCatching).canonicalFile
        val directory = File(context.filesDir, "backgrounds").canonicalFile
        if (candidate.parentFile == directory && candidate.name.startsWith("photo_")) candidate.delete()
    }
}
