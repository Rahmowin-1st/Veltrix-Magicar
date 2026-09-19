package com.veltrix.ultron.service

import android.service.voice.VoiceInteractionService
import com.veltrix.ultron.remote.UltronAgentRuntime

/** Lightweight process kept by Android while Veltrix is the selected assistant. */
class UltronVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        UltronAgentRuntime.initialize(applicationContext)
        // Car edition is deliberately pull-only: readiness never starts remote work.
        // A user wake/command opens a bounded session; idle mode performs no UI task.
    }

    override fun onShutdown() {
        UltronAgentRuntime.stopBackgroundLoop()
        super.onShutdown()
    }
}
