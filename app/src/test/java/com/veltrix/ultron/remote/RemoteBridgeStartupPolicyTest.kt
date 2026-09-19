package com.veltrix.ultron.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteBridgeStartupPolicyTest {
    @Test
    fun pairedStoppedProcessRestoresBridgeExactlyOnce() {
        var starts = 0

        val restored = restoreRemoteBridgeLoopIfNeeded(
            configuredCheck = { true },
            runningCheck = { false },
            start = { starts += 1 }
        )

        assertTrue(restored)
        assertEquals(1, starts)
    }

    @Test
    fun unpairedProcessDoesNotStartBridge() {
        var starts = 0

        val restored = restoreRemoteBridgeLoopIfNeeded(
            configuredCheck = { false },
            runningCheck = { false },
            start = { starts += 1 }
        )

        assertFalse(restored)
        assertEquals(0, starts)
    }

    @Test
    fun alreadyRunningBridgeIsNotStartedTwice() {
        var starts = 0

        val restored = restoreRemoteBridgeLoopIfNeeded(
            configuredCheck = { true },
            runningCheck = { true },
            start = { starts += 1 }
        )

        assertFalse(restored)
        assertEquals(0, starts)
    }

    @Test
    fun unreadableCredentialFailsClosedWithoutStartingBridge() {
        var starts = 0

        val restored = restoreRemoteBridgeLoopIfNeeded(
            configuredCheck = { error("credential decrypt failed") },
            runningCheck = { false },
            start = { starts += 1 }
        )

        assertFalse(restored)
        assertEquals(0, starts)
    }
}
