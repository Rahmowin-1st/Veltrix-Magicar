package com.veltrix.ultron.service

import kotlin.math.abs

internal class EdgeSwipeGestureDetector(
    private val minHorizontalDistance: Float,
    private val maxVerticalDrift: Float
) {
    private var downX: Float? = null
    private var downY: Float? = null

    fun onDown(x: Float, y: Float) {
        downX = x
        downY = y
    }

    fun isRightSwipe(x: Float, y: Float): Boolean {
        val startX = downX ?: return false
        val startY = downY ?: return false
        val horizontal = x - startX
        val vertical = abs(y - startY)
        return horizontal >= minHorizontalDistance && vertical <= maxVerticalDrift
    }

    fun horizontalProgress(x: Float): Float {
        val startX = downX ?: return 0f
        return (x - startX).coerceAtLeast(0f)
    }

    fun reset() {
        downX = null
        downY = null
    }
}
