package com.veltrix.ultron.executor

import java.util.Locale

/** Pure Unicode-aware semantic scoring shared by native and web Accessibility nodes. */
object SemanticTargetMatcher {
    fun score(query: String, values: Iterable<String?>, focused: Boolean = false): Int {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return 0

        var best = 0
        values.forEach { raw ->
            val normalizedValue = raw?.let(::normalize).orEmpty()
            if (normalizedValue.isEmpty()) return@forEach
            val score = when {
                normalizedValue == normalizedQuery -> 100
                normalizedValue.startsWith(normalizedQuery) || normalizedQuery.startsWith(normalizedValue) -> 85
                normalizedValue.endsWith(normalizedQuery) || normalizedQuery.endsWith(normalizedValue) -> 80
                normalizedValue.contains(normalizedQuery) || normalizedQuery.contains(normalizedValue) -> 65
                else -> 0
            }
            if (score > best) best = score
        }

        return if (best > 0 && focused) best + 10 else best
    }

    fun uniqueBestIndex(scores: List<Int>): Int? {
        val best = scores.maxOrNull()?.takeIf { it > 0 } ?: return null
        var selected: Int? = null
        scores.forEachIndexed { index, score ->
            if (score == best) {
                if (selected != null) return null
                selected = index
            }
        }
        return selected
    }

    internal fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}