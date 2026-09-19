package com.veltrix.ultron.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeSwipeGestureDetectorTest {
    private val detector = EdgeSwipeGestureDetector(
        minHorizontalDistance = 56f,
        maxVerticalDrift = 88f
    )

    @Test
    fun rightSwipePastThresholdInvokes() {
        detector.onDown(2f, 200f)
        assertTrue(detector.isRightSwipe(70f, 220f))
    }

    @Test
    fun shortOrWrongDirectionGestureDoesNotInvoke() {
        detector.onDown(20f, 200f)
        assertFalse(detector.isRightSwipe(60f, 205f))
        assertFalse(detector.isRightSwipe(-80f, 205f))
    }

    @Test
    fun largeVerticalDriftDoesNotInvoke() {
        detector.onDown(2f, 200f)
        assertFalse(detector.isRightSwipe(90f, 310f))
    }

    @Test
    fun resetClearsGestureState() {
        detector.onDown(2f, 200f)
        detector.reset()
        assertFalse(detector.isRightSwipe(100f, 200f))
    }
}
