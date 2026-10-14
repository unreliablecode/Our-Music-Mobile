package com.github.musicyou.ui.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundCropTest {
    @Test
    fun cropCoversFoldedAndUnfoldedDisplaysAtEveryAllowedPanEdge() {
        val displays = listOf(IntSize(1080, 2340), IntSize(1856, 2160), IntSize(2160, 1856))
        val photos = listOf(IntSize(2048, 2048), IntSize(6000, 4000), IntSize(1080, 2400), IntSize(4096, 1024))
        for (display in displays) for (photo in photos) for (zoom in listOf(1f, 1.5f, 4f)) {
            val extent = backgroundImageExtent(display, photo, zoom)
            for (x in listOf(-1f, 0f, 1f)) for (y in listOf(-1f, 0f, 1f)) {
                assertPhotoCoversDisplay(display, extent, x, y)
            }
        }
    }

    @Test
    fun pinchKeepsTheSamePhotoPixelUnderMovingFingers() {
        val display = IntSize(1080, 2340)
        val photo = IntSize(4000, 6000)
        val before = BackgroundAppearance(zoom = 1.5f, positionX = 0.2f, positionY = -0.15f)
        val centroid = Offset(650f, 900f)
        val fingerMovement = Offset(35f, -20f)
        val after = repositionBackground(before, display, photo, centroid, fingerMovement, zoomChange = 1.2f)
        val beforeTranslation = photoTranslation(display, backgroundImageExtent(display, photo, before.zoom), before)
        val afterTranslation = photoTranslation(display, backgroundImageExtent(display, photo, after.zoom), after)
        val center = Offset(display.width / 2f, display.height / 2f)
        val photoPointAtBaseZoom = (centroid - center - beforeTranslation) / before.zoom
        val photoPointAfterPinch = center + afterTranslation + photoPointAtBaseZoom * after.zoom

        assertEquals(centroid.x + fingerMovement.x, photoPointAfterPinch.x, 0.6f)
        assertEquals(centroid.y + fingerMovement.y, photoPointAfterPinch.y, 0.6f)
        assertPhotoCoversDisplay(display, backgroundImageExtent(display, photo, after.zoom), after.positionX, after.positionY)
    }

    @Test
    fun dragBeyondPhotoEdgesStopsAtTheLastPixel() {
        val display = IntSize(1856, 2160)
        val photo = IntSize(6000, 4000)
        val dragged = repositionBackground(
            BackgroundAppearance(zoom = 2f), display, photo,
            centroid = Offset(900f, 1000f), pan = Offset(50_000f, -50_000f), zoomChange = 1f,
        )
        assertEquals(1f, dragged.positionX, 0f)
        assertEquals(-1f, dragged.positionY, 0f)
        assertPhotoCoversDisplay(display, backgroundImageExtent(display, photo, dragged.zoom), dragged.positionX, dragged.positionY)
    }

    @Test
    fun zoomBoundsPreventEmptyMarginsAndExcessiveMagnification() {
        val display = IntSize(1080, 2340)
        val photo = IntSize(1080, 2340)
        val centered = Offset(540f, 1170f)
        val zoomedIn = repositionBackground(BackgroundAppearance(), display, photo, centered, Offset.Zero, 100f)
        assertEquals(4f, zoomedIn.zoom, 0f)
        val zoomedOut = repositionBackground(zoomedIn, display, photo, centered, Offset.Zero, 0.001f)
        assertEquals(1f, zoomedOut.zoom, 0f)
        assertEquals(0f, zoomedOut.positionX, 0f)
        assertEquals(0f, zoomedOut.positionY, 0f)
        assertPhotoCoversDisplay(display, backgroundImageExtent(display, photo, zoomedOut.zoom), zoomedOut.positionX, zoomedOut.positionY)
    }

    @Test
    fun gesturesBeforePhotoLoadsLeaveTheCropUntouched() {
        val appearance = BackgroundAppearance(zoom = 2f, positionX = -0.3f, positionY = 0.5f)
        assertEquals(
            appearance,
            repositionBackground(appearance, IntSize(1080, 2340), IntSize.Zero, Offset(200f, 400f), Offset(50f, 50f), 1.5f),
        )
    }

    private fun photoTranslation(display: IntSize, extent: IntSize, appearance: BackgroundAppearance) = Offset(
        appearance.positionX * (extent.width - display.width).coerceAtLeast(0) / 2f,
        appearance.positionY * (extent.height - display.height).coerceAtLeast(0) / 2f,
    )

    private fun assertPhotoCoversDisplay(display: IntSize, photo: IntSize, positionX: Float, positionY: Float) {
        val translationX = positionX * (photo.width - display.width) / 2f
        val translationY = positionY * (photo.height - display.height) / 2f
        val left = (display.width - photo.width) / 2f + translationX
        val top = (display.height - photo.height) / 2f + translationY
        assertTrue("Photo left edge exposes the background", left <= 0.01f)
        assertTrue("Photo top edge exposes the background", top <= 0.01f)
        assertTrue("Photo right edge exposes the background", left + photo.width >= display.width - 0.01f)
        assertTrue("Photo bottom edge exposes the background", top + photo.height >= display.height - 0.01f)
    }
}
