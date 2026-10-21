package com.github.musicyou.ui.glass

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GlassDecorationRenderingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun materialOutlineClipsChildrenThatDrawBeyondTheirBounds() {
        compose.setContent {
            val source = rememberCanvasBackdrop { drawRect(Color.White) }
            Box(Modifier.size(240.dp).testTag("scene").background(Color.Blue)) {
                Box(
                    Modifier.align(Alignment.Center).size(160.dp)
                        .drawPlainBackdrop(
                            backdrop = source,
                            shape = { RoundedCornerShape(36.dp) },
                            effects = {},
                        ),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(
                            Color.Green,
                            topLeft = Offset(-40.dp.toPx(), -40.dp.toPx()),
                            size = Size(size.width + 80.dp.toPx(), size.height + 80.dp.toPx()),
                        )
                    }
                }
            }
        }
        val pixels = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        val edge = pixels.width / 6
        val outside = pixels[edge - 3, pixels.height / 2]
        val roundedCorner = pixels[edge + 2, edge + 2]
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("A child cannot paint outside the parent rectangle", distance(outside, Color.Blue) < 0.02f)
        assertTrue("The parent's rounded corners still clip the child", distance(roundedCorner, Color.Blue) < 0.02f)
        assertTrue("The child's interior remains visible", distance(center, Color.Green) < 0.02f)
    }

    @Test
    fun cachedDecorationUpdatesWhenItsColorWidthAndShapeChange() {
        val color = mutableStateOf(Color.Red)
        val width = mutableStateOf(2.dp)
        val corner = mutableStateOf(24.dp)
        val materialColor = mutableStateOf(Color.White)
        compose.setContent {
            val source = rememberCanvasBackdrop { drawRect(materialColor.value) }
            Box(Modifier.size(240.dp).testTag("scene").background(Color.White)) {
                Box(
                    Modifier.align(Alignment.Center).size(160.dp)
                        .drawBackdrop(
                            backdrop = source,
                            shape = { RoundedCornerShape(corner.value) },
                            effects = {},
                            highlight = {
                                Highlight(
                                    width = width.value,
                                    blurRadius = 0.dp,
                                    style = HighlightStyle.Plain(color.value, BlendMode.SrcOver),
                                )
                            },
                            shadow = { Shadow(radius = 8.dp, color = color.value.copy(alpha = 0.8f)) },
                        ),
                )
            }
        }
        fun sample(insetDp: Float): Color {
            val pixels = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
            val scale = pixels.width / 240f
            return pixels[pixels.width / 2, ((40f + insetDp) * scale).toInt()]
        }
        assertTrue("The first highlight is red", sample(1f).red > 0.9f && sample(1f).green < 0.1f)
        assertTrue("A thin border leaves the inner material white", sample(6f).green > 0.9f)
        val firstShadow = sample(-3f)
        assertTrue("The first shadow keeps its red tint", firstShadow.red > firstShadow.green + 0.01f)
        compose.runOnIdle { color.value = Color.Green; width.value = 10.dp; corner.value = 40.dp }
        val changed = sample(6f)
        assertTrue("An animated border updates its cached color and width", changed.green > 0.9f && changed.red < 0.1f)
        val changedShadow = sample(-3f)
        assertTrue("The cached shadow updates its tint", changedShadow.green > changedShadow.red + 0.01f)
        repeat(3) { index ->
            compose.runOnIdle { materialColor.value = if (index % 2 == 0) Color.DarkGray else Color.White }
            val stable = sample(6f)
            assertTrue("A changing material leaves the cached border intact", distance(changed, stable) < 0.02f)
        }
    }

    private fun distance(a: Color, b: Color): Float =
        abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
}
