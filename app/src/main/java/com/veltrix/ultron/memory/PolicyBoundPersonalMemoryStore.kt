package com.veltrix.ultron.memory

enum class MemoryWriteState {
    STORED,
    DENIED
}

data class MemoryWriteResult(
    val state: MemoryWriteState,
    val record: MemoryRecord? = null,
    val message: String
)

/**
 * The only store shape intended for AI/search/report integration.
 * User-local recall remains available, while every model-facing recall is filtered
 * through PersonalMemoryPrivacyFirewall before content leaves the memory boundary.
 */
class PolicyBoundPersonalMemoryStore(
    private val delegate: PersonalMemoryRepository,
    private val privacy: PersonalMemoryPrivacyFirewall
) {
    @Synchronized
    fun remember(record: MemoryRecord): MemoryWriteResult {
        val safeRecord = PersonalMemoryPersistencePolicy.sanitize(record)
            ?: return MemoryWriteResult(
                state = MemoryWriteState.DENIED,
                message = "Memory content is invalid after safety sanitization"
            )
        val stored = delegate.rememberIf(safeRecord) { candidate -> privacy.canStore(candidate) }
            ?: return MemoryWriteResult(
                state = MemoryWriteState.DENIED,
                message = "Owner privacy policy or durable memory integrity blocks persistence"
            )
        return MemoryWriteResult(
            state = MemoryWriteState.STORED,
            record = stored,
            message = "Memory stored"
        )
    }

    /** User-visible local recall is not a model disclosure path. */
    @Synchronized
    fun recallForUser(kind: MemoryKind? = null, query: String? = null): List<MemoryRecord> =
        delegate.recall(kind, query)

    /**
     * Model/search/report recall. USER_ONLY and HARD DENY records are removed before
     * the caller receives any key/value content.
     */
    @Synchronized
    fun recallForModel(
        audience: MemoryAudience,
        kind: MemoryKind? = null,
        query: String? = null
    ): List<MemoryRecord> = delegate.recall(kind, query)
        .filter { record -> privacy.canObserve(record, audience) }

    @Synchronized
    fun forget(id: String): Boolean = delegate.forget(id)

    @Synchronized
    fun clearPrivate(): Int = delegate.clearPrivate()
}
