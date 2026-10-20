package com.github.musicyou.ui.visualization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.musicyou.audio.visualization.OscilloscopeConfig
import com.github.musicyou.audio.visualization.ScopeTriggerEdge
import com.github.musicyou.audio.visualization.ScopeTriggerMode
import com.github.musicyou.ui.components.GlassDialogShell
import com.github.musicyou.ui.glass.AdaptiveText as Text
import com.github.musicyou.ui.glass.GlassButton
import com.github.musicyou.ui.glass.GlassSlider
import com.github.musicyou.ui.glass.GlassToggle
import com.github.musicyou.ui.glass.LocalContrastPalette
import com.github.musicyou.ui.glass.glassClickable
import java.util.Locale

/** Controls alter the decoded digital signal display; voltage requires explicit calibration. */
@Composable
internal fun ScopeControlsDialog(
    config: OscilloscopeConfig,
    sampleRate: Int,
    sampleCount: Int,
    onChange: (OscilloscopeConfig) -> Unit,
    onDismiss: () -> Unit,
    channelCount: Int = 2,
) {
    val palette = LocalContrastPalette.current
    val timebases = remember(sampleRate, sampleCount) {
        OscilloscopeConfig.supportedTimebases(sampleRate, sampleCount)
    }
    var calibrationText by remember(config.calibratedVoltsPerFullScale) {
        mutableStateOf(config.calibratedVoltsPerFullScale?.toString().orEmpty())
    }
    val calibration = calibrationText.trim().replace(',', '.').toFloatOrNull()
        ?.takeIf { it.isFinite() && it > 0f }
    val volts = config.calibratedVoltsPerFullScale
    val amplitudeUnit = if (volts == null) "FS" else "V"
    fun amplitude(value: Float): String = scopeDecimal(value * (volts ?: 1f))

    GlassDialogShell(
        title = "Oscilloscope controls",
        onDismiss = onDismiss,
        actions = {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                Text(
                    "Done",
                    modifier = Modifier.glassClickable(onClick = onDismiss).padding(horizontal = 20.dp, vertical = 12.dp),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    ) {
        ScopeSectionTitle("Timebase")
        Text("Ten horizontal divisions", fontSize = 12.sp)
        if (timebases.isEmpty()) {
            Text("Timebase choices become available with decoded audio.", Modifier.padding(vertical = 10.dp), fontSize = 13.sp)
        } else {
            for (choices in timebases.chunked(3)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (timebase in choices) {
                        ScopeChoice(
                            label = scopeTimebase(timebase),
                            chosen = config.timebaseUsPerDivision == timebase,
                            onClick = { onChange(config.copy(timebaseUsPerDivision = timebase)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - choices.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        ScopeSectionTitle("Amplitude")
        Text("${amplitude(config.fullScalePerDivision)} $amplitudeUnit/div", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        GlassSlider(
            value = config.fullScalePerDivision,
            onValueChange = { onChange(config.copy(fullScalePerDivision = it)) },
            valueRange = 0.125f..1f,
            contentDescription = "Oscilloscope amplitude per division",
        )

        ScopeSectionTitle("Trigger")
        ScopeChoices(
            labels = listOf("Auto", "Normal"),
            selected = if (config.triggerMode == ScopeTriggerMode.AUTO) 0 else 1,
            onSelect = { onChange(config.copy(triggerMode = if (it == 0) ScopeTriggerMode.AUTO else ScopeTriggerMode.NORMAL)) },
        )
        Text(
            if (config.triggerMode == ScopeTriggerMode.NORMAL) "Normal waits for a crossing of the selected level."
            else "Auto displays the latest audio when there is no crossing.",
            Modifier.padding(top = 6.dp), fontSize = 12.sp,
        )
        Text("Edge", Modifier.padding(top = 12.dp, bottom = 6.dp), fontSize = 13.sp)
        ScopeChoices(
            labels = listOf("Rising", "Falling"),
            selected = if (config.triggerEdge == ScopeTriggerEdge.RISING) 0 else 1,
            onSelect = { onChange(config.copy(triggerEdge = if (it == 0) ScopeTriggerEdge.RISING else ScopeTriggerEdge.FALLING)) },
        )
        Text("Source", Modifier.padding(top = 12.dp, bottom = 6.dp), fontSize = 13.sp)
        ScopeChoices(
            labels = if (channelCount > 1) listOf("Left", "Right") else listOf("Mono"),
            selected = config.triggerSource.coerceIn(0, if (channelCount > 1) 1 else 0),
            onSelect = { onChange(config.copy(triggerSource = it)) },
        )
        Text("Level: ${amplitude(config.triggerLevel)} $amplitudeUnit", Modifier.padding(top = 12.dp), fontSize = 13.sp)
        GlassSlider(
            value = config.triggerLevel,
            onValueChange = { onChange(config.copy(triggerLevel = it)) },
            valueRange = -1f..1f,
            contentDescription = "Oscilloscope trigger level",
        )

        ScopeSectionTitle("Cursors")
        Row(
            Modifier.fillMaxWidth().glassClickable(onClick = { onChange(config.copy(cursorsEnabled = !config.cursorsEnabled)) })
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Time cursors", Modifier.weight(1f), fontSize = 15.sp)
            GlassToggle(config.cursorsEnabled, { onChange(config.copy(cursorsEnabled = it)) })
        }
        if (config.cursorsEnabled) {
            Text("Cursor A: ${scopeDecimal(config.cursorA * 100f)}%", fontSize = 13.sp)
            GlassSlider(
                value = config.cursorA,
                onValueChange = { onChange(config.copy(cursorA = it)) },
                contentDescription = "Oscilloscope time cursor A",
            )
            Text("Cursor B: ${scopeDecimal(config.cursorB * 100f)}%", fontSize = 13.sp)
            GlassSlider(
                value = config.cursorB,
                onValueChange = { onChange(config.copy(cursorB = it)) },
                contentDescription = "Oscilloscope time cursor B",
            )
            Text("Move the two cursors to read the time difference on the screen.", fontSize = 12.sp)
        }

        ScopeSectionTitle("Units and calibration")
        Text(
            if (volts == null) "Digital full scale (FS)" else "Calibrated volts: ${scopeDecimal(volts)} V per full scale",
            fontSize = 14.sp, fontWeight = FontWeight.Medium,
        )
        Text(
            "Decoded audio measures digital amplitude. Voltage is shown only from the calibration you enter for your output.",
            Modifier.padding(top = 6.dp, bottom = 10.dp), fontSize = 12.sp,
        )
        OutlinedTextField(
            value = calibrationText,
            onValueChange = { calibrationText = it },
            label = { Text("Volts per full scale", fontSize = 13.sp) },
            singleLine = true,
            isError = calibrationText.isNotBlank() && calibration == null,
            supportingText = if (calibrationText.isNotBlank() && calibration == null) {
                { Text("Enter a finite number greater than zero.", fontSize = 12.sp) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            textStyle = androidx.compose.ui.text.TextStyle(color = palette.primary, fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScopeChoice(
                label = "Apply calibration",
                chosen = false,
                onClick = { calibration?.let { onChange(config.copy(calibratedVoltsPerFullScale = it)) } },
                enabled = calibration != null,
                role = Role.Button,
                modifier = Modifier.weight(1f),
            )
            ScopeChoice(
                label = "Use digital FS",
                chosen = volts == null,
                onClick = { onChange(config.copy(calibratedVoltsPerFullScale = null)) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(5.dp))
    }
}

@Composable
private fun ScopeSectionTitle(title: String) {
    Text(title, Modifier.padding(top = 16.dp, bottom = 7.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ScopeChoices(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            ScopeChoice(label, selected == index, { onSelect(index) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ScopeChoice(
    label: String,
    chosen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
) {
    GlassButton(
        onClick = onClick,
        enabled = enabled,
        role = role,
        tint = if (chosen) Color(0xFFFA2D48).copy(alpha = 0.15f) else Color.Unspecified,
        modifier = modifier.heightIn(min = 44.dp).semantics { selected = chosen },
    ) {
        Text(label, Modifier.align(Alignment.Center).padding(8.dp), fontSize = 12.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium)
    }
}

private fun scopeTimebase(microseconds: Int): String =
    if (microseconds < 1_000) "$microseconds us/div" else "${scopeDecimal(microseconds / 1_000f)} ms/div"

private fun scopeDecimal(value: Float): String =
    if (value != 0f && (kotlin.math.abs(value) < 0.001f || kotlin.math.abs(value) >= 10_000f)) {
        String.format(Locale.US, "%.4g", value)
    } else String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }
