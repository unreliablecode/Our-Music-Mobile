package com.github.musicyou.ui.apple

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ModalOverlayTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backClosesTopmostOverlayBeforeNavigatingBehindIt() {
        val playerOpen = mutableStateOf(false)
        val menuOpen = mutableStateOf(false)
        lateinit var navController: NavHostController
        compose.setContent {
            MaterialTheme {
                navController = rememberNavController()
                Box(Modifier.fillMaxSize()) {
                    NavHost(navController, startDestination = "home") {
                        composable("home") {
                            Button(onClick = { navController.navigate("album") }) { Text("Open album") }
                        }
                        composable("album") { Text("Album page") }
                    }
                    ModalOverlay(
                        active = playerOpen.value,
                        onDismiss = { playerOpen.value = false },
                        paneTitle = "Now playing",
                        modifier = Modifier.modalUnderlay(menuOpen.value),
                    ) { Text("Player contents", Modifier.align(Alignment.Center)) }
                    ModalOverlay(
                        active = menuOpen.value,
                        onDismiss = { menuOpen.value = false },
                        paneTitle = "Playback options",
                    ) { Text("Menu contents", Modifier.align(Alignment.Center)) }
                }
            }
        }
        compose.onNodeWithText("Open album").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("album", navController.currentDestination?.route)
            playerOpen.value = true
            menuOpen.value = true
        }
        compose.waitForIdle()

        pressBack()
        compose.runOnIdle {
            assertFalse("Back dismisses the menu first", menuOpen.value)
            assertTrue("The player remains open under its menu", playerOpen.value)
            assertEquals("album", navController.currentDestination?.route)
        }
        compose.onNodeWithText("Menu contents").assertDoesNotExist()
        compose.onNodeWithText("Player contents").assertIsDisplayed()

        pressBack()
        compose.runOnIdle {
            assertFalse("The next Back dismisses the player", playerOpen.value)
            assertEquals("The album route must survive closing its player", "album", navController.currentDestination?.route)
        }
        compose.onNodeWithText("Player contents").assertDoesNotExist()
        compose.onNodeWithText("Album page").assertIsDisplayed()

        pressBack()
        compose.runOnIdle { assertEquals("home", navController.currentDestination?.route) }
        compose.onNodeWithText("Open album").assertIsDisplayed()
    }

    @Test
    fun blankModalRegionsBlockBackgroundTapsAndScrollingUntilDismissed() {
        val open = mutableStateOf(true)
        val backgroundClicks = mutableIntStateOf(0)
        lateinit var scroll: androidx.compose.foundation.ScrollState
        compose.setContent {
            MaterialTheme {
                scroll = rememberScrollState()
                Box(Modifier.fillMaxSize().testTag("scene")) {
                    Box(
                        Modifier.fillMaxSize().modalUnderlay(open.value)
                            .clickable { backgroundClicks.intValue++ },
                    ) {
                        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                            repeat(60) { Text("Background row $it", Modifier.fillMaxWidth().height(48.dp)) }
                        }
                    }
                    ModalOverlay(open.value, { open.value = false }, "Now playing") {
                        Text("Player contents", Modifier.align(Alignment.TopCenter).padding(24.dp))
                    }
                }
            }
        }
        backgroundTapAndSwipe()
        compose.runOnIdle {
            assertEquals("Taps in transparent player regions must not reach the page", 0, backgroundClicks.intValue)
            assertEquals("Drags in transparent player regions must not scroll the page", 0, scroll.value)
            open.value = false
        }
        compose.waitForIdle()
        backgroundTapAndSwipe()
        compose.runOnIdle {
            assertEquals("Background taps work again after closing the player", 1, backgroundClicks.intValue)
            assertTrue("Background scrolling works again after closing the player", scroll.value > 0)
        }
    }

    @Test
    fun modalButtonSliderAndDragControlsReceiveRealTouchEvents() {
        val buttonClicks = mutableIntStateOf(0)
        val backgroundClicks = mutableIntStateOf(0)
        val value = mutableFloatStateOf(0.2f)
        val dragged = mutableFloatStateOf(0f)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clickable { backgroundClicks.intValue++ })
                    ModalOverlay(true, {}, "Now playing") {
                        Button(
                            onClick = { buttonClicks.intValue++ },
                            modifier = Modifier.align(Alignment.TopCenter).padding(24.dp).testTag("player-action"),
                        ) { Text("Player action") }
                        Slider(
                            value = value.floatValue,
                            onValueChange = { value.floatValue = it },
                            modifier = Modifier.align(Alignment.Center).width(240.dp).testTag("player-slider"),
                        )
                        Box(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                                .size(180.dp, 90.dp).testTag("player-drag")
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures { change, distance ->
                                        change.consume()
                                        dragged.floatValue += distance
                                    }
                                },
                        ) { Text("Drag area") }
                    }
                }
            }
        }
        compose.onNodeWithTag("player-action").performTouchInput { click() }
        compose.onNodeWithTag("player-slider").performTouchInput {
            swipe(Offset(width * 0.2f, height / 2f), Offset(width * 0.8f, height / 2f), durationMillis = 400)
        }
        compose.onNodeWithTag("player-drag").performTouchInput {
            swipe(Offset(width * 0.2f, height / 2f), Offset(width * 0.8f, height / 2f), durationMillis = 400)
        }
        compose.runOnIdle {
            assertEquals("The modal button receives a physical tap", 1, buttonClicks.intValue)
            assertTrue("The modal slider receives its drag", value.floatValue > 0.65f)
            assertTrue("Custom modal gestures are not cancelled by the barrier", dragged.floatValue > 30f)
            assertEquals("Control gestures never reach the background", 0, backgroundClicks.intValue)
        }
    }

    @Test
    fun backgroundSemanticsAreHiddenWhileItsVisiblePixelsRemainCaptured() {
        val open = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("scene")) {
                    Box(
                        Modifier.fillMaxSize().modalUnderlay(open.value).background(Color(0xFF1234AB)),
                    ) {
                        Button(onClick = {}, modifier = Modifier.align(Alignment.TopStart)) { Text("Browse page") }
                    }
                    ModalOverlay(open.value, { open.value = false }, "Now playing") {
                        Text("Player contents", Modifier.align(Alignment.TopEnd))
                    }
                }
            }
        }
        compose.onNodeWithText("Browse page").assertIsDisplayed()
        val before = sceneCenter()
        compose.runOnIdle { open.value = true }
        compose.onNodeWithText("Browse page").assertDoesNotExist()
        compose.onNodeWithText("Player contents").assertIsDisplayed()
        val during = sceneCenter()
        assertTrue(
            "Removing background semantics must keep the scene drawn for glass sampling",
            abs(before.red - during.red) + abs(before.green - during.green) + abs(before.blue - during.blue) < 0.02f,
        )
        pressBack()
        compose.onNodeWithText("Browse page").assertIsDisplayed()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun backgroundTapAndSwipe() {
        compose.onNodeWithTag("scene").performTouchInput {
            click(Offset(width * 0.8f, height * 0.75f))
            swipe(Offset(width * 0.7f, height * 0.8f), Offset(width * 0.7f, height * 0.35f), durationMillis = 400)
        }
        compose.waitForIdle()
    }

    private fun sceneCenter(): Color {
        val pixels = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }
}
