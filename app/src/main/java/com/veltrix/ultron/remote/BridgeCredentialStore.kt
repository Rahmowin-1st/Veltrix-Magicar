package com.veltrix.ultron.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BridgeCredentials(
    val baseUrl: String,
    val bearerToken: String,
    val deviceId: String? = null
)

/** Device bearer tokens are encrypted with an Android Keystore key before persistence. */
class BridgeCredentialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(baseUrl: String, bearerToken: String, deviceId: String? = null) {
        val normalizedUrl = normalizeHttpsBaseUrl(baseUrl)
        require(bearerToken.isNotBlank()) { "Bridge bearer token must not be blank" }
        val normalizedDeviceId = deviceId?.trim()?.takeIf(String::isNotBlank)
        if (normalizedDeviceId != null) {
            require(DEVICE_ID_PATTERN.matches(normalizedDeviceId)) { "Bridge device id is invalid" }
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(bearerToken.toByteArray(Charsets.UTF_8))
        val persisted = prefs.edit()
            .putString(KEY_BASE_URL, normalizedUrl)
            .putString(KEY_TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_TOKEN_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply {
                if (normalizedDeviceId == null) remove(KEY_DEVICE_ID)
                else putString(KEY_DEVICE_ID, normalizedDeviceId)
            }
            .commit()
        check(persisted) { "Bridge credential persistence failed" }
    }

    fun load(): BridgeCredentials? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val iv = prefs.getString(KEY_TOKEN_IV, null) ?: return null
        val encrypted = prefs.getString(KEY_TOKEN_CIPHER, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        val token = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        if (token.isBlank()) return null
        val storedDeviceId = prefs.getString(KEY_DEVICE_ID, null)
            ?.trim()
            ?.takeIf { DEVICE_ID_PATTERN.matches(it) }
        return BridgeCredentials(normalizeHttpsBaseUrl(baseUrl), token, storedDeviceId)
    }

    fun clear() {
        // Synchronous persistence prevents an owner clear from being acknowledged
        // while the old encrypted credential is still only queued for disk removal.
        val cleared = prefs.edit()
            .remove(KEY_BASE_URL)
            .remove(KEY_TOKEN_IV)
            .remove(KEY_TOKEN_CIPHER)
            .remove(KEY_DEVICE_ID)
            .commit()
        check(cleared) { "Bridge credential clear failed" }
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

    companion object {
        private const val PREFS = "veltrix.ultron.bridge.v1"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN_IV = "device_token_iv"
        private const val KEY_TOKEN_CIPHER = "device_token_cipher"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "veltrix.ultron.bridge.device_token.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9._-]{1,200}$")

        fun normalizeHttpsBaseUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "Bridge URL must not be blank" }
            val uri = URI(trimmed)
            require(uri.scheme.equals("https", ignoreCase = true)) { "Bridge URL must use HTTPS" }
            require(!uri.host.isNullOrBlank()) { "Bridge URL must contain a host" }
            require(uri.userInfo == null) { "Bridge URL must not contain embedded credentials" }
            require(uri.fragment == null && uri.query == null) { "Bridge URL must not contain query or fragment" }
            return trimmed
        }
    }
}
