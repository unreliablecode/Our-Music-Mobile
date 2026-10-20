package com.github.musicyou.ui.glass

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Counts actual hardware draw work while checking that the captured scene stays live. */
@RunWith(AndroidJUnit4::class)
class GlassCaptureWorkTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun defaultCaptureDrawsItsContentOncePerVisiblePass() {
        val visiblePasses = AtomicInteger()
        val contentPasses = AtomicInteger()
        val color = mutableStateOf(Color.Blue)
        compose.setContent {
            val backdrop = rememberLayerBackdrop()
            Box(
                Modifier.size(160.dp).testTag("scene")
                    .drawWithContent { visiblePasses.incrementAndGet(); drawContent() }
                    .layerBackdrop(backdrop),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    contentPasses.incrementAndGet()
                    drawRect(color.value)
                }
            }
        }
        repeat(4) { index ->
            val expected = if (index % 2 == 0) Color.Red else Color.Blue
            compose.runOnIdle { color.value = expected }
            val center = centerPixel()
            assertEquals("The visible frame keeps the source color", expected, center)
            compose.runOnIdle {
                assertTrue("The scene has been drawn", visiblePasses.get() > 0)
                assertEquals("Recording the default scene must not draw its contents twice", visiblePasses.get(), contentPasses.get())
            }
        }
    }

    @Test
    fun customCapturePreservesTheVisibleChildrenWithoutAnExtraPass() {
        val visiblePasses = AtomicInteger()
        val contentPasses = AtomicInteger()
        val customPasses = AtomicInteger()
        compose.setContent {
            val backdrop = rememberLayerBackdrop(onDraw = {
                customPasses.incrementAndGet()
                drawRect(Color.Green)
            })
            Box(
                Modifier.size(160.dp).testTag("scene")
                    .drawWithContent { visiblePasses.incrementAndGet(); drawContent() }
                    .layerBackdrop(backdrop),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    contentPasses.incrementAndGet()
                    drawRect(Color.Blue)
                }
            }
        }
        assertEquals("A custom export must not replace the visible content", Color.Blue, centerPixel())
        compose.runOnIdle {
            assertTrue("The custom capture ran", customPasses.get() > 0)
            assertEquals("Custom capture keeps one visible child pass", visiblePasses.get(), contentPasses.get())
            assertEquals("Custom recording happens once per visible pass", visiblePasses.get(), customPasses.get())
        }
    }

    @Test
    fun nestedMaterialReusesOneCurrentFrameBackdropRecording() {
        val parentPasses = AtomicInteger()
        val sourcePasses = AtomicInteger()
        val surfacePasses = AtomicInteger()
        val color = mutableStateOf(Color.Blue)
        compose.setContent {
            val source = remember {
                object : Backdrop {
                    override val isCoordinatesDependent = false
                    override fun DrawScope.drawBackdrop(
                        density: Density,
                        coordinates: LayoutCoordinates?,
                        layerBlock: (GraphicsLayerScope.() -> Unit)?,
                    ) {
                        sourcePasses.incrementAndGet()
                        drawRect(color.value)
                    }
                }
            }
            val material = rememberLayerBackdrop()
            Box(
                Modifier.size(200.dp).testTag("scene")
                    .drawWithContent { parentPasses.incrementAndGet(); drawContent() }
                    .drawPlainBackdrop(
                        backdrop = source,
                        shape = { RoundedCornerShape(20.dp) },
                        effects = {},
                        exportedBackdrop = material,
                        onDrawSurface = {
                            surfacePasses.incrementAndGet()
                            drawRect(Color.White.copy(alpha = 0.2f))
                        },
                    ),
            ) {
                Box(
                    Modifier.align(Alignment.Center).size(80.dp)
                        .drawPlainBackdrop(
                            backdrop = material,
                            shape = { RoundedCornerShape(12.dp) },
                            effects = {},
                        ),
                )
            }
        }
        repeat(4) { index ->
            val red = index % 2 == 0
            compose.runOnIdle { color.value = if (red) Color.Red else Color.Blue }
            val child = centerPixel()
            assertTrue("The nested child samples this frame's parent material", if (red) child.red > child.blue + 0.5f else child.blue > child.red + 0.5f)
            assertTrue("The child keeps the parent surface tint", child.green > 0.15f)
            compose.runOnIdle {
                assertTrue("The parent material has been drawn", parentPasses.get() > 0)
                // The material node can redraw inside a cached outer graphics
                // layer. Count both surface outputs at that same node instead.
                assertEquals("Visible material and its nested export share one source recording", surfacePasses.get(), sourcePasses.get() * 2)
            }
        }
    }

    private fun centerPixel(): Color {
        val pixels = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }
}
