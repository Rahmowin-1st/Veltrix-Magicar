package com.veltrix.ultron.car

import kotlin.math.max
import kotlin.math.min

enum class CarFailureKind {
    VERIFICATION_FAILED,
    ELEMENT_NOT_FOUND,
    UI_CHANGED,
    NETWORK,
    PERMISSION,
    EXECUTOR_DISCONNECTED,
    TIMEOUT,
    PROVIDER,
    LOOP,
    UNKNOWN
}

data class CarSkillRecord(
    val key: String,
    val objectiveHash: String,
    val packageName: String?,
    val screenFingerprint: String?,
    val strategy: String,
    val successes: Int,
    val failures: Int,
    val confidence: Double,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val recoveryStrategy: String?,
    val failureKind: CarFailureKind?
)

object CarSkillRanker {
    fun rank(records: List<CarSkillRecord>, nowEpochMs: Long, limit: Int = 5): List<CarSkillRecord> =
        records
            .filter { it.successes > 0 || it.recoveryStrategy != null }
            .sortedByDescending { score(it, nowEpochMs) }
            .take(limit.coerceIn(1, 10))

    fun score(record: CarSkillRecord, nowEpochMs: Long): Double {
        val successRate = (record.successes + 1.0) / (record.successes + record.failures + 2.0)
        val latest = max(record.lastSuccessAt ?: 0L, record.lastFailureAt ?: 0L)
        val ageDays = if (latest <= 0L) 365.0 else max(0.0, (nowEpochMs - latest) / 86_400_000.0)
        val freshness = 1.0 / (1.0 + ageDays / 30.0)
        val failurePenalty = min(record.failures, 5) / 5.0
        return 0.52 * successRate +
            0.30 * record.confidence.coerceIn(0.0, 1.0) +
            0.18 * freshness -
            0.10 * failurePenalty
    }
}

class CarFailureLoopTracker(private val repeatLimit: Int = 2) {
    private val counts = linkedMapOf<String, Int>()

    @Synchronized
    fun note(signature: String): Int {
        val next = (counts[signature] ?: 0) + 1
        counts[signature] = next
        return next
    }

    @Synchronized
    fun shouldAvoid(signature: String): Boolean = (counts[signature] ?: 0) >= repeatLimit

    @Synchronized
    fun clear() = counts.clear()
}

object CarFailureClassifier {
    fun classify(message: String): CarFailureKind {
        val lower = message.lowercase()
        return when {
            "verification" in lower || "post-condition" in lower -> CarFailureKind.VERIFICATION_FAILED
            "not found" in lower || "no visible" in lower || "no matching" in lower -> CarFailureKind.ELEMENT_NOT_FOUND
            "changed" in lower || "stale" in lower -> CarFailureKind.UI_CHANGED
            "network" in lower || "http" in lower || "offline" in lower -> CarFailureKind.NETWORK
            "permission" in lower || "not granted" in lower || "denied" in lower -> CarFailureKind.PERMISSION
            "disconnected" in lower || "executor" in lower -> CarFailureKind.EXECUTOR_DISCONNECTED
            "timeout" in lower || "timed out" in lower -> CarFailureKind.TIMEOUT
            "provider" in lower || "model" in lower || "planner" in lower -> CarFailureKind.PROVIDER
            "loop" in lower || "repeat" in lower -> CarFailureKind.LOOP
            else -> CarFailureKind.UNKNOWN
        }
    }
}
