package com.veltrix.ultron.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticTargetMatcherTest {
    @Test
    fun matchesUzbekLabelsAcrossPunctuationAndCase() {
        assertEquals(
            100,
            SemanticTargetMatcher.score(
                query = "OQISH",
                values = listOf("O‘qish")
            )
        )
    }

    @Test
    fun matchesCyrillicLabelsWithoutDroppingLetters() {
        assertEquals(
            100,
            SemanticTargetMatcher.score(
                query = "настройки",
                values = listOf("Настройки")
            )
        )
    }

    @Test
    fun resourceStyleIdentifiersCanMatchHumanSpacing() {
        assertEquals(
            100,
            SemanticTargetMatcher.score(
                query = "search input",
                values = listOf("search_input")
            )
        )
    }

    @Test
    fun focusedButUnrelatedNodeNeverBecomesAFalseMatch() {
        assertEquals(
            0,
            SemanticTargetMatcher.score(
                query = "email",
                values = listOf("password"),
                focused = true
            )
        )
    }

    @Test
    fun focusOnlyBoostsAnExistingSemanticMatch() {
        val plain = SemanticTargetMatcher.score("email", listOf("email address"))
        val focused = SemanticTargetMatcher.score("email", listOf("email address"), focused = true)

        assertTrue(plain > 0)
        assertEquals(plain + 10, focused)
    }

    @Test
    fun equalTopScoresAreAmbiguousAndNeverSelected() {
        assertNull(SemanticTargetMatcher.uniqueBestIndex(listOf(100, 65, 100)))
    }

    @Test
    fun oneUniqueTopScoreIsSelectedDeterministically() {
        assertEquals(1, SemanticTargetMatcher.uniqueBestIndex(listOf(65, 100, 85)))
    }
}