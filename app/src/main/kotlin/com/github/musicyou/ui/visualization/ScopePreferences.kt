package com.github.musicyou.ui.visualization

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.musicyou.audio.visualization.OscilloscopeConfig
import com.github.musicyou.audio.visualization.ScopeTriggerEdge
import com.github.musicyou.audio.visualization.ScopeTriggerMode

@Stable
internal class ScopePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("oscilloscope", Context.MODE_PRIVATE)
    var value by mutableStateOf(OscilloscopeConfig(
        timebaseUsPerDivision = preferences.getInt("timebase_us", 1_000),
        fullScalePerDivision = preferences.getFloat("scale_fs", 0.25f),
        triggerLevel = preferences.getFloat("trigger_level", 0f),
        triggerEdge = runCatching { ScopeTriggerEdge.valueOf(preferences.getString("trigger_edge", null).orEmpty()) }.getOrDefault(ScopeTriggerEdge.RISING),
        triggerMode = runCatching { ScopeTriggerMode.valueOf(preferences.getString("trigger_mode", null).orEmpty()) }.getOrDefault(ScopeTriggerMode.AUTO),
        triggerSource = preferences.getInt("trigger_source", 0),
        calibratedVoltsPerFullScale = preferences.getString("volts_per_fs", null)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f },
        cursorsEnabled = preferences.getBoolean("cursors", false),
        cursorA = preferences.getFloat("cursor_a", 0.25f),
        cursorB = preferences.getFloat("cursor_b", 0.75f),
    ))
        private set

    fun update(config: OscilloscopeConfig) {
        value = config
        preferences.edit()
            .putInt("timebase_us", config.timebaseUsPerDivision)
            .putFloat("scale_fs", config.fullScalePerDivision)
            .putFloat("trigger_level", config.triggerLevel)
            .putString("trigger_edge", config.triggerEdge.name)
            .putString("trigger_mode", config.triggerMode.name)
            .putInt("trigger_source", config.triggerSource)
            .putString("volts_per_fs", config.calibratedVoltsPerFullScale?.toString())
            .putBoolean("cursors", config.cursorsEnabled)
            .putFloat("cursor_a", config.cursorA)
            .putFloat("cursor_b", config.cursorB)
            .apply()
    }
}
