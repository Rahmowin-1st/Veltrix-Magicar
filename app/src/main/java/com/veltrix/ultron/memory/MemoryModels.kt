package com.veltrix.ultron.memory

import java.time.Instant
import java.util.UUID

enum class MemoryKind {
    PREFERENCE,
    PERSON,
    PROJECT,
    DECISION,
    APP_STATE,
    SKILL,
    FOLLOW_UP,
    FACT
}

/**
 * Explicit model audiences for durable personal memory.
 * USER_ONLY is represented by isPrivate=true, which always wins over this allow-list.
 */
enum class MemoryAudience {
    MAIN_ASSISTANT,
    SEARCHER,
    WORKER,
    REPORTER
}

data class MemoryRecord(
    val id: String = UUID.randomUUID().toString(),
    val kind: MemoryKind,
    val key: String,
    val value: String,
    val confidence: Double = 1.0,
    val source: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
    val expiresAt: Instant? = null,
    val isPrivate: Boolean = false,
    /**
     * Positive visibility allow-list. HARD DENY policy and isPrivate=true are stronger.
     * Existing memories default to all model audiences for backward compatibility.
     */
    val visibleTo: Set<MemoryAudience> = MemoryAudience.entries.toSet()
) {
    fun isExpired(now: Instant = Instant.now()): Boolean = expiresAt?.isBefore(now) == true
}
