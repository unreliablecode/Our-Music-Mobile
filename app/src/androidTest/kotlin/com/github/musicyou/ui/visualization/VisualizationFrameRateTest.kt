package com.github.musicyou.ui.visualization

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class VisualizationFrameRateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun multiplePlotsShareTheRequestAndRestoreOnlyAfterTheLastPlotStops() {
        val first = mutableStateOf(true)
        val second = mutableStateOf(true)
        val window = compose.activity.window
        val originalRate = window.attributes.preferredRefreshRate
        val originalMode = window.attributes.preferredDisplayModeId
        compose.setContent {
            VisualizationFrameRate(first.value)
            VisualizationFrameRate(second.value)
        }
        compose.runOnIdle {
            assertEquals(60f, window.attributes.preferredRefreshRate, 0f)
            val display = window.decorView.display
            val active = display.mode
            val preferred = display.supportedModes.firstOrNull { it.modeId == window.attributes.preferredDisplayModeId }
            if (preferred != null) {
                assertEquals(active.physicalWidth, preferred.physicalWidth)
                assertEquals(active.physicalHeight, preferred.physicalHeight)
                assertTrue(abs(preferred.refreshRate - 60f) <= 1f)
            }
            first.value = false
        }
        compose.runOnIdle {
            assertEquals("The second plot retains its request", 60f, window.attributes.preferredRefreshRate, 0f)
            second.value = false
        }
        compose.runOnIdle {
            assertEquals(originalRate, window.attributes.preferredRefreshRate, 0f)
            assertEquals(originalMode, window.attributes.preferredDisplayModeId)
        }
    }

    @Test
    fun disposingDoesNotOverwriteANewerWindowPreference() {
        val active = mutableStateOf(true)
        val window = compose.activity.window
        val originalRate = window.attributes.preferredRefreshRate
        val originalMode = window.attributes.preferredDisplayModeId
        compose.setContent { VisualizationFrameRate(active.value) }
        compose.runOnIdle {
            assertEquals(60f, window.attributes.preferredRefreshRate, 0f)
            window.attributes = window.attributes.apply { preferredRefreshRate = 90f }
            active.value = false
        }
        compose.runOnIdle {
            try {
                assertEquals("A later owner's refresh preference survives", 90f, window.attributes.preferredRefreshRate, 0f)
                assertEquals("The unchanged mode preference is restored", originalMode, window.attributes.preferredDisplayModeId)
            } finally {
                window.attributes = window.attributes.apply {
                    preferredRefreshRate = originalRate
                    preferredDisplayModeId = originalMode
                }
            }
        }
    }
}
