package com.veltrix.ultron.chat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.veltrix.ultron.planner.AndroidOwnerPlannerPermissionStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class ConversationMemorySnapshot(
    val messages: List<ChatMessage> = emptyList(),
    val followUp: FollowUpContext = FollowUpContext()
)

/**
 * Small encrypted local conversation store with an owner privacy boundary.
 *
 * Secrets/codes are redacted before persistence. Owner HARD DENY is evaluated before
 * new conversation state is stored and again before memory becomes planner/model context.
 * Observe-only deny keeps local history user-visible while making it unavailable to AI.
 */
class AndroidConversationMemoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val privacyFirewall = ConversationMemoryPrivacyFirewall(
        ownerPermissions = AndroidOwnerPlannerPermissionStore(appContext),
        ownerPrincipalId = LOCAL_OWNER_PRINCIPAL_ID,
        deviceId = LOCAL_ANDROID_DEVICE_ID
    )

    @Synchronized
    fun append(role: MessageRole, text: String, missionId: String? = null): ChatMessage? {
        val safe = ConversationMemoryPolicy.sanitize(text)
        if (safe.isBlank()) return null
        val message = ChatMessage(role = role, text = safe, missionId = missionId)
        if (!privacyFirewall.canStoreMessage(message)) return null

        val current = loadSnapshot()
        persist(
            current.copy(
                messages = ConversationMemoryPolicy.bounded(current.messages + message)
            )
        )
        return message
    }

    /** User-visible local history is not removed merely because AI observation is denied. */
    @Synchronized
    fun recent(limit: Int = 30): List<ChatMessage> =
        loadSnapshot().messages.takeLast(limit.coerceAtLeast(0))

    @Synchronized
    fun updateFollowUp(transform: (FollowUpContext) -> FollowUpContext): FollowUpContext {
        val current = loadSnapshot()
        if (!privacyFirewall.canStoreFollowUp()) return current.followUp

        val transformed = transform(current.followUp)
        val updated = transformed.copy(
            activeMissionId = transformed.activeMissionId?.let(ConversationMemoryPolicy::sanitize),
            activeArtifactIds = transformed.activeArtifactIds.map(ConversationMemoryPolicy::sanitize).filter(String::isNotBlank).take(20),
            activeAppPackage = transformed.activeAppPackage?.let(ConversationMemoryPolicy::sanitize),
            activePersonRef = transformed.activePersonRef?.let(ConversationMemoryPolicy::sanitize),
            lastIntent = transformed.lastIntent?.let(ConversationMemoryPolicy::sanitize)
        )
        persist(current.copy(followUp = updated))
        return updated
    }

    @Synchronized
    fun followUpContext(): FollowUpContext = loadSnapshot().followUp

    @Synchronized
    fun plannerHints(
        limit: Int = ConversationMemoryPolicy.MAX_HINTS,
        excludeLastMessages: Int = 0
    ): List<String> {
        val snapshot = loadSnapshot()
        val excluded = excludeLastMessages.coerceAtLeast(0)
        val candidateMessages = if (excluded == 0) {
            snapshot.messages
        } else {
            snapshot.messages.dropLast(excluded.coerceAtMost(snapshot.messages.size))
        }
        val visible = privacyFirewall.filterForPlanner(candidateMessages, snapshot.followUp)
        return ConversationMemoryPolicy.plannerHints(visible.messages, visible.followUp, limit)
    }

    @Synchronized
    fun count(): Int = loadSnapshot().messages.size

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_IV).remove(KEY_CIPHER).apply()
    }

    private fun persist(snapshot: ConversationMemorySnapshot) {
        val plaintext = encode(snapshot).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext)
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    private fun loadSnapshot(): ConversationMemorySnapshot {
        val iv = prefs.getString(KEY_IV, null) ?: return ConversationMemorySnapshot()
        val encrypted = prefs.getString(KEY_CIPHER, null) ?: return ConversationMemorySnapshot()
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            val plaintext = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
            decode(JSONObject(plaintext))
        }.getOrElse {
            clear()
            ConversationMemorySnapshot()
        }
    }

    private fun encode(snapshot: ConversationMemorySnapshot): JSONObject {
        val messages = JSONArray()
        snapshot.messages.forEach { message ->
            messages.put(
                JSONObject()
                    .put("id", message.id)
                    .put("role", message.role.name)
                    .put("text", message.text)
                    .put("created_at", message.createdAt.toEpochMilli())
                    .putNullable("mission_id", message.missionId)
            )
        }
        val followUp = JSONObject()
            .putNullable("active_mission_id", snapshot.followUp.activeMissionId)
            .put("active_artifact_ids", JSONArray(snapshot.followUp.activeArtifactIds.take(20)))
            .putNullable("active_app_package", snapshot.followUp.activeAppPackage)
            .putNullable("active_person_ref", snapshot.followUp.activePersonRef)
            .putNullable("last_intent", snapshot.followUp.lastIntent)

        return JSONObject()
            .put("version", 1)
            .put("messages", messages)
            .put("follow_up", followUp)
    }

    private fun decode(root: JSONObject): ConversationMemorySnapshot {
        val messagesJson = root.optJSONArray("messages") ?: JSONArray()
        val messages = buildList {
            for (index in 0 until messagesJson.length()) {
                val item = messagesJson.optJSONObject(index) ?: continue
                val role = runCatching { MessageRole.valueOf(item.optString("role")) }.getOrNull() ?: continue
                val text = ConversationMemoryPolicy.sanitize(item.optString("text"))
                if (text.isBlank()) continue
                add(
                    ChatMessage(
                        id = item.optString("id").takeIf(String::isNotBlank) ?: continue,
                        role = role,
                        text = text,
                        createdAt = Instant.ofEpochMilli(item.optLong("created_at", System.currentTimeMillis())),
                        missionId = item.optNullableString("mission_id")
                    )
                )
            }
        }
        val followUpJson = root.optJSONObject("follow_up") ?: JSONObject()
        val artifactIdsJson = followUpJson.optJSONArray("active_artifact_ids") ?: JSONArray()
        val artifactIds = buildList {
            for (index in 0 until artifactIdsJson.length()) {
                artifactIdsJson.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
        return ConversationMemorySnapshot(
            messages = ConversationMemoryPolicy.bounded(messages),
            followUp = FollowUpContext(
                activeMissionId = followUpJson.optNullableString("active_mission_id"),
                activeArtifactIds = artifactIds.take(20),
                activeAppPackage = followUpJson.optNullableString("active_app_package"),
                activePersonRef = followUpJson.optNullableString("active_person_ref"),
                lastIntent = followUpJson.optNullableString("last_intent")
            )
        )
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun JSONObject.putNullable(key: String, value: String?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private companion object {
        const val PREFS = "veltrix.ultron.conversation.v1"
        const val KEY_IV = "snapshot_iv"
        const val KEY_CIPHER = "snapshot_cipher"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "veltrix.ultron.conversation.memory.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val LOCAL_OWNER_PRINCIPAL_ID = "owner"
        const val LOCAL_ANDROID_DEVICE_ID = "android-local"
    }
}
