package com.veltrix.ultron.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException

class RemoteBridgeDiagnosticsTest {
    @Test
    fun configuredAttemptAndSuccessExposeOnlySafeState() {
        var now = 100L
        val diagnostics = RemoteBridgeDiagnostics { now }

        assertEquals(RemoteBridgeConnectionState.UNCONFIGURED, diagnostics.snapshot().state)
        assertFalse(diagnostics.shouldRetry())
        diagnostics.markConfigured()
        assertEquals(RemoteBridgeConnectionState.IDLE, diagnostics.snapshot().state)
        assertTrue(diagnostics.shouldRetry())

        diagnostics.markAttemptStarted()
        assertEquals(RemoteBridgeConnectionState.CONNECTING, diagnostics.snapshot().state)

        now = 200L
        diagnostics.markSuccess()
        val connected = diagnostics.snapshot()
        assertEquals(RemoteBridgeConnectionState.CONNECTED, connected.state)
        assertEquals(0, connected.consecutiveFailures)
        assertEquals(200L, connected.lastSuccessEpochMillis)
        assertNull(connected.retryBackoffMs)
        assertNull(connected.httpStatus)
        assertTrue(diagnostics.shouldRetry())
    }

    @Test
    fun authFailureIsTypedWithoutServerControlledTextAndStopsRetry() {
        val diagnostics = RemoteBridgeDiagnostics { 500L }
        diagnostics.markConfigured()
        diagnostics.markFailure(RemoteBridgeHttpException(401), retryBackoffMs = 1_000L)

        val snapshot = diagnostics.snapshot()
        assertEquals(RemoteBridgeConnectionState.AUTH_REJECTED, snapshot.state)
        assertEquals(1, snapshot.consecutiveFailures)
        assertEquals(500L, snapshot.lastFailureEpochMillis)
        assertEquals(1_000L, snapshot.retryBackoffMs)
        assertEquals(401, snapshot.httpStatus)
        assertFalse(snapshot.toString().contains("token", ignoreCase = true))
        assertFalse(diagnostics.shouldRetry())
    }

    @Test
    fun networkFailureNeverCopiesSensitiveExceptionMessageAndRemainsRetryable() {
        val diagnostics = RemoteBridgeDiagnostics { 700L }
        diagnostics.markConfigured()
        diagnostics.markFailure(
            IOException("failed https://secret.example/?token=do-not-copy"),
            retryBackoffMs = 2_000L
        )

        val snapshot = diagnostics.snapshot()
        assertEquals(RemoteBridgeConnectionState.NETWORK_ERROR, snapshot.state)
        assertEquals(2_000L, snapshot.retryBackoffMs)
        assertNull(snapshot.httpStatus)
        assertFalse(snapshot.toString().contains("secret.example"))
        assertFalse(snapshot.toString().contains("do-not-copy"))
        assertTrue(diagnostics.shouldRetry())
    }

    @Test
    fun credentialDecryptFailureIsConfigurationErrorAndStopsRetry() {
        val diagnostics = RemoteBridgeDiagnostics { 900L }
        diagnostics.markFailure(GeneralSecurityException("keystore secret detail"))

        val snapshot = diagnostics.snapshot()
        assertEquals(RemoteBridgeConnectionState.CONFIGURATION_ERROR, snapshot.state)
        assertEquals(1, snapshot.consecutiveFailures)
        assertEquals(900L, snapshot.lastFailureEpochMillis)
        assertFalse(snapshot.toString().contains("keystore secret detail"))
        assertFalse(diagnostics.shouldRetry())
    }

    @Test
    fun serverAndProtocolHttpFailuresRemainDistinctAndRetryable() {
        val diagnostics = RemoteBridgeDiagnostics { 1_000L }
        diagnostics.markConfigured()

        diagnostics.markFailure(RemoteBridgeHttpException(503), retryBackoffMs = 4_000L)
        assertEquals(RemoteBridgeConnectionState.SERVER_ERROR, diagnostics.snapshot().state)
        assertEquals(503, diagnostics.snapshot().httpStatus)
        assertTrue(diagnostics.shouldRetry())

        diagnostics.markFailure(RemoteBridgeHttpException(409), retryBackoffMs = 8_000L)
        val protocol = diagnostics.snapshot()
        assertEquals(RemoteBridgeConnectionState.PROTOCOL_ERROR, protocol.state)
        assertEquals(2, protocol.consecutiveFailures)
        assertEquals(409, protocol.httpStatus)
        assertEquals(8_000L, protocol.retryBackoffMs)
        assertTrue(diagnostics.shouldRetry())
    }

    @Test
    fun successfulRecoveryClearsFailureAndNextLongPollKeepsConnectedState() {
        var now = 1_100L
        val diagnostics = RemoteBridgeDiagnostics { now }
        diagnostics.markConfigured()
        diagnostics.markFailure(IOException("offline"), retryBackoffMs = 1_000L)

        now = 1_200L
        diagnostics.markSuccess()
        diagnostics.markAttemptStarted()

        val recovered = diagnostics.snapshot()
        assertEquals(RemoteBridgeConnectionState.CONNECTED, recovered.state)
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(1_200L, recovered.lastSuccessEpochMillis)
        assertNull(recovered.retryBackoffMs)
        assertNull(recovered.httpStatus)
    }

    @Test
    fun clearingPairingResetsConnectivityHistoryAndRetryPolicy() {
        val diagnostics = RemoteBridgeDiagnostics { 1_300L }
        diagnostics.markConfigured()
        diagnostics.markFailure(IOException("offline"), retryBackoffMs = 1_000L)
        diagnostics.markUnconfigured()

        assertEquals(
            RemoteBridgeConnectionSnapshot(RemoteBridgeConnectionState.UNCONFIGURED),
            diagnostics.snapshot()
        )
        assertFalse(diagnostics.shouldRetry())
    }
}
