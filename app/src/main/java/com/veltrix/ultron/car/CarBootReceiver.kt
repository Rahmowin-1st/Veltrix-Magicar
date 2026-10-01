package com.veltrix.ultron.car

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.voice.VoiceInteractionService
import com.veltrix.ultron.service.UltronVoiceInteractionService

class CarBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val trigger = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> CarStartupTrigger.BOOT_COMPLETED
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> CarStartupTrigger.LOCKED_BOOT_COMPLETED
            FYT_ACC_ON -> CarStartupTrigger.ACC_ON
            else -> return
        }
        val assistantActive = VoiceInteractionService.isActiveService(
            context,
            ComponentName(context, UltronVoiceInteractionService::class.java)
        )
        if (
            CarRuntimeStartupPolicy.shouldStart(
                trigger = trigger,
                sdkInt = Build.VERSION.SDK_INT,
                assistantServiceActive = assistantActive
            )
        ) {
            runCatching { CarRuntimeService.start(context.applicationContext) }
        }
    }

    companion object {
        const val FYT_ACC_ON = "com.fyt.boot.ACCON"
    }
}
