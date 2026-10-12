package com.hatsunama.captionaction.service

/** Pixel geometry shared by initial placement, resizing, rotation and auto-growth. */
internal object OverlayBounds {
    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)
    private const val MAX_HEIGHT_FRACTION = 0.48f

    fun growthHeight(measuredHeight: Int, screenHeight: Int, density: Float): Int {
        val maxHeight = (screenHeight.coerceAtLeast(1) * MAX_HEIGHT_FRACTION).toInt().coerceAtLeast(1)
        val minHeight = (180 * density).toInt().coerceIn(1, maxHeight)
        return measuredHeight.coerceIn(minHeight, maxHeight)
    }

    fun clamp(x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int, density: Float): Rect {
        val availableWidth = screenWidth.coerceAtLeast(1)
        val availableHeight = screenHeight.coerceAtLeast(1)
        val minWidth = (160 * density).toInt().coerceIn(1, availableWidth)
        val boundedWidth = width.coerceIn(minWidth, availableWidth)
        val boundedHeight = growthHeight(height, availableHeight, density)
        return Rect(x.coerceIn(0, availableWidth - boundedWidth),
            y.coerceIn(0, availableHeight - boundedHeight), boundedWidth, boundedHeight)
    }
}
