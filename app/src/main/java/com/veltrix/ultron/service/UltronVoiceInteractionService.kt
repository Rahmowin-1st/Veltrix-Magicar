package com.veltrix.ultron.service

import android.content.Intent
import android.service.voice.VoiceInteractionService
import com.veltrix.ultron.car.CarRuntimeService
import com.veltrix.ultron.remote.UltronAgentRuntime

/**
 * System-owned assistant lifecycle.
 *
 * On modern Android this is the reliable startup owner for the persistent
 * Magicar runtime; BOOT_COMPLETED is not allowed to launch a microphone
 * foreground service on Android 15+.
 */
class UltronVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        UltronAgentRuntime.initialize(applicationContext)
        runCatching { CarRuntimeService.start(applicationContext) }
    }

    override fun onShutdown() {
        UltronAgentRuntime.stopBackgroundLoop()
        runCatching {
            stopService(Intent(applicationContext, CarRuntimeService::class.java))
        }
        super.onShutdown()
    }
}
