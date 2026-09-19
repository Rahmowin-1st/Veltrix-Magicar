package com.veltrix.ultron.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.model.IntegrityErrorCode
import com.veltrix.ultron.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class SecureEnrollmentReceipt(
    val deviceId: String,
    val expiresAtEpochSeconds: Long
)

internal enum class SecureEnrollmentState {
    IDLE,
    ENROLLING,
    CONFIGURED,
    FAILED
}

internal data class SecureEnrollmentSnapshot(
    val state: SecureEnrollmentState,
    val failureCode: String? = null
)

/**
 * Zero-input device enrollment.
 *
 * No bootstrap secret is shipped in the APK. The app proves app/device integrity
 * using a server nonce and Play Integrity, then receives a short-lived scoped
 * device bearer credential. The resulting credential is persisted only through
 * BridgeCredentialStore (Android Keystore AES-GCM).
 */
internal class AndroidSecureDeviceBootstrap(
    context: Context,
    private val credentials: BridgeCredentialStore,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "veltrix-secure-enrollment").apply { isDaemon = true }
    }
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val integrityManager by lazy { IntegrityManagerFactory.create(appContext) }
    private val backendBaseUrl = BridgeCredentialStore.normalizeHttpsBaseUrl(BuildConfig.VELTRIX_BACKEND_URL)
    private val attempts = SecureEnrollmentAttemptCoordinator<SecureEnrollmentReceipt>(
        SecureEnrollmentSnapshot(
            state = if (hasUsableStoredCredential()) SecureEnrollmentState.CONFIGURED else SecureEnrollmentState.IDLE
        )
    )

    fun snapshot(): SecureEnrollmentSnapshot = attempts.snapshot()

    fun enrollAsync(callback: (Result<SecureEnrollmentReceipt>) -> Unit = {}) {
        // A Retry pressed while startup enrollment is already active must join that
        // same attempt. Dropping the callback would leave the UI stuck forever.
        if (attempts.joinIfActive(callback) != null) return

        clearExpiredManagedCredentialIfNeeded()
        if (hasUsableStoredCredential()) {
            attempts.markConfigured()
            val receipt = storedReceipt()
            mainHandler.post { callback(Result.success(receipt)) }
            return
        }

        val attempt = attempts.start(callback)
        if (!attempt.started) return

        worker.execute {
            val prepared = runCatching { requestChallenge() }
            prepared.fold(
                onSuccess = { challenge -> requestIntegrity(attempt.attemptId, challenge) },
                onFailure = { error -> finishFailure(attempt.attemptId, error) }
            )
        }
    }

    fun pairWithCodeAsync(
        pairingCode: String,
        callback: (Result<SecureEnrollmentReceipt>) -> Unit = {}
    ) {
        val cleanCode = pairingCode.trim()
        if (cleanCode.length !in 20..128) {
            mainHandler.post {
                callback(Result.failure(SecureEnrollmentException("PAIRING_CODE_INVALID")))
            }
            return
        }

        clearExpiredManagedCredentialIfNeeded()
        if (hasUsableStoredCredential()) {
            attempts.markConfigured()
            val receipt = storedReceipt()
            mainHandler.post { callback(Result.success(receipt)) }
            return
        }

        val attempt = attempts.start(callback)
        if (!attempt.started) return

        worker.execute {
            val result = runCatching { finishPairing(attempt.attemptId, cleanCode) }
            result.fold(
                onSuccess = { receipt -> finishSuccess(attempt.attemptId, receipt) },
                onFailure = { error -> finishFailure(attempt.attemptId, error) }
            )
        }
    }

    fun resetManagedCredentialMetadata() {
        val pending = attempts.cancelActive()
        clearManagedCredentialMetadataOnly()
        if (pending.isNotEmpty()) {
            val cancelled = Result.failure<SecureEnrollmentReceipt>(
                SecureEnrollmentException("ENROLLMENT_CANCELLED")
            )
            pending.forEach { callback -> mainHandler.post { callback(cancelled) } }
        }
    }

    private fun requestIntegrity(
        attemptId: Long,
        challenge: EnrollmentChallenge
    ) {
        mainHandler.post {
            if (!attempts.isActive(attemptId)) return@post
            val timeoutMs = playIntegrityTimeoutMs(
                challenge.expiresAtEpochSeconds,
                currentEpochSeconds()
            )
            if (timeoutMs == null) {
                finishFailure(
                    attemptId,
                    SecureEnrollmentException("BOOTSTRAP_CHALLENGE_EXPIRED")
                )
                return@post
            }

            val request = runCatching {
                IntegrityTokenRequest.builder()
                    .setNonce(challenge.nonce)
                    .setCloudProjectNumber(challenge.cloudProjectNumber)
                    .build()
            }.getOrElse { error ->
                finishFailure(attemptId, error)
                return@post
            }

            val timeout = Runnable {
                finishFailure(
                    attemptId,
                    SecureEnrollmentException("PLAY_INTEGRITY_TIMEOUT")
                )
            }
            mainHandler.postDelayed(timeout, timeoutMs)

            val task = runCatching { integrityManager.requestIntegrityToken(request) }
                .getOrElse { error ->
                    mainHandler.removeCallbacks(timeout)
                    finishFailure(attemptId, error, safePlayIntegrityFailureCode(error))
                    return@post
                }

            task.addOnSuccessListener { response ->
                mainHandler.removeCallbacks(timeout)
                if (!attempts.isActive(attemptId)) return@addOnSuccessListener
                worker.execute {
                    val result = runCatching {
                        finishEnrollment(attemptId, challenge, response.token())
                    }
                    result.fold(
                        onSuccess = { receipt -> finishSuccess(attemptId, receipt) },
                        onFailure = { error -> finishFailure(attemptId, error) }
                    )
                }
            }.addOnFailureListener { error ->
                mainHandler.removeCallbacks(timeout)
                finishFailure(attemptId, error, safePlayIntegrityFailureCode(error))
            }
        }
    }

    private fun requestChallenge(): EnrollmentChallenge {
        val buildSha = BuildConfig.VELTRIX_BUILD_SHA.trim().lowercase()
        if (!BUILD_SHA_PATTERN.matches(buildSha)) throw SecureEnrollmentException("BUILD_PROVENANCE_MISSING")
        val installationId = installationId()
        val response = postJson(
            path = "/v1/device/enroll/challenge",
            body = JSONObject()
                .put("installationId", installationId)
                .put("buildSha", buildSha)
        )
        val nonce = response.optString("nonce").trim()
        val projectNumber = response.optString("cloudProjectNumber").trim().toLongOrNull()
            ?: throw SecureEnrollmentException("BOOTSTRAP_PROJECT_INVALID")
        val expiresAt = response.optLong("expiresAt", 0L)
        if (!NONCE_PATTERN.matches(nonce)) throw SecureEnrollmentException("BOOTSTRAP_NONCE_INVALID")
        if (projectNumber <= 0L) throw SecureEnrollmentException("BOOTSTRAP_PROJECT_INVALID")
        if (expiresAt <= 0L) throw SecureEnrollmentException("BOOTSTRAP_CHALLENGE_INVALID")
        return EnrollmentChallenge(nonce, projectNumber, expiresAt)
    }

    private fun finishPairing(
        attemptId: Long,
        pairingCode: String
    ): SecureEnrollmentReceipt {
        if (!attempts.isActive(attemptId)) throw SecureEnrollmentException("ENROLLMENT_STALE")
        val buildSha = BuildConfig.VELTRIX_BUILD_SHA.trim().lowercase()
        if (!BUILD_SHA_PATTERN.matches(buildSha)) throw SecureEnrollmentException("BUILD_PROVENANCE_MISSING")
        val response = postJson(
            path = "/v1/device/pair",
            body = JSONObject()
                .put("installationId", installationId())
                .put("buildSha", buildSha)
                .put("pairingCode", pairingCode)
        )
        return persistCredentialResponse(attemptId, response)
    }

    private fun finishEnrollment(
        attemptId: Long,
        challenge: EnrollmentChallenge,
        integrityToken: String
    ): SecureEnrollmentReceipt {
        if (!attempts.isActive(attemptId)) throw SecureEnrollmentException("ENROLLMENT_STALE")
        if (integrityToken.isBlank()) throw SecureEnrollmentException("PLAY_INTEGRITY_EMPTY")
        if (challenge.expiresAtEpochSeconds <= currentEpochSeconds() + ENROLL_SUBMIT_BUDGET_SECONDS) {
            throw SecureEnrollmentException("BOOTSTRAP_CHALLENGE_EXPIRED")
        }
        val response = postJson(
            path = "/v1/device/enroll",
            body = JSONObject()
                .put("nonce", challenge.nonce)
                .put("integrityToken", integrityToken)
        )
        return persistCredentialResponse(attemptId, response)
    }

    private fun persistCredentialResponse(
        attemptId: Long,
        response: JSONObject
    ): SecureEnrollmentReceipt {
        val bearerToken = response.optString("bearerToken").trim()
        val deviceId = response.optString("deviceId").trim()
        val expiresAt = response.optLong("expiresAt", 0L)
        if (bearerToken.isBlank() || deviceId.isBlank() || expiresAt <= currentEpochSeconds()) {
            throw SecureEnrollmentException("BOOTSTRAP_RESPONSE_INVALID")
        }

        val committed = attempts.commitIfActive(attemptId) {
            val metadataPersisted = prefs.edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .putLong(KEY_TOKEN_EXPIRES_AT, expiresAt)
                .commit()
            if (!metadataPersisted) {
                throw SecureEnrollmentException("BOOTSTRAP_METADATA_PERSIST_FAILED")
            }
            try {
                credentials.save(backendBaseUrl, bearerToken, deviceId)
            } catch (_: Exception) {
                clearManagedCredentialMetadataOnly()
                throw SecureEnrollmentException("BOOTSTRAP_CREDENTIAL_PERSIST_FAILED")
            }
        }
        if (!committed) throw SecureEnrollmentException("ENROLLMENT_STALE")
        return SecureEnrollmentReceipt(deviceId, expiresAt)
    }

    private fun finishSuccess(attemptId: Long, receipt: SecureEnrollmentReceipt) {
        val callbacks = attempts.complete(
            attemptId,
            SecureEnrollmentSnapshot(SecureEnrollmentState.CONFIGURED)
        )
        if (callbacks.isEmpty()) return
        val result = Result.success(receipt)
        callbacks.forEach { callback -> mainHandler.post { callback(result) } }
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        val endpoint = backendBaseUrl + path
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-store")
        try {
            connection.outputStream.use { stream ->
                stream.write(body.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val source = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = source?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw SecureEnrollmentException(safeBootstrapServerFailureCode(status, raw))
            }
            return runCatching { JSONObject(raw) }
                .getOrElse { throw SecureEnrollmentException("BOOTSTRAP_RESPONSE_INVALID") }
        } finally {
            connection.disconnect()
        }
    }

    private fun hasUsableStoredCredential(): Boolean {
        val existing = runCatching { credentials.load() }.getOrNull() ?: return false
        if (existing.baseUrl != backendBaseUrl) return false
        val managedExpiry = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        if (managedExpiry == 0L) return true // backward-compatible pre-provisioned credential
        return managedExpiry > currentEpochSeconds() + RENEW_BEFORE_SECONDS
    }

    private fun storedReceipt(): SecureEnrollmentReceipt = SecureEnrollmentReceipt(
        deviceId = prefs.getString(KEY_DEVICE_ID, null).orEmpty().ifBlank { "configured" },
        expiresAtEpochSeconds = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
    )

    private fun clearExpiredManagedCredentialIfNeeded() {
        val managedExpiry = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        if (managedExpiry == 0L) return
        if (managedExpiry <= currentEpochSeconds() + RENEW_BEFORE_SECONDS) {
            credentials.clear()
            clearManagedCredentialMetadataOnly()
        }
    }

    private fun clearManagedCredentialMetadataOnly() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_TOKEN_EXPIRES_AT)
            .commit()
    }

    private fun finishFailure(
        attemptId: Long,
        error: Throwable,
        overrideCode: String? = null
    ) {
        val code = overrideCode ?: (error as? SecureEnrollmentException)?.safeCode ?: when (error) {
            is java.net.SocketTimeoutException -> "BOOTSTRAP_TIMEOUT"
            is java.io.IOException -> "BOOTSTRAP_NETWORK"
            else -> "BOOTSTRAP_FAILED"
        }
        val callbacks = attempts.complete(
            attemptId,
            SecureEnrollmentSnapshot(SecureEnrollmentState.FAILED, code)
        )
        if (callbacks.isEmpty()) return
        val safe = Result.failure<SecureEnrollmentReceipt>(SecureEnrollmentException(code))
        callbacks.forEach { callback -> mainHandler.post { callback(safe) } }
    }

    private fun installationId(): String {
        prefs.getString(KEY_INSTALLATION_ID, null)?.takeIf(INSTALLATION_ID_PATTERN::matches)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        val persisted = prefs.edit().putString(KEY_INSTALLATION_ID, fresh).commit()
        if (!persisted) throw SecureEnrollmentException("BOOTSTRAP_INSTALLATION_ID_PERSIST_FAILED")
        return fresh
    }

    private fun currentEpochSeconds(): Long = System.currentTimeMillis() / 1000L

    private data class EnrollmentChallenge(
        val nonce: String,
        val cloudProjectNumber: Long,
        val expiresAtEpochSeconds: Long
    )

    private class SecureEnrollmentException(val safeCode: String) : Exception(safeCode)

    companion object {
        private const val PREFS = "veltrix.ultron.enrollment.v1"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val RENEW_BEFORE_SECONDS = 24 * 60 * 60L
        private val BUILD_SHA_PATTERN = Regex("^[0-9a-f]{40}$")
        private val NONCE_PATTERN = Regex("^[A-Za-z0-9_-]{32,128}$")
        private val INSTALLATION_ID_PATTERN = Regex("^[a-zA-Z0-9._-]{16,128}$")
    }
}

