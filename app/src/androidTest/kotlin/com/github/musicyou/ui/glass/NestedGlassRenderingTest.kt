package com.github.musicyou.ui.glass

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.backdrop.isRuntimeShaderSupported
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Pixel assertions exercise the real hardware renderer, rather than inspecting
 * modifier configuration. A child must visibly sample its parent glass material
 * and continue updating when only the captured background scene changes.
 */
@RunWith(AndroidJUnit4::class)
class NestedGlassRenderingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun nestedGlassSamplesParentMaterialAndLiveScene() {
        assumeTrue("AGSL refraction requires Android 13+", isRuntimeShaderSupported())
        val sceneColor = mutableStateOf(Color.Blue)
        val parentTint = mutableStateOf(Color.Red.copy(alpha = 0.6f))

        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalGlassStyle provides GlassStyle(blurDp = 14f)) {
                    GlassBackdropRoot(
                        modifier = Modifier.fillMaxSize().testTag("scene"),
                        background = { Box(Modifier.fillMaxSize().background(sceneColor.value)) },
                    ) {
                        GlassSurface(
                            modifier = Modifier.align(Alignment.Center).size(240.dp),
                            shape = RoundedCornerShape(36.dp),
                            tint = parentTint.value,
                        ) {
                            GlassSurface(
                                modifier = Modifier.align(Alignment.Center).size(100.dp).testTag("inner"),
                                shape = RoundedCornerShape(26.dp),
                                tint = Color.Transparent,
                            ) {}
                        }
                    }
                }
            }
        }

        compose.waitForIdle()
        val redParent = screenshot()
        val outsideBefore = redParent.pixel(8, 8)
        val childBefore = redParent.center()

        // The wallpaper stays blue. If the child sampled only that wallpaper,
        // changing its parent's tint would leave the child's center unchanged.
        compose.runOnIdle { parentTint.value = Color.Green.copy(alpha = 0.6f) }
        compose.waitForIdle()
        val greenParent = screenshot()
        val childWithGreenParent = greenParent.center()
        val parentDelta = distance(childBefore, childWithGreenParent)
        Log.i("GlassRenderTest", "Parent-only change: nested RGB delta=$parentDelta")
        assertTrue("Nested glass must reflect the parent's material (RGB delta=$parentDelta)", parentDelta > 0.35f)
        assertTrue("Changing a parent must leave the external scene intact", distance(outsideBefore, greenParent.pixel(8, 8)) < 0.03f)

        // Changing only the captured scene verifies the layers remain live.
        compose.runOnIdle { sceneColor.value = Color.Yellow }
        compose.waitForIdle()
        val yellowScene = screenshot()
        val sceneDelta = distance(childWithGreenParent, yellowScene.center())
        Log.i("GlassRenderTest", "Scene-only change: nested RGB delta=$sceneDelta")
        assertTrue("Nested glass must update when its captured scene changes (RGB delta=$sceneDelta)", sceneDelta > 0.25f)
        assertTrue("The scene change reached the hardware frame", distance(outsideBefore, yellowScene.pixel(8, 8)) > 1f)

        // Repeat a few state-driven frames. This catches captures that only
        // refresh on initial layout or become stale after their first export.
        var previous = yellowScene.center()
        repeat(4) { index ->
            compose.runOnIdle { sceneColor.value = if (index % 2 == 0) Color.Blue else Color.Yellow }
            compose.waitForIdle()
            val next = screenshot().center()
            val frameDelta = distance(previous, next)
            Log.i("GlassRenderTest", "Live update $index: nested RGB delta=$frameDelta")
            assertTrue("Nested material remains live at frame $index (RGB delta=$frameDelta)", frameDelta > 0.25f)
            previous = next
        }
    }

    @Test
    fun nestedGlassIncludesReadableParentBacking() {
        assumeTrue("AGSL refraction requires Android 13+", isRuntimeShaderSupported())
        val wallpaper = WallpaperContrastState()
        val colors = intArrayOf(Color.Black.toArgb(), Color.Blue.toArgb(), Color.White.toArgb())
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalWallpaperContrast provides wallpaper,
                    LocalGlassStyle provides GlassStyle(blurDp = 0f),
                ) {
                    GlassBackdropRoot(
                        modifier = Modifier.fillMaxSize().testTag("scene").onGloballyPositioned {
                            val viewport = it.boundsInWindow()
                            if (wallpaper.viewport != viewport || wallpaper.map == null) {
                                wallpaper.viewport = viewport
                                wallpaper.map = WallpaperColorMap(
                                    width = 3, height = 1,
                                    sourceSize = IntSize(viewport.width.toInt(), viewport.height.toInt()),
                                    pixels = colors,
                                )
                            }
                        },
                        background = {
                            Canvas(Modifier.fillMaxSize()) {
                                val stripe = size.width / 3f
                                colors.forEachIndexed { index, color ->
                                    drawRect(Color(color), Offset(stripe * index, 0f), Size(stripe + 1f, size.height))
                                }
                            }
                        },
                    ) {
                        GlassSurface(
                            modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.9f).height(240.dp),
                            tint = Color.Transparent,
                        ) {
                            GlassSurface(
                                modifier = Modifier.align(Alignment.Center).size(80.dp),
                                tint = Color.Transparent,
                            ) {}
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val nested = screenshot().center()
        // The center scene is pure blue. Bright/dark stripes force the parent
        // to add a light backing; the child must sample that visible material.
        assertTrue("Parent readability backing must be present in its live export", nested.red > 0.25f && nested.green > 0.25f)
        Log.i("GlassRenderTest", "Nested readability backing: center RGB=${nested.red},${nested.green},${nested.blue}")
    }

    private fun screenshot(): ImageBitmap = compose.onNodeWithTag("scene").captureToImage()

    private fun ImageBitmap.center(): Color = pixel(width / 2, height / 2)

    private fun ImageBitmap.pixel(x: Int, y: Int): Color = toPixelMap()[x, y]

    private fun distance(a: Color, b: Color): Float = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
}
