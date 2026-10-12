package com.hatsunama.captionaction.service

import org.junit.Assert.*
import org.junit.Test

class OverlayBoundsTest {
    @Test fun shortLandscapeGrowth_doesNotInvertBounds() {
        assertEquals(153, OverlayBounds.growthHeight(240, 320, 1f))
    }
    @Test fun portraitGrowth_preservesOriginalLimits() {
        assertEquals(180, OverlayBounds.growthHeight(100, 800, 1f))
        assertEquals(240, OverlayBounds.growthHeight(240, 800, 1f))
        assertEquals(384, OverlayBounds.growthHeight(600, 800, 1f))
    }
    @Test fun validPortraitGeometry_isUnchanged() {
        val rect = OverlayBounds.Rect(40, 300, 280, 200)
        assertEquals(rect, OverlayBounds.clamp(40, 300, 280, 200, 400, 800, 1f))
    }
    @Test fun rotation_clampsSavedPortraitGeometryIntoLandscape() {
        assertEquals(OverlayBounds.Rect(40, 167, 320, 153),
            OverlayBounds.clamp(40, 600, 320, 300, 800, 320, 1f))
    }
    @Test fun movingAndResizing_remainsReachable() {
        assertEquals(OverlayBounds.Rect(0, 0, 400, 384),
            OverlayBounds.clamp(-200, -50, 900, 900, 400, 800, 1f))
        assertEquals(OverlayBounds.Rect(240, 620, 160, 180),
            OverlayBounds.clamp(999, 999, 50, 50, 400, 800, 1f))
    }
    @Test fun tinyAndMultiwindowSizes_neverProduceEmptyOrOffscreenGeometry() {
        for (w in listOf(0, 1, 80, 240, 640)) for (h in listOf(0, 1, 40, 200, 320)) {
            val r = OverlayBounds.clamp(-1, 9999, 900, 900, w, h, 3f)
            assertTrue(r.width in 1..w.coerceAtLeast(1))
            assertTrue(r.height in 1..h.coerceAtLeast(1))
            assertTrue(r.x >= 0 && r.y >= 0)
            assertTrue(r.x + r.width <= w.coerceAtLeast(1))
            assertTrue(r.y + r.height <= h.coerceAtLeast(1))
        }
    }
}
