package com.veltrix.ultron.car

import android.content.Context
import com.veltrix.ultron.devices.DeviceObservation
import com.veltrix.ultron.devices.UniversalAction
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class CarLearningStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val pendingFailures = ConcurrentHashMap<String, PendingFailure>()
    private val loopTrackers = ConcurrentHashMap<String, CarFailureLoopTracker>()

    data class FailureNote(
        val strategy: String,
        val kind: CarFailureKind,
        val repeatCount: Int,
        val avoidExactRepeat: Boolean,
        val recoveryHint: String?
    )

    @Synchronized
    fun observeScreen(observation: DeviceObservation) {
        if (!CarSessionRuntime.allowsUiMutation()) return
        val packageName = observation.foregroundApp?.take(220) ?: return
        val fingerprint = observation.screenFingerprint?.take(80) ?: return
        val nodes = observation.semanticNodes
            .asSequence()
            .filter { it.viewId != null || it.clickable || it.editable || it.scrollable }
            .take(80)
            .map { node ->
                JSONObject()
                    .put("v", node.viewId?.take(180) ?: JSONObject.NULL)
                    .put("c", node.className?.take(120) ?: JSONObject.NULL)
                    .put("b", JSONArray(listOf(node.left, node.top, node.right, node.bottom)))
                    .put("k", node.clickable)
                    .put("e", node.editable)
                    .put("s", node.scrollable)
            }
            .toList()

        val screens = loadArray(KEY_SCREENS)
        val retained = mutableListOf<JSONObject>()
        for (i in 0 until screens.length()) {
            val item = screens.optJSONObject(i) ?: continue
            if (item.optString("p") == packageName && item.optString("f") == fingerprint) continue
            retained += item
        }
        retained += JSONObject()
            .put("p", packageName)
            .put("f", fingerprint)
            .put("t", System.currentTimeMillis())
            .put("n", JSONArray(nodes))
        saveArray(KEY_SCREENS, JSONArray(retained.takeLast(MAX_SCREENS)))
    }

    @Synchronized
    fun learningHints(objective: String, observation: DeviceObservation, now: Long = System.currentTimeMillis()): List<String> {
        if (!CarSessionRuntime.allowsUiMutation()) return emptyList()
        val objectiveHash = hash(normalizeObjective(objective))
        val packageName = observation.foregroundApp
        val fingerprint = observation.screenFingerprint
        val matching = loadSkills().filter { record ->
            (record.objectiveHash == objectiveHash || record.packageName == packageName) &&
                (record.screenFingerprint == null || fingerprint == null || record.screenFingerprint == fingerprint)
        }
        return CarSkillRanker.rank(matching, now, 5).map { record ->
            buildString {
                append("learned_skill(strategy=").append(record.strategy)
                append(",success=").append(record.successes)
                append(",failure=").append(record.failures)
                append(",confidence=").append("%.2f".format(record.confidence))
                record.recoveryStrategy?.let { append(",recovery=").append(it) }
                append("). Hint only; re-check the live screen before acting.")
            }
        }
    }

    @Synchronized
    fun recordFailure(
        sessionId: String,
        objective: String,
        action: UniversalAction?,
        observation: DeviceObservation,
        reason: String
    ): FailureNote {
        val strategy = strategySignature(action)
        val kind = CarFailureClassifier.classify(reason)
        val tracker = loopTrackers.getOrPut(sessionId) { CarFailureLoopTracker() }
        val signature = failureSignature(strategy, observation)
        val repeat = tracker.note(signature)
        val objectiveHash = hash(normalizeObjective(objective))
        val key = recordKey(objectiveHash, observation.foregroundApp, observation.screenFingerprint, strategy)
        val records = loadSkills().toMutableList()
        val index = records.indexOfFirst { it.key == key }
        val previous = records.getOrNull(index)
        val failures = (previous?.failures ?: 0) + 1
        val successes = previous?.successes ?: 0
        val updated = CarSkillRecord(
            key = key,
            objectiveHash = objectiveHash,
            packageName = observation.foregroundApp,
            screenFingerprint = observation.screenFingerprint,
            strategy = strategy,
            successes = successes,
            failures = failures,
            confidence = confidence(successes, failures),
            lastSuccessAt = previous?.lastSuccessAt,
            lastFailureAt = System.currentTimeMillis(),
            recoveryStrategy = previous?.recoveryStrategy,
            failureKind = kind
        )
        if (index >= 0) records[index] = updated else records += updated
        saveSkills(records)
        pendingFailures[sessionId] = PendingFailure(key, strategy)
        return FailureNote(strategy, kind, repeat, tracker.shouldAvoid(signature), updated.recoveryStrategy)
    }

    @Synchronized
    fun recordSuccess(
        sessionId: String,
        objective: String,
        action: UniversalAction,
        before: DeviceObservation,
        after: DeviceObservation
    ) {
        if (!CarSessionRuntime.allowsUiMutation()) return
        val strategy = strategySignature(action)
        val objectiveHash = hash(normalizeObjective(objective))
        val key = recordKey(objectiveHash, before.foregroundApp, before.screenFingerprint, strategy)
        val records = loadSkills().toMutableList()
        val index = records.indexOfFirst { it.key == key }
        val previous = records.getOrNull(index)
        val successes = (previous?.successes ?: 0) + 1
        val failures = previous?.failures ?: 0
        val updated = CarSkillRecord(
            key = key,
            objectiveHash = objectiveHash,
            packageName = before.foregroundApp,
            screenFingerprint = before.screenFingerprint,
            strategy = strategy,
            successes = successes,
            failures = failures,
            confidence = confidence(successes, failures),
            lastSuccessAt = System.currentTimeMillis(),
            lastFailureAt = previous?.lastFailureAt,
            recoveryStrategy = previous?.recoveryStrategy,
            failureKind = previous?.failureKind
        )
        if (index >= 0) records[index] = updated else records += updated

        pendingFailures.remove(sessionId)?.let { failed ->
            val failedIndex = records.indexOfFirst { it.key == failed.key }
            val failedRecord = records.getOrNull(failedIndex)
            if (failedIndex >= 0 && failedRecord != null && failed.strategy != strategy) {
                records[failedIndex] = failedRecord.copy(
                    recoveryStrategy = strategy,
                    confidence = max(0.05, failedRecord.confidence - 0.03)
                )
            }
        }
        saveSkills(records)
        observeScreen(after)
    }

    fun finishSession(sessionId: String) {
        pendingFailures.remove(sessionId)
        loopTrackers.remove(sessionId)?.clear()
    }

    private fun failureSignature(strategy: String, observation: DeviceObservation): String =
        listOf(strategy, observation.foregroundApp.orEmpty(), observation.screenFingerprint.orEmpty()).joinToString("|")

    private fun strategySignature(action: UniversalAction?): String {
        if (action == null) return "unknown"
        val gesture = action.metadata["gesture"]?.lowercase()?.take(24)
        val mode = when {
            gesture != null -> "gesture:$gesture"
            action.x != null || action.y != null -> "coordinate"
            else -> "semantic"
        }
        val targetKind = action.target
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_.]{2,120}")) }
            ?.let { "target:$it" }
            ?: "target:dynamic"
        return "${action.type.name.lowercase()}:$mode:$targetKind".take(220)
    }

    private fun normalizeObjective(value: String): String =
        value.lowercase().trim().replace(Regex("\\s+"), " ").take(512)

    private fun recordKey(objectiveHash: String, packageName: String?, fingerprint: String?, strategy: String): String =
        hash(listOf(objectiveHash, packageName.orEmpty(), fingerprint.orEmpty(), strategy).joinToString("|"))

    private fun confidence(successes: Int, failures: Int): Double =
        ((successes + 1.0) / (successes + failures + 2.0)).coerceIn(0.05, 0.99)

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)

    private fun loadSkills(): List<CarSkillRecord> {
        val array = loadArray(KEY_SKILLS)
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                runCatching {
                    add(
                        CarSkillRecord(
                            key = o.getString("k"),
                            objectiveHash = o.getString("o"),
                            packageName = o.optString("p").takeIf(String::isNotBlank),
                            screenFingerprint = o.optString("f").takeIf(String::isNotBlank),
                            strategy = o.getString("s"),
                            successes = o.optInt("ok", 0).coerceAtLeast(0),
                            failures = o.optInt("bad", 0).coerceAtLeast(0),
                            confidence = o.optDouble("c", 0.5).coerceIn(0.0, 1.0),
                            lastSuccessAt = o.optLong("ls", 0L).takeIf { it > 0L },
                            lastFailureAt = o.optLong("lf", 0L).takeIf { it > 0L },
                            recoveryStrategy = o.optString("r").takeIf(String::isNotBlank),
                            failureKind = o.optString("fk").takeIf(String::isNotBlank)?.let(CarFailureKind::valueOf)
                        )
                    )
                }
            }
        }
    }

    private fun saveSkills(records: List<CarSkillRecord>) {
        val deduped = records
            .sortedByDescending { max(it.lastSuccessAt ?: 0L, it.lastFailureAt ?: 0L) }
            .take(MAX_SKILLS)
        val array = JSONArray()
        deduped.forEach { record ->
            array.put(
                JSONObject()
                    .put("k", record.key)
                    .put("o", record.objectiveHash)
                    .put("p", record.packageName ?: "")
                    .put("f", record.screenFingerprint ?: "")
                    .put("s", record.strategy)
                    .put("ok", record.successes)
                    .put("bad", record.failures)
                    .put("c", record.confidence)
                    .put("ls", record.lastSuccessAt ?: 0L)
                    .put("lf", record.lastFailureAt ?: 0L)
                    .put("r", record.recoveryStrategy ?: "")
                    .put("fk", record.failureKind?.name ?: "")
            )
        }
        saveArray(KEY_SKILLS, array)
    }

    private fun loadArray(key: String): JSONArray =
        runCatching { JSONArray(prefs.getString(key, "[]") ?: "[]") }.getOrDefault(JSONArray())

    private fun saveArray(key: String, value: JSONArray) {
        prefs.edit().putString(key, value.toString()).apply()
    }

    private data class PendingFailure(val key: String, val strategy: String)

    companion object {
        private const val PREFS = "veltrix_car_learning_v1"
        private const val KEY_SKILLS = "skills"
        private const val KEY_SCREENS = "screens"
        private const val MAX_SKILLS = 240
        private const val MAX_SCREENS = 96
    }
}