internal fun safePlayIntegrityFailureCode(error: Throwable): String {
    val integrity = error as? IntegrityServiceException ?: return "PLAY_INTEGRITY_FAILED"
    return safePlayIntegrityErrorCode(integrity.errorCode)
}

internal fun safePlayIntegrityErrorCode(errorCode: Int): String = when (errorCode) {
    IntegrityErrorCode.API_NOT_AVAILABLE -> "PLAY_INTEGRITY_API_NOT_AVAILABLE"
    IntegrityErrorCode.PLAY_STORE_NOT_FOUND -> "PLAY_INTEGRITY_PLAY_STORE_NOT_FOUND"
    IntegrityErrorCode.NETWORK_ERROR -> "PLAY_INTEGRITY_NETWORK_ERROR"
    IntegrityErrorCode.PLAY_STORE_ACCOUNT_NOT_FOUND -> "PLAY_INTEGRITY_ACCOUNT_NOT_FOUND"
    IntegrityErrorCode.APP_NOT_INSTALLED -> "PLAY_INTEGRITY_APP_NOT_INSTALLED"
    IntegrityErrorCode.PLAY_SERVICES_NOT_FOUND -> "PLAY_INTEGRITY_PLAY_SERVICES_NOT_FOUND"
    IntegrityErrorCode.APP_UID_MISMATCH -> "PLAY_INTEGRITY_APP_UID_MISMATCH"
    IntegrityErrorCode.TOO_MANY_REQUESTS -> "PLAY_INTEGRITY_TOO_MANY_REQUESTS"
    IntegrityErrorCode.CANNOT_BIND_TO_SERVICE -> "PLAY_INTEGRITY_CANNOT_BIND_TO_SERVICE"
    IntegrityErrorCode.NONCE_TOO_SHORT -> "PLAY_INTEGRITY_NONCE_TOO_SHORT"
    IntegrityErrorCode.NONCE_TOO_LONG -> "PLAY_INTEGRITY_NONCE_TOO_LONG"
    IntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE -> "PLAY_INTEGRITY_GOOGLE_SERVER_UNAVAILABLE"
    IntegrityErrorCode.NONCE_IS_NOT_BASE64 -> "PLAY_INTEGRITY_NONCE_INVALID"
    IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED -> "PLAY_INTEGRITY_PLAY_STORE_OUTDATED"
    IntegrityErrorCode.PLAY_SERVICES_VERSION_OUTDATED -> "PLAY_INTEGRITY_PLAY_SERVICES_OUTDATED"
    IntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID -> "PLAY_INTEGRITY_PROJECT_INVALID"
    IntegrityErrorCode.CLIENT_TRANSIENT_ERROR -> "PLAY_INTEGRITY_CLIENT_TRANSIENT"
    IntegrityErrorCode.INTERNAL_ERROR -> "PLAY_INTEGRITY_INTERNAL_ERROR"
    else -> "PLAY_INTEGRITY_ERROR_$errorCode"
}

