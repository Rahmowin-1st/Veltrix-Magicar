package com.veltrix.ultron.remote

import android.content.Context
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class GeminiLiveEphemeralToken(
    val token: String,
    val model: String,
    val expireTime: String?,
    val newSessionExpireTime: String?
)

/**
 * Fetches a single-use Gemini Live credential from the Veltrix backend.
 *
 * The APK never contains the long-lived Gemini API key. The only credential
 * returned to Android is the backend-minted short-lived token.
 */
class GeminiLiveTokenClient(
    context: Context,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000
) {
    private val credentials = BridgeCredentialStore(context.applicationContext)

    fun request(): GeminiLiveEphemeralToken {
        val bridge = credentials.load()
            ?: throw IllegalStateException("Veltrix device is not securely enrolled")
        val baseUrl = BridgeCredentialStore.normalizeHttpsBaseUrl(bridge.baseUrl)
        require(bridge.bearerToken.isNotBlank()) { "Veltrix device credential is missing" }

        val connection = URL("$baseUrl/v1/device/live/token").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Authorization", "Bearer ${bridge.bearerToken}")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("Gemini Live token unavailable (HTTP $status)")
            }
            val root = runCatching { JSONObject(raw) }
                .getOrElse { throw IllegalStateException("Gemini Live token response was invalid") }
            val token = root.optString("token").trim()
            val model = root.optString("model").trim()
            if (token.isBlank() || model.isBlank()) {
                throw IllegalStateException("Gemini Live token response was incomplete")
            }
            return GeminiLiveEphemeralToken(
                token = token,
                model = model,
                expireTime = root.optString("expireTime").trim().takeIf(String::isNotBlank),
                newSessionExpireTime = root.optString("newSessionExpireTime").trim().takeIf(String::isNotBlank)
            )
        } finally {
            connection.disconnect()
        }
    }
}
