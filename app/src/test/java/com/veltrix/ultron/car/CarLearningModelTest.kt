package com.veltrix.ultron.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLearningModelTest {
    @Test
    fun `ranker prefers proven recent low-failure skill`() {
        val now = 2_000_000_000_000L
        val weak = CarSkillRecord("a", "o", "pkg", "f", "tap:coordinate", 2, 4, 0.35, now - 90L * 86_400_000L, now - 5L, null, CarFailureKind.VERIFICATION_FAILED)
        val strong = CarSkillRecord("b", "o", "pkg", "f", "click:semantic", 12, 1, 0.92, now - 1_000L, null, null, null)
        assertEquals("b", CarSkillRanker.rank(listOf(weak, strong), now, 2).first().key)
    }

    @Test
    fun `loop tracker blocks exact repeated failure but not first attempt`() {
        val tracker = CarFailureLoopTracker(repeatLimit = 2)
        assertEquals(1, tracker.note("screen|strategy"))
        assertFalse(tracker.shouldAvoid("screen|strategy"))
        assertEquals(2, tracker.note("screen|strategy"))
        assertTrue(tracker.shouldAvoid("screen|strategy"))
        assertFalse(tracker.shouldAvoid("different"))
    }

    @Test
    fun `failure classifier distinguishes common recovery causes`() {
        assertEquals(CarFailureKind.ELEMENT_NOT_FOUND, CarFailureClassifier.classify("No visible matching node found"))
        assertEquals(CarFailureKind.NETWORK, CarFailureClassifier.classify("HTTP network error"))
        assertEquals(CarFailureKind.PERMISSION, CarFailureClassifier.classify("Permission not granted"))
    }
}
