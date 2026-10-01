package com.veltrix.ultron.car

enum class CarStartupTrigger {
    BOOT_COMPLETED,
    LOCKED_BOOT_COMPLETED,
    ACC_ON,
    ASSISTANT_READY,
    USER_VISIBLE
}

/**
 * Central startup policy for the Magicar head-unit runtime.
 *
 * Android 15+ blocks BOOT_COMPLETED from launching microphone foreground
 * services. On those versions the selected VoiceInteractionService owns the
 * persistent runtime startup instead. ACC/user/assistant activation may still
 * start the runtime through their normal user/system-owned path.
 */
object CarRuntimeStartupPolicy {
    fun shouldStart(
        trigger: CarStartupTrigger,
        sdkInt: Int,
        assistantServiceActive: Boolean
    ): Boolean = when (trigger) {
        CarStartupTrigger.BOOT_COMPLETED,
        CarStartupTrigger.LOCKED_BOOT_COMPLETED -> sdkInt < 35

        CarStartupTrigger.ACC_ON -> sdkInt < 35 || assistantServiceActive
        CarStartupTrigger.ASSISTANT_READY,
        CarStartupTrigger.USER_VISIBLE -> true
    }
}
