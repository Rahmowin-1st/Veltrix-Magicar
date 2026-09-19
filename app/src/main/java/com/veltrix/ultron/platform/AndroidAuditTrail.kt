package com.veltrix.ultron.platform

import android.content.Context
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

enum class AndroidAuditEventType {
    MISSION_SUBMITTED,
    PLAN_ACCEPTED,
    PERMISSION_REQUESTED,
    PERMISSION_ALLOW_ONCE,
    PERMISSION_ALWAYS_ALLOW,
    PERMISSION_DENIED,
    ACTION_DISPATCH_REQUESTED,
    ACTION_DISPATCH_ACCEPTED,
    ACTION_REJECTED,
    VERIFICATION_PASSED,
    VERIFICATION_FAILED,
    MISSION_PAUSED,
    MISSION_RESUMED,
    MISSION_CANCELLED,
    USER_TAKE_OVER,
    UNDO_CHECKPOINT_RECORDED,
    UNDO_VERIFIED,
    MISSION_FAILED
}

data class AndroidAuditEventInput(
    val missionId: String,
    val principalKind: String,
    val principalId: String,
    val deviceId: String,
    val type: AndroidAuditEventType,
    val capability: String? = null,
    val actionType: String? = null,
    val targetScope: String? = null,
    val resultCode: String
)

data class AndroidAuditEvent(
    val id: String,
    val sequence: Long,
    val missionId: String,
    val principalKind: String,
    val principalRef: String,
    val deviceId: String,
    val type: AndroidAuditEventType,
    val capability: String?,
    val actionType: String?,
    val targetRef: String?,
    val resultCode: String,
    val createdAtEpochMs: Long
)

internal interface AuditTrailPersistence {
    fun load(): List<AndroidAuditEvent>
    fun save(events: List<AndroidAuditEvent>)
    fun clear()
}

/**
 * Bounded security/operational audit history.
 *
 * The trail stores identifiers and typed facts only. It deliberately never accepts
 * raw objectives, screen text/images, transcripts, credentials, tokens, or arbitrary
 * evidence payloads. Non-package targets are persisted only as one-way references.
 */
object AndroidAuditTrail {
    private const val MAX_EVENTS = 256
    private const val MAX_ID = 128
    private const val MAX_SYMBOL = 80
    private val safeIdPattern = Regex("^[A-Za-z0-9_.:-]{1,$MAX_ID}$")
    private val packagePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private val events = ArrayDeque<AndroidAuditEvent>()

