package com.github.musicyou.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveContrastTest {
    @Test
    fun luminanceAndContrastMatchKnownBlackWhiteAndNormalTextBoundary() {
        assertEquals(0f, relativeLuminance(Color.Black), 0.0001f)
        assertEquals(1f, relativeLuminance(Color.White), 0.0001f)
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.001f)
        // Adjacent sRGB byte values straddle the normal-text threshold.
        assertEquals(4.49295f, contrastRatio(Color.Black, Color(0xFF747474)), 0.0001f)
        assertTrue(contrastRatio(Color.Black, Color(0xFF747474)) < 4.5f)
        assertTrue(contrastRatio(Color.Black, Color(0xFF757575)) > 4.5f)
    }

    @Test
    fun independentLightAndDarkRegionsChooseDifferentReadableForegrounds() {
        val light = resolveContrast(listOf(Color(0xFFF8F5ED), Color(0xFFE6DDD5)))
        val dark = resolveContrast(listOf(Color(0xFF131B24), Color(0xFF253346)))
        assertTrue(relativeLuminance(light.foreground) < 0.05f)
        assertTrue(relativeLuminance(dark.foreground) > 0.9f)
        assertEquals(0f, light.scrim.alpha, 0f)
        assertEquals(0f, dark.scrim.alpha, 0f)
        assertTrue(light.minimumContrast >= 4.5f)
        assertTrue(dark.minimumContrast >= 4.5f)
    }

    @Test
    fun mixedBrightDarkPhotoRequiresBackingAndKeepsEverySampleReadable() {
        val colors = listOf(Color.Black, Color.White, Color(0xFFE02431), Color(0xFF216FCC), Color(0xFF979797))
        val resolution = resolveContrast(colors)
        assertTrue("One foreground alone cannot cover black and white patches", resolution.scrim.alpha > 0f)
        assertTrue("Backing should remain translucent", resolution.scrim.alpha < 0.7f)
        colors.forEach { color ->
            assertTrue(contrastRatio(resolution.foreground, resolution.scrim.compositeOver(color)) >= 4.5f)
        }
    }

    @Test
    fun requestedCoralAdaptsOnBothPhotoRegionsAtNormalTextContrast() {
        val coral = Color(0xFFFA2D48)
        for (background in listOf(Color(0xFFFDFDFD), Color(0xFF111B25), Color(0xFF888888))) {
            val resolution = resolveContrast(listOf(background), coral)
            assertTrue(contrastRatio(resolution.foreground, resolution.scrim.compositeOver(background)) >= 4.5f)
        }
    }

    @Test
    fun theSameScreenRegionAdaptsAsThePhotoMovesBehindIt() {
        val state = WallpaperContrastState().apply {
            map = WallpaperColorMap(4, 1, IntSize(400, 100), intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()))
            viewport = Rect(0f, 0f, 200f, 100f)
        }
        val textBounds = Rect(60f, 10f, 90f, 40f)
        state.appearance = BackgroundAppearance(positionX = 1f)
        val darkPhotoPatch = resolveContrast(state.sample(textBounds, Color.Transparent))
        state.appearance = BackgroundAppearance(positionX = -1f)
        val lightPhotoPatch = resolveContrast(state.sample(textBounds, Color.Transparent))
        assertTrue(relativeLuminance(darkPhotoPatch.foreground) > 0.9f)
        assertTrue(relativeLuminance(lightPhotoPatch.foreground) < 0.05f)
        assertTrue(darkPhotoPatch.minimumContrast >= 4.5f)
        assertTrue(lightPhotoPatch.minimumContrast >= 4.5f)
    }
}
