package com.veltrix.ultron.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CarBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            FYT_ACC_ON -> runCatching { CarRuntimeService.start(context.applicationContext) }
        }
    }

    companion object {
        const val FYT_ACC_ON = "com.fyt.boot.ACCON"
    }
}
