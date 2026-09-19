package com.veltrix.ultron.memory

import java.time.Instant

interface PersonalMemoryRepository {
    fun rememberIf(record: MemoryRecord, canPersist: (MemoryRecord) -> Boolean): MemoryRecord?
    fun recall(kind: MemoryKind? = null, query: String? = null): List<MemoryRecord>
    fun forget(id: String): Boolean
    fun clearPrivate(): Int
}

class PersonalMemoryStore : PersonalMemoryRepository {
    private val records = linkedMapOf<String, MemoryRecord>()

    @Synchronized
    fun remember(record: MemoryRecord): MemoryRecord {
        val merged = PersonalMemoryMerge.merge(
            existing = records.values.firstOrNull { it.kind == record.kind && it.key == record.key },
            incoming = record,
            now = Instant.now()
        )
        records.remove(merged.replacedId)
        records[merged.record.id] = merged.record
        return merged.record
    }

    @Synchronized
    override fun rememberIf(
        record: MemoryRecord,
        canPersist: (MemoryRecord) -> Boolean
    ): MemoryRecord? {
        val merged = PersonalMemoryMerge.merge(
            existing = records.values.firstOrNull { it.kind == record.kind && it.key == record.key },
            incoming = record,
            now = Instant.now()
        )
        if (!canPersist(merged.record)) return null
        records.remove(merged.replacedId)
        records[merged.record.id] = merged.record
        return merged.record
    }

    @Synchronized
    override fun recall(kind: MemoryKind?, query: String?): List<MemoryRecord> {
        val now = Instant.now()
        return records.values
            .asSequence()
            .filterNot { it.isExpired(now) }
            .filter { kind == null || it.kind == kind }
            .filter {
                query.isNullOrBlank() ||
                    it.key.contains(query, ignoreCase = true) ||
                    it.value.contains(query, ignoreCase = true)
            }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    @Synchronized
    override fun forget(id: String): Boolean = records.remove(id) != null

    @Synchronized
    override fun clearPrivate(): Int {
        val ids = records.values.filter { it.isPrivate }.map { it.id }
        ids.forEach(records::remove)
        return ids.size
    }
}

internal data class PersonalMemoryMergeResult(
    val record: MemoryRecord,
    val replacedId: String?
)

/** Shared duplicate semantics for volatile and encrypted-durable memory stores. */
internal object PersonalMemoryMerge {
    fun merge(existing: MemoryRecord?, incoming: MemoryRecord, now: Instant): PersonalMemoryMergeResult {
        if (existing == null) return PersonalMemoryMergeResult(incoming, null)
        return PersonalMemoryMergeResult(
            record = incoming.copy(
                id = existing.id,
                createdAt = existing.createdAt,
                updatedAt = now,
                // Updates may tighten privacy but must never silently declassify memory.
                isPrivate = existing.isPrivate || incoming.isPrivate,
                visibleTo = existing.visibleTo.intersect(incoming.visibleTo)
            ),
            replacedId = existing.id
        )
    }
}
