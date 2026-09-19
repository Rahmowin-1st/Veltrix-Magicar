package com.veltrix.ultron.platform

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AndroidScreenCaptureBridgeTest {
    @Before
    fun setUp() {
        AndroidScreenCaptureBridge.clear()
    }

    @After
    fun tearDown() {
        AndroidScreenCaptureBridge.clear()
    }

    @Test
    fun frameRequiresExactForegroundPackageAndIsConsumedOnce() {
        AndroidScreenCaptureBridge.beginCapture("com.example.target")
        AndroidScreenCaptureBridge.publish(
            AndroidScreenCaptureFrame(
                bytes = byteArrayOf(1, 2, 3),
                mimeType = "image/jpeg",
                width = 100,
                height = 200,
                sourcePackage = "com.example.target",
                capturedAtEpochMs = 1_000L
            )
        )

        assertNull(
            AndroidScreenCaptureBridge.latestFresh(
                nowEpochMs = 1_100L,
                expectedPackage = "com.example.other"
            )
        )
        assertNotNull(
            AndroidScreenCaptureBridge.latestFresh(
                nowEpochMs = 1_100L,
                expectedPackage = "com.example.target"
            )
        )
        assertNotNull(
            AndroidScreenCaptureBridge.consumeFresh(
                nowEpochMs = 1_100L,
                expectedPackage = "com.example.target"
            )
        )
        assertFalse(AndroidScreenCaptureBridge.snapshot(nowEpochMs = 1_100L).freshFrameReady)
        assertNull(AndroidScreenCaptureBridge.consumeFresh(nowEpochMs = 1_100L, expectedPackage = "com.example.target"))
    }

    @Test
    fun staleFrameExpiresInsteadOfBeingReused() {
        AndroidScreenCaptureBridge.beginCapture("com.example.target")
        AndroidScreenCaptureBridge.publish(
            AndroidScreenCaptureFrame(
                bytes = byteArrayOf(9),
                mimeType = "image/jpeg",
                width = 10,
                height = 10,
                sourcePackage = "com.example.target",
                capturedAtEpochMs = 5_000L
            )
        )

        val now = 5_000L + AndroidScreenCaptureBridge.FRAME_TTL_MS + 1L
        assertNull(AndroidScreenCaptureBridge.latestFresh(nowEpochMs = now, expectedPackage = "com.example.target"))
        assertEquals(ScreenCaptureState.IDLE, AndroidScreenCaptureBridge.snapshot(nowEpochMs = now).state)
    }

    @Test
    fun deniedCaptureNeverCreatesAReusableGrant() {
        AndroidScreenCaptureBridge.beginCapture("com.example.target")
        AndroidScreenCaptureBridge.denied()

        val snapshot = AndroidScreenCaptureBridge.snapshot()
        assertEquals(ScreenCaptureState.DENIED, snapshot.state)
        assertFalse(snapshot.freshFrameReady)
        assertNull(AndroidScreenCaptureBridge.latestFresh(expectedPackage = "com.example.target"))
    }
}
