package com.veltrix.ultron.platform

enum class ScreenCaptureState { IDLE, CAPTURING, READY, DENIED, UNAVAILABLE, ERROR }

data class AndroidScreenCaptureFrame(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sourcePackage: String?,
    val capturedAtEpochMs: Long = System.currentTimeMillis()
)

data class ScreenCaptureSnapshot(
    val state: ScreenCaptureState,
    val freshFrameReady: Boolean,
    val sourcePackage: String?,
    val detail: String?
)

/**
 * Process-local, RAM-only one-shot screen frame bridge.
 *
 * MediaProjection consent is session-scoped on modern Android, so this object does not
 * model screenshot access as a persistent permission. Frames expire quickly and are never
 * written to disk, evidence, logs, or the remote bridge by this component.
 */
object AndroidScreenCaptureBridge {
    const val FRAME_TTL_MS = 30_000L

    private var state = ScreenCaptureState.IDLE
    private var frame: AndroidScreenCaptureFrame? = null
    private var sourcePackage: String? = null
    private var detail: String? = null

    @Synchronized
    fun beginCapture(sourcePackage: String?) {
        this.sourcePackage = sourcePackage?.trim()?.takeIf(String::isNotEmpty)
        frame = null
        detail = null
        state = ScreenCaptureState.CAPTURING
    }

    @Synchronized
    fun publish(captured: AndroidScreenCaptureFrame) {
        frame = captured.copy(bytes = captured.bytes.copyOf())
        sourcePackage = captured.sourcePackage
        detail = null
        state = ScreenCaptureState.READY
    }

    @Synchronized
    fun denied() {
        frame = null
        detail = "User did not grant this capture session"
        state = ScreenCaptureState.DENIED
    }

    @Synchronized
    fun unavailable(reason: String) {
        frame = null
        detail = reason.take(180)
        state = ScreenCaptureState.UNAVAILABLE
    }

    @Synchronized
    fun error(reason: String) {
        frame = null
        detail = reason.take(180)
        state = ScreenCaptureState.ERROR
    }

    @Synchronized
    fun latestFresh(
        nowEpochMs: Long = System.currentTimeMillis(),
        expectedPackage: String? = null
    ): AndroidScreenCaptureFrame? {
        expireIfNeeded(nowEpochMs)
        val current = frame ?: return null
        val expected = expectedPackage?.trim()?.takeIf(String::isNotEmpty)
        if (expected != null && current.sourcePackage != expected) return null
        return current.copy(bytes = current.bytes.copyOf())
    }

    @Synchronized
    fun consumeFresh(
        nowEpochMs: Long = System.currentTimeMillis(),
        expectedPackage: String? = null
    ): AndroidScreenCaptureFrame? {
        val current = latestFresh(nowEpochMs, expectedPackage) ?: return null
        frame = null
        sourcePackage = null
        detail = null
        state = ScreenCaptureState.IDLE
        return current
    }

    @Synchronized
    fun snapshot(nowEpochMs: Long = System.currentTimeMillis()): ScreenCaptureSnapshot {
        expireIfNeeded(nowEpochMs)
        return ScreenCaptureSnapshot(
            state = state,
            freshFrameReady = frame != null && state == ScreenCaptureState.READY,
            sourcePackage = sourcePackage,
            detail = detail
        )
    }

    @Synchronized
    fun clear() {
        frame = null
        sourcePackage = null
        detail = null
        state = ScreenCaptureState.IDLE
    }

    private fun expireIfNeeded(nowEpochMs: Long) {
        val current = frame ?: return
        if (nowEpochMs - current.capturedAtEpochMs <= FRAME_TTL_MS) return
        frame = null
        sourcePackage = null
        detail = "One-shot frame expired"
        state = ScreenCaptureState.IDLE
    }
}
