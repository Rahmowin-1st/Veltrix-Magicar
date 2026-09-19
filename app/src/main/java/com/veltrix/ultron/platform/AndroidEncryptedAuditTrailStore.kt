package com.veltrix.ultron.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val AUDIT_KEY_ALIAS = "veltrix.ultron.audit.trail.v1"

/** Dedicated AES/GCM storage for the bounded security audit snapshot. */
internal class AndroidEncryptedAuditTrailStore(context: Context) : AuditTrailPersistence {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): List<AndroidAuditEvent> {
        val iv = prefs.getString(KEY_IV, null)
        val encrypted = prefs.getString(KEY_CIPHER, null)
        if (iv == null && encrypted == null) return emptyList()
        check(iv != null && encrypted != null) { "Truncated audit snapshot" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        val plaintext = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        return AndroidAuditTrailCodec.decode(plaintext)
    }

    override fun save(events: List<AndroidAuditEvent>) {
        if (events.isEmpty()) {
            clear()
            return
        }
        val plaintext = AndroidAuditTrailCodec.encode(events).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext)
        val committed = prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
        check(committed) { "Audit trail persistence commit failed" }
    }

    override fun clear() {
        val committed = prefs.edit().remove(KEY_IV).remove(KEY_CIPHER).commit()
        check(committed) { "Audit trail persistence clear failed" }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(AUDIT_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                AUDIT_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "veltrix.ultron.audit.v1"
        const val KEY_IV = "snapshot_iv"
        const val KEY_CIPHER = "snapshot_cipher"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Pure schema guard so JVM unit tests do not need Android's org.json implementation. */
internal object AndroidAuditTrailSchema {
    const val VERSION = 1

    fun requireSupportedVersion(version: Int) {
        check(version == VERSION) { "Unsupported audit trail version" }
    }
}

/** Versioned plaintext schema inside the authenticated encrypted envelope. */
internal object AndroidAuditTrailCodec {
    fun encode(events: List<AndroidAuditEvent>): String {
        val items = JSONArray()
        events.forEach { event ->
            items.put(
                JSONObject()
                    .put("id", event.id)
                    .put("sequence", event.sequence)
                    .put("mission_id", event.missionId)
                    .put("principal_kind", event.principalKind)
                    .put("principal_ref", event.principalRef)
                    .put("device_id", event.deviceId)
                    .put("type", event.type.name)
                    .put("capability", event.capability)
                    .put("action_type", event.actionType)
                    .put("target_ref", event.targetRef)
                    .put("result_code", event.resultCode)
                    .put("created_at", event.createdAtEpochMs)
            )
        }
        return JSONObject()
            .put("version", AndroidAuditTrailSchema.VERSION)
            .put("events", items)
            .toString()
    }

    fun decode(value: String): List<AndroidAuditEvent> {
        val root = JSONObject(value)
        AndroidAuditTrailSchema.requireSupportedVersion(root.optInt("version", -1))
        val items = root.optJSONArray("events") ?: error("Audit events missing")
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: error("Invalid audit event")
                add(
                    AndroidAuditEvent(
                        id = item.getString("id"),
                        sequence = item.getLong("sequence"),
                        missionId = item.getString("mission_id"),
                        principalKind = item.getString("principal_kind"),
                        principalRef = item.getString("principal_ref"),
                        deviceId = item.getString("device_id"),
                        type = AndroidAuditEventType.valueOf(item.getString("type")),
                        capability = item.optString("capability").takeIf { it.isNotEmpty() && it != "null" },
                        actionType = item.optString("action_type").takeIf { it.isNotEmpty() && it != "null" },
                        targetRef = item.optString("target_ref").takeIf { it.isNotEmpty() && it != "null" },
                        resultCode = item.getString("result_code"),
                        createdAtEpochMs = item.getLong("created_at")
                    )
                )
            }
        }
    }
}
