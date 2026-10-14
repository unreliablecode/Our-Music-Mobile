package com.github.musicyou.ui.glass

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises public gestures, accessibility actions, and visible spring motion. */
@RunWith(AndroidJUnit4::class)
class LiquidControlsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun togglePressChangesGlassMaterialAndReleaseAnimatesSelection() {
        val checked = mutableStateOf(false)
        val changes = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        scene {
            GlassToggle(
                checked = checked.value,
                onCheckedChange = { checked.value = it; changes.intValue++ },
                modifier = Modifier.testTag("toggle"),
            )
        }
        settle()
        val resting = thumbBounds("glass-toggle-thumb")
        val opaque = thumbBrightness("glass-toggle-thumb")

        compose.onNodeWithTag("toggle").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        val growing = thumbBounds("glass-toggle-thumb")
        assertTrue("Press produces a visible intermediate enlargement", growing.height.value > resting.height.value * 1.05f)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val pressed = thumbBounds("glass-toggle-thumb")
        assertTrue("The held thumb is larger than its resting material", pressed.height.value > resting.height.value * 1.2f)
        assertTrue("The held glass reveals the blue scene instead of remaining white", opaque - thumbBrightness("glass-toggle-thumb") > 0.12f)
        compose.runOnIdle { assertEquals("Pressing alone does not commit the selection", 0, changes.intValue) }

