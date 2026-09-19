package com.veltrix.ultron.platform

import android.content.Context
import com.veltrix.ultron.core.ScreenObservation
import com.veltrix.ultron.devices.DeviceObservation
import com.veltrix.ultron.devices.UniversalAction
import com.veltrix.ultron.devices.UniversalActionType
import com.veltrix.ultron.executor.AccessibilityAction
import com.veltrix.ultron.executor.AccessibilityActionType
import java.util.ArrayDeque
import java.util.UUID

data class AndroidUndoCheckpoint(
    val id: String,
    val missionId: String,
    val description: String,
    val expectedCurrentPackage: String,
    val restorePackage: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

internal interface UndoJournalPersistence {
    fun load(): List<AndroidUndoCheckpoint>
    fun save(checkpoints: List<AndroidUndoCheckpoint>)
    fun clear()
}

/**
 * Conservative rollback journal with optional encrypted Android persistence.
 *
 * Only actions for which ULTRON can prove an app-level compensation are retained.
 * Any later verified non-reversible action invalidates the journal, so Undo never
 * pretends to reverse arbitrary clicks, text entry, file writes or navigation.
 * Persistence is initialized by UltronApplication and remains fail-closed: corrupt,
 * invalid or unwritable durable state is discarded instead of being trusted.
 */
object AndroidUndoJournal {
    private const val MAX_CHECKPOINTS = 16
    private const val MAX_MISSION_ID = 128
    private const val MAX_DESCRIPTION = 256
    private val checkpoints = ArrayDeque<AndroidUndoCheckpoint>()
    private val suppressionDepth = ThreadLocal.withInitial { 0 }
    private val packagePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")

    @Volatile
    private var persistence: UndoJournalPersistence? = null

    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        persistence = AndroidEncryptedUndoJournalStore(context.applicationContext)
        initialized = true
        restoreFromPersistence()
    }

    @Synchronized
    fun recordVerified(
        missionId: String,
        description: String,
        action: AccessibilityAction,
        before: ScreenObservation,
        after: ScreenObservation
    ) {
        if (isRecordingSuppressed()) return
        val checkpoint = checkpointFor(
            missionId = missionId,
            description = description,
            reversibleType = action.type == AccessibilityActionType.OPEN_APP || action.type == AccessibilityActionType.GLOBAL_HOME,
            beforePackage = before.packageName,
            afterPackage = after.packageName
        )
        replaceOrInvalidate(checkpoint)
    }

    @Synchronized
    fun recordVerified(
        missionId: String,
        description: String,
        action: UniversalAction,
        before: DeviceObservation,
        after: DeviceObservation
    ) {
        if (isRecordingSuppressed()) return
        val checkpoint = checkpointFor(
            missionId = missionId,
            description = description,
            reversibleType = action.type == UniversalActionType.OPEN_APP || action.type == UniversalActionType.HOME,
            beforePackage = before.foregroundApp,
            afterPackage = after.foregroundApp
        )
        replaceOrInvalidate(checkpoint)
    }

    @Synchronized
    fun latest(): AndroidUndoCheckpoint? = checkpoints.lastOrNull()

    @Synchronized
    fun consume(checkpointId: String): Boolean {
        val latest = checkpoints.lastOrNull() ?: return false
        if (latest.id != checkpointId) return false
        checkpoints.removeLast()
        persistCurrentState()
        return true
    }

    @Synchronized
    fun clear() {
        checkpoints.clear()
        val store = persistence ?: return
        runCatching { store.clear() }.onFailure { failClosed(store) }
    }

    fun <T> withoutRecording(block: () -> T): T {
        suppressionDepth.set(suppressionDepth.get() + 1)
        return try {
            block()
        } finally {
            suppressionDepth.set((suppressionDepth.get() - 1).coerceAtLeast(0))
        }
    }

    internal fun checkpointFor(
        missionId: String,
        description: String,
        reversibleType: Boolean,
        beforePackage: String?,
        afterPackage: String?
    ): AndroidUndoCheckpoint? {
        val before = beforePackage?.trim().orEmpty()
        val after = afterPackage?.trim().orEmpty()
        if (!reversibleType || !packagePattern.matches(before) || !packagePattern.matches(after) || before == after) {
            return null
        }
        return AndroidUndoCheckpoint(
            id = UUID.randomUUID().toString(),
            missionId = missionId.trim().take(MAX_MISSION_ID),
            description = sanitizeDescription(description),
            expectedCurrentPackage = after,
            restorePackage = before
        )
    }

    @Synchronized
    internal fun installPersistenceForTests(store: UndoJournalPersistence?) {
        checkpoints.clear()
        persistence = store
        initialized = store != null
        if (store != null) restoreFromPersistence()
    }

    @Synchronized
    internal fun simulateProcessRestartForTests() {
        checkpoints.clear()
        restoreFromPersistence()
    }

    @Synchronized
    internal fun resetForTests() {
        checkpoints.clear()
        persistence = null
        initialized = false
        suppressionDepth.remove()
    }

    private fun replaceOrInvalidate(checkpoint: AndroidUndoCheckpoint?) {
        if (checkpoint == null) {
            checkpoints.clear()
            persistCurrentState()
            return
        }
        checkpoints.addLast(checkpoint)
        while (checkpoints.size > MAX_CHECKPOINTS) checkpoints.removeFirst()
        persistCurrentState()
    }

    private fun persistCurrentState() {
        val store = persistence ?: return
        runCatching { store.save(checkpoints.toList()) }
            .onFailure { failClosed(store) }
    }

    private fun restoreFromPersistence() {
        val store = persistence ?: return
        val loaded = runCatching { store.load() }.getOrElse {
            failClosed(store)
            return
        }
        val normalized = loaded.mapNotNull(::normalizeLoadedCheckpoint)
        if (normalized.size != loaded.size) {
            failClosed(store)
            return
        }
        checkpoints.clear()
        normalized.takeLast(MAX_CHECKPOINTS).forEach(checkpoints::addLast)
        if (normalized.size > MAX_CHECKPOINTS) persistCurrentState()
    }

    private fun normalizeLoadedCheckpoint(checkpoint: AndroidUndoCheckpoint): AndroidUndoCheckpoint? {
        val id = checkpoint.id.trim()
        val missionId = checkpoint.missionId.trim()
        val expected = checkpoint.expectedCurrentPackage.trim()
        val restore = checkpoint.restorePackage.trim()
        if (id.isEmpty() || id.length > 64) return null
        if (missionId.isEmpty() || missionId.length > MAX_MISSION_ID) return null
        if (!packagePattern.matches(expected) || !packagePattern.matches(restore) || expected == restore) return null
        if (checkpoint.createdAtEpochMs <= 0L) return null
        return checkpoint.copy(
            id = id,
            missionId = missionId,
            description = sanitizeDescription(checkpoint.description),
            expectedCurrentPackage = expected,
            restorePackage = restore
        )
    }

    private fun failClosed(store: UndoJournalPersistence) {
        checkpoints.clear()
        runCatching { store.clear() }
    }

    private fun sanitizeDescription(value: String): String = value
        .replace(Regex("[\\u0000-\\u001F\\u007F]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_DESCRIPTION)
        .ifBlank { "verified reversible action" }

    private fun isRecordingSuppressed(): Boolean = suppressionDepth.get() > 0
}