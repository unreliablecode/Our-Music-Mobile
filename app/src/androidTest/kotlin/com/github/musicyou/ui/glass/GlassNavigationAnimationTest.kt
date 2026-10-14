package com.github.musicyou.ui.glass

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Verifies the visible lens travels between the actual tab hit targets. */
@RunWith(AndroidJUnit4::class)
class GlassNavigationAnimationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun switchingTabsHasIntermediateMotionAndSettlesOnSelectedTab() {
        val selected = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                GlassBackdropRoot(
                    modifier = Modifier.fillMaxSize(),
                    background = { Box(Modifier.fillMaxSize().background(Color.White)) },
                ) {
                    AnimatedGlassTabs(
                        tabs = listOf(
                            GlassTab("Home", Icons.Outlined.Home),
                            GlassTab("New", Icons.Outlined.GridView),
                            GlassTab("Radio", Icons.Outlined.Radio),
                            GlassTab("Library", Icons.Outlined.LibraryMusic),
                        ),
                        selectedIndex = selected.intValue,
                        onSelect = { selected.intValue = it },
                        modifier = Modifier.align(Alignment.Center).width(320.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val initial = indicatorCenter()
        val homeBounds = compose.onNodeWithText("Home").getUnclippedBoundsInRoot()
        val home = (homeBounds.left.value + homeBounds.right.value) / 2f
        assertTrue("The initial lens is aligned with Home", abs(initial - home) < 2f)

        compose.onNodeWithText("Library").performClick()
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        val destinationBounds = compose.onNodeWithText("Library").getUnclippedBoundsInRoot()
        val destination = (destinationBounds.left.value + destinationBounds.right.value) / 2f
        val intermediate = indicatorCenter()
        assertTrue("The lens advances from its previous tab", intermediate > initial + 8f)
        assertTrue("The lens passes through an intermediate position rather than jumping", intermediate < destination - 8f)

        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("The lens settles on the selected tab's hit target", abs(indicatorCenter() - destination) < 2f)
        compose.onNodeWithText("Library").assertIsSelected()

        // A second interaction must also animate, rather than only the first.
        compose.onNodeWithText("New").performClick()
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        val newDestinationBounds = compose.onNodeWithText("New").getUnclippedBoundsInRoot()
        val newDestination = (newDestinationBounds.left.value + newDestinationBounds.right.value) / 2f
        val returning = indicatorCenter()
        assertTrue("The lens moves back continuously", returning < destination - 8f && returning > newDestination + 8f)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("The return animation lands on New", abs(indicatorCenter() - newDestination) < 2f)
        compose.onNodeWithText("New").assertIsSelected()
    }

    private fun indicatorCenter(): Float {
        val bounds = compose.onNodeWithTag("glass-tab-indicator", useUnmergedTree = true).getUnclippedBoundsInRoot()
        return (bounds.left.value + bounds.right.value) / 2f
    }
}