        compose.onNodeWithTag("toggle").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val moving = thumbBounds("glass-toggle-thumb")
        assertTrue("Selection travels through an intermediate position", moving.centerX() > resting.centerX() + 1f)
        settle()
        val selected = thumbBounds("glass-toggle-thumb")
        assertTrue("The selected thumb finishes to the right of its intermediate frame", selected.centerX() > moving.centerX() + 1f)
        assertEquals("Release returns the thumb to its resting size", resting.height.value, selected.height.value, 0.5f)
        compose.onNodeWithTag("toggle").assertIsOn()
        compose.runOnIdle { assertEquals("One tap commits one selection", 1, changes.intValue) }
    }

    @Test
    fun toggleDragSelectsBothDirectionsAndExternalValuesAreSilent() {
        val checked = mutableStateOf(false)
        val changes = mutableIntStateOf(0)
        scene {
            GlassToggle(
                checked = checked.value,
                onCheckedChange = { checked.value = it; changes.intValue++ },
                modifier = Modifier.testTag("toggle"),
            )
        }
        compose.onNodeWithTag("toggle").performTouchInput {
            swipe(Offset(width * 0.2f, height / 2f), Offset(width * 0.9f, height / 2f), durationMillis = 400)
        }
        compose.onNodeWithTag("toggle").assertIsOn()
        compose.runOnIdle { assertEquals("A completed drag commits once", 1, changes.intValue) }
        compose.onNodeWithTag("toggle").performTouchInput {
            swipe(Offset(width * 0.8f, height / 2f), Offset(width * 0.1f, height / 2f), durationMillis = 400)
        }
        compose.onNodeWithTag("toggle").assertIsOff()
        compose.runOnIdle {
            assertEquals("The return drag commits once", 2, changes.intValue)
            checked.value = true
        }
        compose.onNodeWithTag("toggle").assertIsOn()
        compose.runOnIdle { assertEquals("State supplied by the parent never echoes a callback", 2, changes.intValue) }
    }

    @Test
    fun sliderPressRevealsGlassAndDragFinishesExactlyOnce() {
        val value = mutableFloatStateOf(0.2f)
        val changes = mutableIntStateOf(0)
        val finishes = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        scene {
            GlassSlider(
                value = value.floatValue,
                onValueChange = { value.floatValue = it; changes.intValue++ },
                onValueChangeFinished = { finishes.intValue++ },
                modifier = Modifier.width(280.dp).testTag("slider"),
            )
        }
        settle()
        val resting = thumbBounds("glass-slider-thumb")
        val opaque = thumbBrightness("glass-slider-thumb")
        compose.onNodeWithTag("slider").performTouchInput { down(Offset(width * 0.2f, height / 2f)) }
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        val growing = thumbBounds("glass-slider-thumb")
        assertTrue("Slider pressure enlarges gradually", growing.height.value > resting.height.value * 1.05f)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertTrue("A held slider has an enlarged glass thumb", thumbBounds("glass-slider-thumb").height.value > resting.height.value * 1.2f)
        assertTrue("A held slider reveals the scene behind its thumb", opaque - thumbBrightness("glass-slider-thumb") > 0.12f)
        compose.runOnIdle { assertEquals("A held gesture has not finished", 0, finishes.intValue) }

        compose.onNodeWithTag("slider").performTouchInput {
            moveTo(Offset(width * 0.85f, height / 2f), delayMillis = 200)
            up()
        }
        settle()
        compose.runOnIdle {
            assertTrue("The physical drag updates the controlled value", value.floatValue > 0.75f)
            assertTrue("A drag delivers value changes", changes.intValue > 0)
            assertEquals("A drag's release invokes its completion exactly once", 1, finishes.intValue)
        }
        assertEquals("Release settles the slider's resting size", resting.height.value, thumbBounds("glass-slider-thumb").height.value, 0.5f)
    }

    @Test
    fun sliderTapAndAccessibilityCommitValuesWithoutDuplicateCompletions() {
        val value = mutableFloatStateOf(0f)
        val changes = mutableIntStateOf(0)
        val finishes = mutableIntStateOf(0)
        scene {
            GlassSlider(
                value = value.floatValue,
                onValueChange = { value.floatValue = it; changes.intValue++ },
                onValueChangeFinished = { finishes.intValue++ },
                valueRange = 0f..100f,
                modifier = Modifier.width(280.dp).testTag("slider"),
            )
        }
        compose.onNodeWithTag("slider").performTouchInput { click(Offset(width * 0.75f, height / 2f)) }
        compose.runOnIdle {
            assertTrue("Tapping a track seeks near the tapped position", value.floatValue in 70f..85f)
            assertEquals("A physical tap completes once", 1, finishes.intValue)
        }
        compose.onNodeWithTag("slider").performSemanticsAction(SemanticsActions.SetProgress) { action ->
            assertTrue("The accessible progress action accepts a new value", action(40f))
        }
        compose.runOnIdle {
            assertEquals("Accessibility updates the same controlled value", 40f, value.floatValue, 0.01f)
            assertEquals("Accessibility completes its seek once", 2, finishes.intValue)
        }
        compose.onNodeWithTag("slider").performSemanticsAction(SemanticsActions.SetProgress) { action ->
            action(40f)
        }
        compose.runOnIdle { assertEquals("An unchanged accessible value does not repeat a seek", 2, finishes.intValue) }
    }

    @Test
    fun externalSliderValuesAnimateSilentlyAndRtlTouchesReverseTheTrack() {
        val value = mutableFloatStateOf(0.1f)
        val changes = mutableIntStateOf(0)
        val finishes = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        scene(direction = LayoutDirection.Rtl) {
            GlassSlider(
                value = value.floatValue,
                onValueChange = { value.floatValue = it; changes.intValue++ },
                onValueChangeFinished = { finishes.intValue++ },
                modifier = Modifier.width(280.dp).testTag("slider"),
            )
        }
        settle()
        val initial = thumbBounds("glass-slider-thumb").centerX()
        compose.runOnIdle { value.floatValue = 0.9f }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val intermediate = thumbBounds("glass-slider-thumb").centerX()
        assertTrue("A supplied value moves the RTL thumb through an intermediate frame", intermediate < initial - 2f)
        settle()
        val target = thumbBounds("glass-slider-thumb").centerX()
        assertTrue("A greater RTL value settles farther left", target < intermediate - 2f)
        compose.runOnIdle {
            assertEquals("External playback progress does not issue a seek", 0, changes.intValue)
            assertEquals("External playback progress does not finish a gesture", 0, finishes.intValue)
        }

        compose.onNodeWithTag("slider").performTouchInput { click(Offset(width * 0.15f, height / 2f)) }
        settle()
        compose.runOnIdle {
            assertTrue("The visual left side selects a high value in RTL", value.floatValue > 0.8f)
            assertEquals(1, finishes.intValue)
        }
        compose.onNodeWithTag("slider").performTouchInput { click(Offset(width * 0.85f, height / 2f)) }
        settle()
        compose.runOnIdle {
            assertTrue("The visual right side selects a low value in RTL", value.floatValue < 0.2f)
            assertEquals(2, finishes.intValue)
        }
    }

    @Test
    fun disabledControlsExposeTheirStateAndIgnoreTouches() {
        val changes = mutableIntStateOf(0)
        val finishes = mutableIntStateOf(0)
        scene {
            Column {
                GlassToggle(false, { changes.intValue++ }, Modifier.testTag("toggle"), enabled = false)
                GlassSlider(
                    value = 0.4f,
                    onValueChange = { changes.intValue++ },
                    onValueChangeFinished = { finishes.intValue++ },
                    modifier = Modifier.width(280.dp).testTag("slider"),
                    enabled = false,
                )
            }
        }
        compose.onNodeWithTag("toggle").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("slider").assertIsNotEnabled()
        compose.onNodeWithTag("toggle").performTouchInput { click() }
        compose.onNodeWithTag("slider").performTouchInput {
            swipe(Offset(width * 0.2f, height / 2f), Offset(width * 0.8f, height / 2f), durationMillis = 300)
        }
        compose.runOnIdle {
            assertEquals("Disabled controls do not update values", 0, changes.intValue)
            assertEquals("Disabled controls never complete a gesture", 0, finishes.intValue)
        }
    }

    @Test
    fun sliderKeyboardSeeksAndFinishesOnKeyRelease() {
        val value = mutableFloatStateOf(0.4f)
        val finishes = mutableIntStateOf(0)
        scene {
            GlassSlider(
                value = value.floatValue,
                onValueChange = { value.floatValue = it },
                onValueChangeFinished = { finishes.intValue++ },
                modifier = Modifier.width(280.dp).testTag("slider"),
            )
        }
        compose.onNodeWithTag("slider").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("slider").performKeyInput { keyDown(Key.DirectionRight) }
        compose.runOnIdle {
            assertTrue("Arrow Right increases progress", value.floatValue > 0.4f)
            assertEquals("The held key has not completed its seek", 0, finishes.intValue)
        }
        compose.onNodeWithTag("slider").performKeyInput { keyUp(Key.DirectionRight) }
        compose.runOnIdle { assertEquals("Key release completes once", 1, finishes.intValue) }
        compose.onNodeWithTag("slider").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.runOnIdle {
            assertEquals("Arrow Left restores the prior progress", 0.4f, value.floatValue, 0.001f)
            assertEquals("The return key completes once", 2, finishes.intValue)
        }
    }

    @Test
    fun cancelledGesturesDoNotCommitAndRestoreRestingThumbs() {
        val checked = mutableStateOf(false)
        val value = mutableFloatStateOf(0.2f)
        val selections = mutableIntStateOf(0)
        val sliderChanges = mutableIntStateOf(0)
        val finishes = mutableIntStateOf(0)
        val cancellations = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        scene {
            Column {
                GlassToggle(
                    checked = checked.value,
                    onCheckedChange = { checked.value = it; selections.intValue++ },
                    modifier = Modifier.testTag("toggle"),
                )
                GlassSlider(
                    value = value.floatValue,
                    onValueChange = { value.floatValue = it; sliderChanges.intValue++ },
                    onValueChangeFinished = { finishes.intValue++ },
                    onValueChangeCanceled = { cancellations.intValue++ },
                    modifier = Modifier.width(280.dp).testTag("slider"),
                )
            }
        }
        settle()
        val toggleHeight = thumbBounds("glass-toggle-thumb").height.value
        val sliderHeight = thumbBounds("glass-slider-thumb").height.value
        compose.onNodeWithTag("toggle").performTouchInput {
            down(Offset(width * 0.2f, height / 2f))
            moveTo(Offset(width * 0.9f, height / 2f), delayMillis = 200)
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("toggle").performTouchInput { cancel() }
        settle()
        compose.onNodeWithTag("toggle").assertIsOff()
        compose.runOnIdle { assertEquals("A cancelled switch drag never selects", 0, selections.intValue) }
        assertEquals("Cancelling releases the switch pressure", toggleHeight, thumbBounds("glass-toggle-thumb").height.value, 0.5f)

        compose.onNodeWithTag("slider").performTouchInput {
            down(Offset(width * 0.2f, height / 2f))
            moveTo(Offset(width * 0.8f, height / 2f), delayMillis = 200)
        }
        compose.mainClock.advanceTimeBy(300)
        var changesBeforeCancel = 0
        compose.runOnIdle {
            assertTrue("The interrupted gesture had delivered a drag preview", value.floatValue > 0.7f)
            assertEquals("A running drag is not cancelled yet", 0, cancellations.intValue)
            changesBeforeCancel = sliderChanges.intValue
        }
        compose.onNodeWithTag("slider").performTouchInput { cancel() }
        settle()
        compose.runOnIdle {
            assertEquals("A cancelled slider drag never completes", 0, finishes.intValue)
            assertEquals("The parent receives one cancellation to discard its seek preview", 1, cancellations.intValue)
            assertEquals("Cancellation does not create another value change", changesBeforeCancel, sliderChanges.intValue)
        }
        assertEquals("Cancelling releases the slider pressure", sliderHeight, thumbBounds("glass-slider-thumb").height.value, 0.5f)
    }

    private fun scene(direction: LayoutDirection = LayoutDirection.Ltr, content: @Composable () -> Unit) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    GlassBackdropRoot(
                        modifier = Modifier.fillMaxSize(),
                        background = { Box(Modifier.fillMaxSize().background(Color(0xFF1234AF))) },
                    ) {
                        Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) { content() }
                    }
                }
            }
        }
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun thumbBounds(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()

    private fun DpRect.centerX(): Float = (left.value + right.value) / 2f

    private fun thumbBrightness(tag: String): Float {
        val pixels = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        // Sample above the track, away from highlights, rounded edges, and shadows.
        val color = pixels[pixels.width / 2, pixels.height / 4]
        return (color.red + color.green + color.blue) / 3f
    }
}
