package com.veltrix.ultron.core

enum class MemoryKind { PREFERENCE, PERSON, PROJECT, DECISION, APP, SKILL, CONVERSATION }

data class MemoryItem(
    val id: String,
    val kind: MemoryKind,
    val summary: String,
    val confidence: Float,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val expiresAtEpochMs: Long? = null,
    val isPrivate: Boolean = false
)

interface MemoryStore {
    suspend fun remember(item: MemoryItem)
    suspend fun search(query: String, limit: Int = 20): List<MemoryItem>
    suspend fun forget(id: String)
}
