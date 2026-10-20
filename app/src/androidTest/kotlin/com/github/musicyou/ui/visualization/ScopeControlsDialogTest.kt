package com.github.musicyou.ui.visualization

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.musicyou.audio.visualization.OscilloscopeConfig
import com.github.musicyou.audio.visualization.ScopeTriggerEdge
import com.github.musicyou.audio.visualization.ScopeTriggerMode
import com.github.musicyou.ui.glass.GlassBackdropRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Changes pass through the displayed controls into an isolated configuration. */
@RunWith(AndroidJUnit4::class)
class ScopeControlsDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun timebaseAmplitudeAndTriggerControlsUpdateTheAcquisitionConfiguration() {
        val config = mutableStateOf(OscilloscopeConfig())
        show(config)

        compose.onNodeWithText("2 ms/div").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Oscilloscope amplitude per division").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.onNodeWithText("Normal").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Falling").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Right").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Oscilloscope trigger level").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(-0.25f) }

        compose.runOnIdle {
            assertEquals(2_000, config.value.timebaseUsPerDivision)
            assertEquals(0.5f, config.value.fullScalePerDivision, 0.0001f)
            assertEquals(ScopeTriggerMode.NORMAL, config.value.triggerMode)
            assertEquals(ScopeTriggerEdge.FALLING, config.value.triggerEdge)
            assertEquals(1, config.value.triggerSource)
            assertEquals(-0.25f, config.value.triggerLevel, 0.0001f)
        }
    }

    @Test
    fun physicalUnitsRequireExplicitFinitePositiveCalibrationAndCanReturnToDigital() {
        val config = mutableStateOf(OscilloscopeConfig())
        show(config)
        compose.onNodeWithText("Digital full scale (FS)").performScrollTo().assertExists()

        for (invalid in listOf("-2", "0", "NaN", "Infinity")) {
            compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(invalid)
            compose.onNode(hasSetTextAction()).performImeAction()
            compose.onNodeWithText("Apply calibration").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { assertNull("$invalid cannot supply physical units", config.value.calibratedVoltsPerFullScale) }
        }

        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("2.5")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertNull("Typing alone does not invent a voltage calibration", config.value.calibratedVoltsPerFullScale) }
        compose.onNodeWithText("Apply calibration").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Calibrated volts: 2.5 V per full scale").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(2.5f, config.value.calibratedVoltsPerFullScale!!, 0.0001f) }

        compose.onNodeWithText("Use digital FS").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Digital full scale (FS)").performScrollTo().assertExists()
        compose.runOnIdle { assertNull(config.value.calibratedVoltsPerFullScale) }
    }

    private fun show(config: MutableState<OscilloscopeConfig>) {
        compose.setContent {
            MaterialTheme {
                GlassBackdropRoot(
                    modifier = Modifier.fillMaxSize(),
                    background = { Box(Modifier.fillMaxSize().background(Color(0xFFCAD8E1))) },
                ) {
                    ScopeControlsDialog(
                        config = config.value,
                        sampleRate = 48_000,
                        sampleCount = 4_096,
                        channelCount = 2,
                        onChange = { config.value = it },
                        onDismiss = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }
}
