package com.veltrix.ultron.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarRuntimeStartupPolicyTest {
    @Test
    fun android15BootDoesNotLaunchMicrophoneForegroundRuntime() {
        assertFalse(
            CarRuntimeStartupPolicy.shouldStart(
                CarStartupTrigger.BOOT_COMPLETED,
                sdkInt = 35,
                assistantServiceActive = true
            )
        )
    }

    @Test
    fun assistantReadyAlwaysOwnsRuntimeStartup() {
        assertTrue(
            CarRuntimeStartupPolicy.shouldStart(
                CarStartupTrigger.ASSISTANT_READY,
                sdkInt = 36,
                assistantServiceActive = true
            )
        )
    }

    @Test
    fun accOnOnModernAndroidRequiresActiveAssistantService() {
        assertFalse(CarRuntimeStartupPolicy.shouldStart(CarStartupTrigger.ACC_ON, 36, false))
        assertTrue(CarRuntimeStartupPolicy.shouldStart(CarStartupTrigger.ACC_ON, 36, true))
    }
}
