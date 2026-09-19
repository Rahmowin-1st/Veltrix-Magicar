package com.veltrix.ultron.remote

import org.json.JSONException
import java.io.IOException
import java.security.GeneralSecurityException

/** Sanitized connectivity states for the Android remote bridge. */
enum class RemoteBridgeConnectionState {
    UNCONFIGURED,
    IDLE,
    CONNECTING,
    CONNECTED,
    AUTH_REJECTED,
    CONFIGURATION_ERROR,
    NETWORK_ERROR,
    SERVER_ERROR,
    PROTOCOL_ERROR
}

/**
 * Safe-to-display remote bridge health. It deliberately contains no URL, host,
 * bearer token, response body, task content, or exception message.
 */
data class RemoteBridgeConnectionSnapshot(
    val state: RemoteBridgeConnectionState,
    val consecutiveFailures: Int = 0,
    val lastSuccessEpochMillis: Long? = null,
    val lastFailureEpochMillis: Long? = null,
    val retryBackoffMs: Long? = null,
    val httpStatus: Int? = null
)

/** Typed HTTP failure so diagnostics never need to inspect server-controlled text. */
internal class RemoteBridgeHttpException(
    val statusCode: Int
) : IOException("ULTRON bridge request failed with HTTP $statusCode")

/** Process-local, secret-free connectivity telemetry for recovery and acceptance diagnostics. */
internal class RemoteBridgeDiagnostics(
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    @Volatile
    private var current = RemoteBridgeConnectionSnapshot(RemoteBridgeConnectionState.UNCONFIGURED)

    fun snapshot(): RemoteBridgeConnectionSnapshot = current

    fun shouldRetry(): Boolean = current.state != RemoteBridgeConnectionState.AUTH_REJECTED &&
        current.state != RemoteBridgeConnectionState.CONFIGURATION_ERROR &&
        current.state != RemoteBridgeConnectionState.UNCONFIGURED

    @Synchronized
    fun markConfigured() {
        current = RemoteBridgeConnectionSnapshot(RemoteBridgeConnectionState.IDLE)
    }

    @Synchronized
    fun markUnconfigured() {
        current = RemoteBridgeConnectionSnapshot(RemoteBridgeConnectionState.UNCONFIGURED)
    }

    /** Do not downgrade a known-good connection merely because the next long poll started. */
    @Synchronized
    fun markAttemptStarted() {
        if (current.state == RemoteBridgeConnectionState.UNCONFIGURED ||
            current.state == RemoteBridgeConnectionState.CONNECTED
        ) return
        current = current.copy(
            state = RemoteBridgeConnectionState.CONNECTING,
            retryBackoffMs = null,
            httpStatus = null
        )
    }

    @Synchronized
    fun markSuccess() {
        current = current.copy(
            state = RemoteBridgeConnectionState.CONNECTED,
            consecutiveFailures = 0,
            lastSuccessEpochMillis = nowMs(),
            retryBackoffMs = null,
            httpStatus = null
        )
    }

    @Synchronized
    fun markFailure(error: Throwable, retryBackoffMs: Long? = null) {
        val classification = classify(error)
        val nextFailures = if (current.consecutiveFailures == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            current.consecutiveFailures + 1
        }
        current = current.copy(
            state = classification.state,
            consecutiveFailures = nextFailures,
            lastFailureEpochMillis = nowMs(),
            retryBackoffMs = retryBackoffMs?.coerceAtLeast(0L),
            httpStatus = classification.httpStatus
        )
    }

    private data class Classification(
        val state: RemoteBridgeConnectionState,
        val httpStatus: Int? = null
    )

    private fun classify(error: Throwable): Classification = when (error) {
        is RemoteBridgeHttpException -> when (error.statusCode) {
            401, 403 -> Classification(RemoteBridgeConnectionState.AUTH_REJECTED, error.statusCode)
            408, 425, 429 -> Classification(RemoteBridgeConnectionState.SERVER_ERROR, error.statusCode)
            in 500..599 -> Classification(RemoteBridgeConnectionState.SERVER_ERROR, error.statusCode)
            else -> Classification(RemoteBridgeConnectionState.PROTOCOL_ERROR, error.statusCode)
        }
        is IllegalArgumentException,
        is GeneralSecurityException,
        is SecurityException -> Classification(RemoteBridgeConnectionState.CONFIGURATION_ERROR)
        is JSONException -> Classification(RemoteBridgeConnectionState.PROTOCOL_ERROR)
        is IOException -> Classification(RemoteBridgeConnectionState.NETWORK_ERROR)
        else -> Classification(RemoteBridgeConnectionState.PROTOCOL_ERROR)
    }
}
