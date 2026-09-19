package com.veltrix.ultron.memory

import java.util.Locale

/**
 * Model-context boundary for durable personal memory.
 *
 * Callers must name the model audience explicitly. The underlying policy-bound store
 * applies USER_ONLY, positive audience allow-lists and current owner HARD DENY state
 * before this class receives any key/value content. Hints intentionally omit record
 * ids, source metadata, timestamps and confidence because the planner does not need
 * those fields to use a remembered fact safely.
 */
class PersonalMemoryRuntimeContext(
    private val store: PolicyBoundPersonalMemoryStore
) {
    fun hintsFor(
        audience: MemoryAudience,
        kind: MemoryKind? = null,
        query: String? = null,
        limit: Int = MAX_HINTS
    ): List<String> {
        val boundedLimit = limit.coerceIn(0, MAX_HINTS)
        if (boundedLimit == 0) return emptyList()

        return store.recallForModel(audience = audience, kind = kind, query = query)
            .asSequence()
            .take(boundedLimit)
            .mapNotNull(::toHint)
            .toList()
    }

    private fun toHint(record: MemoryRecord): String? {
        val key = PersonalMemoryPersistencePolicy.sanitizeText(record.key, MAX_HINT_KEY_CHARS)
        val value = PersonalMemoryPersistencePolicy.sanitizeText(record.value, MAX_HINT_VALUE_CHARS)
        if (key.isBlank() || value.isBlank()) return null
        return "personal_memory:${record.kind.name.lowercase(Locale.ROOT)}:$key=$value"
    }

    companion object {
        const val MAX_HINTS = 12
        const val MAX_HINT_KEY_CHARS = 160
        const val MAX_HINT_VALUE_CHARS = 800
    }
}
