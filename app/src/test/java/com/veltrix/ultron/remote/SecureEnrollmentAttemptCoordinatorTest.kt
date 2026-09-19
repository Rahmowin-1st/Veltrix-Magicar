package com.veltrix.ultron.remote

import com.google.android.play.core.integrity.model.IntegrityErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureEnrollmentAttemptCoordinatorTest {
    @Test
    fun concurrentRetryJoinsActiveAttemptAndBothCallbacksCompleteOnce() {
        val coordinator = SecureEnrollmentAttemptCoordinator<String>(
            SecureEnrollmentSnapshot(SecureEnrollmentState.IDLE)
        )
        val deliveries = mutableListOf<String>()

        val first = coordinator.start { result -> deliveries += "first:${result.getOrNull()}" }
        val joinedId = coordinator.joinIfActive { result -> deliveries += "joined:${result.getOrNull()}" }

        assertTrue(first.started)
        assertEquals(first.attemptId, joinedId)
        assertEquals(SecureEnrollmentState.ENROLLING, coordinator.snapshot().state)

        val callbacks = coordinator.complete(
            first.attemptId,
            SecureEnrollmentSnapshot(SecureEnrollmentState.CONFIGURED)
        )
        callbacks.forEach { it(Result.success("ok")) }

        assertEquals(listOf("first:ok", "joined:ok"), deliveries)
        assertEquals(SecureEnrollmentState.CONFIGURED, coordinator.snapshot().state)
        assertTrue(
            coordinator.complete(
                first.attemptId,
                SecureEnrollmentSnapshot(SecureEnrollmentState.FAILED, "STALE")
            ).isEmpty()
        )
    }

    @Test
    fun cancelledAttemptCannotOverrideNewAttemptWithLateCompletion() {
        val coordinator = SecureEnrollmentAttemptCoordinator<String>(
            SecureEnrollmentSnapshot(SecureEnrollmentState.IDLE)
        )
        val first = coordinator.start { }
        assertEquals(1, coordinator.cancelActive().size)
        assertEquals(SecureEnrollmentState.IDLE, coordinator.snapshot().state)

        val second = coordinator.start { }
        assertFalse(first.attemptId == second.attemptId)
        assertTrue(
            coordinator.complete(
                first.attemptId,
                SecureEnrollmentSnapshot(SecureEnrollmentState.CONFIGURED)
            ).isEmpty()
        )
        assertEquals(SecureEnrollmentState.ENROLLING, coordinator.snapshot().state)

        assertEquals(
            1,
            coordinator.complete(
                second.attemptId,
                SecureEnrollmentSnapshot(SecureEnrollmentState.FAILED, "PLAY_INTEGRITY_TIMEOUT")
            ).size
        )
        assertEquals("PLAY_INTEGRITY_TIMEOUT", coordinator.snapshot().failureCode)
    }

    @Test
    fun ownerCancellationPreventsLateCredentialCommit() {
        val coordinator = SecureEnrollmentAttemptCoordinator<String>(
            SecureEnrollmentSnapshot(SecureEnrollmentState.IDLE)
        )
        val attempt = coordinator.start { }
        var committed = false

        assertTrue(coordinator.commitIfActive(attempt.attemptId) { committed = true })
        assertTrue(committed)

        coordinator.cancelActive()
        committed = false
        assertFalse(coordinator.commitIfActive(attempt.attemptId) { committed = true })
        assertFalse(committed)
    }

    @Test
    fun playIntegrityErrorCodesMapWithoutConstructingAndroidExceptions() {
        assertEquals(
            "PLAY_INTEGRITY_PROJECT_INVALID",
            safePlayIntegrityErrorCode(IntegrityErrorCode.CLOUD_PROJECT_NUMBER_IS_INVALID)
        )
        assertEquals(
            "PLAY_INTEGRITY_PLAY_STORE_OUTDATED",
            safePlayIntegrityErrorCode(IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED)
        )
        assertEquals(
            "PLAY_INTEGRITY_ERROR_123456",
            safePlayIntegrityErrorCode(123456)
        )
    }

    @Test
    fun playIntegrityDeadlinePreservesEnrollmentSubmitBudget() {
        assertEquals(60_000L, playIntegrityTimeoutMs(200L, 100L))
        assertEquals(15_000L, playIntegrityTimeoutMs(130L, 100L))
        assertNull(playIntegrityTimeoutMs(119L, 100L))
        assertNull(playIntegrityTimeoutMs(100L, 100L))
    }

    @Test
    fun serverEnrollmentErrorsAreSurfacedOnlyThroughBoundedSafeCodes() {
        assertEquals(
            "BOOTSTRAP_SERVER_INTEGRITY_SIGNER_MISMATCH",
            safeBootstrapServerFailureCode(403, "{\"error\":\"integrity_signer_mismatch\"}")
        )
        assertEquals(
            "BOOTSTRAP_SERVER_RATE_LIMITED",
            safeBootstrapServerFailureCode(429, "{\"error\":\"rate_limited\"}")
        )
        assertEquals(
            "BOOTSTRAP_HTTP_403",
            safeBootstrapServerFailureCode(403, "{\"error\":\"unexpected raw server detail\"}")
        )
    }
}