internal fun playIntegrityTimeoutMs(
    challengeExpiresAtEpochSeconds: Long,
    nowEpochSeconds: Long
): Long? {
    if (challengeExpiresAtEpochSeconds <= nowEpochSeconds) return null
    val remainingSeconds = (challengeExpiresAtEpochSeconds - nowEpochSeconds).coerceAtMost(10 * 60L)
    val availableMs = remainingSeconds * 1000L - ENROLL_SUBMIT_BUDGET_MS
    if (availableMs < MIN_PLAY_INTEGRITY_WINDOW_MS) return null
    return minOf(DEFAULT_PLAY_INTEGRITY_TIMEOUT_MS, availableMs)
}

internal fun safeBootstrapServerFailureCode(status: Int, rawBody: String): String {
    val code = SERVER_ERROR_FIELD_PATTERN.find(rawBody)?.groupValues?.getOrNull(1)
    if (code != null && SERVER_ERROR_CODE_PATTERN.matches(code)) {
        return "BOOTSTRAP_SERVER_${code.uppercase()}"
    }
    return "BOOTSTRAP_HTTP_$status"
}

private const val DEFAULT_PLAY_INTEGRITY_TIMEOUT_MS = 60_000L
private const val ENROLL_SUBMIT_BUDGET_MS = 15_000L
private const val ENROLL_SUBMIT_BUDGET_SECONDS = 15L
private const val MIN_PLAY_INTEGRITY_WINDOW_MS = 5_000L
private val SERVER_ERROR_FIELD_PATTERN = Regex("\"error\"\\s*:\\s*\"([a-z0-9_]{1,96})\"")
private val SERVER_ERROR_CODE_PATTERN = Regex("^(integrity_[a-z0-9_]+|enrollment_[a-z0-9_]+|invalid_[a-z0-9_]+|secure_enrollment_[a-z0-9_]+|build_sha_not_allowed|rate_limited)$")
