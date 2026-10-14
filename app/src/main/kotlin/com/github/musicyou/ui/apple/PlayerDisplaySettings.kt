package com.github.musicyou.ui.apple

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.ui.glass.GlassToggle
import com.github.musicyou.ui.glass.AdaptiveText as Text
import com.github.musicyou.ui.visualization.VisualizationMode
import kotlinx.coroutines.launch

@Immutable
internal data class PlayerDisplayAppearance(
    val ambient: Boolean = false,
    val car: Boolean = false,
    val spectrum: Boolean = false,
    val waveform: Boolean = false,
) {
    val visualization: VisualizationMode get() = when {
        spectrum && waveform -> VisualizationMode.BOTH
        spectrum -> VisualizationMode.SPECTRUM
        waveform -> VisualizationMode.WAVEFORM
        else -> VisualizationMode.OFF
    }
}

@Stable
internal class PlayerDisplaySettings(context: Context) {
    private val preferences = context.getSharedPreferences("player_display", Context.MODE_PRIVATE)
    var value by mutableStateOf(PlayerDisplayAppearance(
        ambient = preferences.getBoolean("ambient", false),
        car = preferences.getBoolean("car", false),
        spectrum = preferences.getBoolean("spectrum", false),
        waveform = preferences.getBoolean("waveform", false),
    ))
        private set

    fun update(appearance: PlayerDisplayAppearance) {
        preferences.edit().putBoolean("ambient", appearance.ambient)
            .putBoolean("car", appearance.car).putBoolean("spectrum", appearance.spectrum)
            .putBoolean("waveform", appearance.waveform).apply()
        value = appearance
    }
}

@Composable
internal fun rememberPlayerDisplaySettings(): PlayerDisplaySettings {
    val context = LocalContext.current.applicationContext
    return remember(context) { PlayerDisplays.get(context) }
}

private object PlayerDisplays {
    private var settings: PlayerDisplaySettings? = null
    @Synchronized fun get(context: Context): PlayerDisplaySettings =
        settings ?: PlayerDisplaySettings(context).also { settings = it }
}

@Composable
internal fun PlayerDisplayOptions(settings: PlayerDisplaySettings, onDismiss: () -> Unit) {
    val binder = LocalPlayerServiceBinder.current
    val scope = rememberCoroutineScope()
    val appearance = settings.value
    var cacheBytes by remember(binder) { mutableLongStateOf(0L) }
    var clearing by remember { mutableStateOf(false) }
    var cacheMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(binder) { cacheBytes = binder?.musicCacheBytes ?: 0L }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Player options", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onDismiss) { Text("Done", color = MusicRed) }
        }
        PlayerOption("Ambient mode", "Soft artwork colors respond to the music", appearance.ambient) {
            settings.update(appearance.copy(ambient = it))
        }
        PlayerOption("Car mode", "Large artwork and easy playback controls", appearance.car) {
            settings.update(appearance.copy(car = it))
        }
        PlayerOption("Equalizer visualization", "Live frequency spectrum", appearance.spectrum) {
            settings.update(appearance.copy(spectrum = it))
        }
        PlayerOption("Audio waveform", "The actual sound, shown in real time", appearance.waveform) {
            settings.update(appearance.copy(waveform = it))
        }
        Spacer(Modifier.height(14.dp))
        Text("Music cache", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("${formatCacheBytes(cacheBytes)} of streamed audio", color = MusicSecondary, fontSize = 13.sp)
        Text("Downloaded songs stay available when you clear this cache.", Modifier.padding(top = 5.dp), color = MusicSecondary, fontSize = 13.sp)
        TextButton(enabled = binder != null && !clearing, onClick = {
            clearing = true
            scope.launch {
                try {
                    val removed = binder?.clearMusicCache() ?: 0L
                    cacheBytes = binder?.musicCacheBytes ?: 0L
                    cacheMessage = if (cacheBytes > 0L) "Cleared ${formatCacheBytes(removed)}. ${formatCacheBytes(cacheBytes)} remains in use."
                        else "Cleared ${formatCacheBytes(removed)}"
                } catch (_: Exception) {
                    cacheMessage = "The cache could not be cleared. Try again."
                } finally { clearing = false }
            }
        }) { Text(if (clearing) "Clearing cache…" else "Clear music cache", color = MusicRed) }
        cacheMessage?.let { Text(it, fontSize = 13.sp) }
    }
}

@Composable
private fun PlayerOption(title: String, detail: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!enabled) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 12.sp, color = MusicSecondary)
        }
        GlassToggle(enabled, onChange)
    }
}

private fun formatCacheBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes bytes"
}
