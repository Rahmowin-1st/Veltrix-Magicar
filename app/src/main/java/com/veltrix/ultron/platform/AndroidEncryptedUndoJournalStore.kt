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

/** AES/GCM durable storage for reversible Android checkpoints. */
internal class AndroidEncryptedUndoJournalStore(context: Context) : UndoJournalPersistence {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): List<AndroidUndoCheckpoint> {
        val iv = prefs.getString(KEY_IV, null) ?: return emptyList()
        val encrypted = prefs.getString(KEY_CIPHER, null) ?: return emptyList()
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
            runCatching { clear() }
            emptyList()
        }
    }

    override fun save(checkpoints: List<AndroidUndoCheckpoint>) {
        if (checkpoints.isEmpty()) {
            clear()
            return
        }
        val plaintext = encode(checkpoints).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext)
        val committed = prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
        check(committed) { "Undo journal persistence commit failed" }
    }

    override fun clear() {
        val committed = prefs.edit().remove(KEY_IV).remove(KEY_CIPHER).commit()
        check(committed) { "Undo journal persistence clear failed" }
    }

    private fun encode(checkpoints: List<AndroidUndoCheckpoint>): JSONObject {
        val items = JSONArray()
        checkpoints.forEach { checkpoint ->
            items.put(
                JSONObject()
                    .put("id", checkpoint.id)
                    .put("mission_id", checkpoint.missionId)
                    .put("description", checkpoint.description)
                    .put("expected_package", checkpoint.expectedCurrentPackage)
                    .put("restore_package", checkpoint.restorePackage)
                    .put("created_at", checkpoint.createdAtEpochMs)
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("checkpoints", items)
    }

    private fun decode(root: JSONObject): List<AndroidUndoCheckpoint> {
        check(root.optInt("version", -1) == VERSION) { "Unsupported undo journal version" }
        val items = root.optJSONArray("checkpoints") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: error("Invalid undo checkpoint")
                add(
                    AndroidUndoCheckpoint(
                        id = item.optString("id"),
                        missionId = item.optString("mission_id"),
                        description = item.optString("description"),
                        expectedCurrentPackage = item.optString("expected_package"),
                        restorePackage = item.optString("restore_package"),
                        createdAtEpochMs = item.optLong("created_at", -1L)
                    )
                )
            }
        }
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

    private companion object {
        const val VERSION = 1
        const val PREFS = "veltrix.ultron.undo.v1"
        const val KEY_IV = "snapshot_iv"
        const val KEY_CIPHER = "snapshot_cipher"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "veltrix.ultron.undo.journal.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}