    @Volatile
    private var persistence: AuditTrailPersistence? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var degraded = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        persistence = AndroidEncryptedAuditTrailStore(context.applicationContext)
        initialized = true
        restoreFromPersistence()
    }

    /**
     * Returns false when durable audit persistence is initialized but cannot safely
     * accept the event. Callers may fail closed for forward execution, while Stop /
     * Cancel paths should still remain authoritative and treat audit as best effort.
     */
    @Synchronized
    fun record(input: AndroidAuditEventInput): Boolean {
        val normalized = normalizeInput(input) ?: return false
        val nextSequence = (events.lastOrNull()?.sequence ?: 0L) + 1L
        val event = AndroidAuditEvent(
            id = UUID.randomUUID().toString(),
            sequence = nextSequence,
            missionId = safeReference(normalized.missionId, keepSafe = true),
            principalKind = safeSymbol(normalized.principalKind),
            principalRef = principalReference(normalized.principalId),
            deviceId = safeReference(normalized.deviceId, keepSafe = true),
            type = normalized.type,
            capability = normalized.capability?.let(::safeSymbol),
            actionType = normalized.actionType?.let(::safeSymbol),
            targetRef = normalized.targetScope?.let(::targetReference),
            resultCode = safeResultCode(normalized.resultCode),
            createdAtEpochMs = System.currentTimeMillis()
        )
        events.addLast(event)
        while (events.size > MAX_EVENTS) events.removeFirst()
        return persistCurrentState()
    }

    @Synchronized
    fun snapshot(limit: Int = MAX_EVENTS): List<AndroidAuditEvent> =
        events.toList().takeLast(limit.coerceIn(0, MAX_EVENTS))

    fun isDegraded(): Boolean = degraded

    @Synchronized
    internal fun installPersistenceForTests(store: AuditTrailPersistence?) {
        events.clear()
        persistence = store
        initialized = store != null
        degraded = false
        if (store != null) restoreFromPersistence()
    }

    @Synchronized
    internal fun simulateProcessRestartForTests() {
        events.clear()
        restoreFromPersistence()
    }

    @Synchronized
    internal fun resetForTests() {
        events.clear()
        persistence = null
        initialized = false
        degraded = false
    }

    private fun normalizeInput(input: AndroidAuditEventInput): AndroidAuditEventInput? {
        if (input.missionId.isBlank() || input.principalKind.isBlank() || input.principalId.isBlank()) return null
        if (input.deviceId.isBlank() || input.resultCode.isBlank()) return null
        return input.copy(
            missionId = input.missionId.trim(),
            principalKind = input.principalKind.trim(),
            principalId = input.principalId.trim(),
            deviceId = input.deviceId.trim(),
            capability = input.capability?.trim()?.takeIf(String::isNotEmpty),
            actionType = input.actionType?.trim()?.takeIf(String::isNotEmpty),
            targetScope = input.targetScope?.trim()?.takeIf(String::isNotEmpty),
            resultCode = input.resultCode.trim()
        )
    }

    private fun persistCurrentState(): Boolean {
        val store = persistence ?: return true
        return runCatching {
            store.save(events.toList())
            degraded = false
            true
        }.getOrElse {
            failClosed(store)
            false
        }
    }

    private fun restoreFromPersistence() {
        val store = persistence ?: return
        val loaded = runCatching { store.load() }.getOrElse {
            failClosed(store)
            return
        }
        val normalized = loaded.mapNotNull(::normalizeLoadedEvent)
        val orderValid = normalized.zipWithNext().all { (a, b) -> b.sequence > a.sequence }
        if (normalized.size != loaded.size || !orderValid) {
            failClosed(store)
            return
        }
        events.clear()
        normalized.takeLast(MAX_EVENTS).forEach(events::addLast)
        if (normalized.size > MAX_EVENTS) {
            if (!persistCurrentState()) return
        }
        degraded = false
    }

    private fun normalizeLoadedEvent(event: AndroidAuditEvent): AndroidAuditEvent? {
        if (event.id.isBlank() || event.id.length > 64 || event.sequence <= 0L || event.createdAtEpochMs <= 0L) return null
        if (event.missionId.isBlank() || event.missionId.length > MAX_ID) return null
        if (event.principalKind.isBlank() || event.principalKind.length > MAX_SYMBOL) return null
        if (event.principalRef.isBlank() || event.principalRef.length > MAX_ID) return null
        if (event.deviceId.isBlank() || event.deviceId.length > MAX_ID) return null
        if (event.resultCode.isBlank() || event.resultCode.length > MAX_SYMBOL) return null
        if ((event.capability?.length ?: 0) > MAX_SYMBOL) return null
        if ((event.actionType?.length ?: 0) > MAX_SYMBOL) return null
        if ((event.targetRef?.length ?: 0) > MAX_ID) return null
        return event
    }

    private fun failClosed(store: AuditTrailPersistence) {
        events.clear()
        degraded = true
        runCatching { store.clear() }
    }

    private fun safeSymbol(value: String): String = value
        .replace(Regex("[^A-Za-z0-9_.:-]+"), "_")
        .trim('_')
        .take(MAX_SYMBOL)
        .ifBlank { "unknown" }

    private fun safeResultCode(value: String): String = safeSymbol(value.uppercase(Locale.ROOT))

    private fun principalReference(value: String): String =
        if (value.equals("owner", ignoreCase = true)) "owner" else "sha256:${digest(value).take(24)}"

    private fun targetReference(value: String): String {
        val trimmed = value.trim()
        return if (packagePattern.matches(trimmed)) trimmed.take(MAX_ID) else "sha256:${digest(trimmed).take(24)}"
    }

    private fun safeReference(value: String, keepSafe: Boolean): String {
        val trimmed = value.trim()
        return if (keepSafe && safeIdPattern.matches(trimmed)) trimmed else "sha256:${digest(trimmed).take(24)}"
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
