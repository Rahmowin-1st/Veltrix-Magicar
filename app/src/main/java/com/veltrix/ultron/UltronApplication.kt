package com.veltrix.ultron

import android.app.Application
import com.veltrix.ultron.car.CarRuntimeService
import com.veltrix.ultron.platform.AndroidAuditTrail
import com.veltrix.ultron.platform.AndroidBrowserIntentBridge
import com.veltrix.ultron.platform.AndroidPlannerAuditAdapter
import com.veltrix.ultron.platform.AndroidUndoJournal
import com.veltrix.ultron.remote.UltronAgentRuntime
import com.veltrix.ultron.remote.restoreRemoteBridgeLoopIfNeeded

class UltronApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { CarRuntimeService.start(this) }
        AndroidBrowserIntentBridge.initialize(this)
        AndroidAuditTrail.initialize(this)
        AndroidPlannerAuditAdapter.initialize()
        AndroidUndoJournal.initialize(this)

        UltronAgentRuntime.initialize(this)
        restoreRemoteBridgeLoopIfNeeded(
            configuredCheck = { UltronAgentRuntime.isConfigured() },
            runningCheck = { UltronAgentRuntime.isBackgroundLoopRunning() },
            start = { UltronAgentRuntime.startBackgroundLoop() }
        )
    }
}
