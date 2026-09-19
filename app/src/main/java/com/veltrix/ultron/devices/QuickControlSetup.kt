package com.veltrix.ultron.devices

enum class SetupStepKind { LOCAL_APP, SYSTEM_CONSENT, DEVICE_PAIRING, BACKGROUND_SERVICE }

data class QuickSetupStep(
    val id: String,
    val title: String,
    val kind: SetupStepKind,
    val required: Boolean,
    val capabilityHints: Set<DeviceCapability> = emptySet()
)

data class QuickSetupPlan(
    val primaryActionLabel: String,
    val finalProfile: ControlProfile,
    val steps: List<QuickSetupStep>
)

/**
 * UX contract for the "Connect + Enable Max Approved" flow.
 * ULTRON can collapse these into one guided flow, while OS-owned consent screens
 * still require the user's own confirmation where the platform mandates it.
 */
object QuickControlSetup {
    fun plan(kind: DeviceKind, platform: DevicePlatform): QuickSetupPlan = when (kind) {
        DeviceKind.PHONE -> phonePlan(platform)
        DeviceKind.DESKTOP -> desktopPlan(platform)
        DeviceKind.CLOUD -> cloudPlan()
    }

    private fun phonePlan(platform: DevicePlatform): QuickSetupPlan = QuickSetupPlan(
        primaryActionLabel = "Enable Max Approved Control",
        finalProfile = ControlProfile.MAX_APPROVED,
        steps = listOf(
            QuickSetupStep(
                id = "pair-device",
                title = "Pair this phone with ULTRON",
                kind = SetupStepKind.DEVICE_PAIRING,
                required = true
            ),
            QuickSetupStep(
                id = "assistant",
                title = "Select Veltrix as assistant",
                kind = SetupStepKind.SYSTEM_CONSENT,
                required = platform == DevicePlatform.ANDROID,
                capabilityHints = setOf(DeviceCapability.PHONE_AUTOMATION)
            ),
            QuickSetupStep(
                id = "accessibility",
                title = "Enable UI executor",
                kind = SetupStepKind.SYSTEM_CONSENT,
                required = platform == DevicePlatform.ANDROID,
                capabilityHints = setOf(
                    DeviceCapability.SCREEN_OBSERVE,
                    DeviceCapability.UI_CLICK,
                    DeviceCapability.UI_TYPE,
                    DeviceCapability.UI_SCROLL,
                    DeviceCapability.UI_GESTURE,
                    DeviceCapability.OPEN_APP,
                    DeviceCapability.APP_SWITCH,
                    DeviceCapability.PHONE_AUTOMATION
                )
            ),
            QuickSetupStep(
                id = "overlay",
                title = "Allow command overlay",
                kind = SetupStepKind.SYSTEM_CONSENT,
                required = platform == DevicePlatform.ANDROID
            ),
            QuickSetupStep(
                id = "notification-context",
                title = "Allow notification context",
                kind = SetupStepKind.SYSTEM_CONSENT,
                required = false,
                capabilityHints = setOf(DeviceCapability.NOTIFICATION_READ)
            ),
            QuickSetupStep(
                id = "background",
                title = "Keep ULTRON available for background missions",
                kind = SetupStepKind.BACKGROUND_SERVICE,
                required = true,
                capabilityHints = setOf(DeviceCapability.BACKGROUND_TASKS)
            )
        )
    )

    private fun desktopPlan(platform: DevicePlatform): QuickSetupPlan = QuickSetupPlan(
        primaryActionLabel = "Connect Desktop + Max Control",
        finalProfile = ControlProfile.MAX_APPROVED,
        steps = listOf(
            QuickSetupStep(
                id = "pair-device",
                title = "Pair this desktop with ULTRON",
                kind = SetupStepKind.DEVICE_PAIRING,
                required = true
            ),
            QuickSetupStep(
                id = "install-companion",
                title = "Start ULTRON desktop companion",
                kind = SetupStepKind.LOCAL_APP,
                required = true,
                capabilityHints = setOf(DeviceCapability.DESKTOP_AUTOMATION)
            ),
            QuickSetupStep(
                id = "screen-input-consent",
                title = "Allow screen observation and input control",
                kind = SetupStepKind.SYSTEM_CONSENT,
                required = true,
                capabilityHints = setOf(
                    DeviceCapability.SCREEN_OBSERVE,
                    DeviceCapability.UI_CLICK,
                    DeviceCapability.UI_TYPE,
                    DeviceCapability.UI_SCROLL,
                    DeviceCapability.WINDOW_CONTROL,
                    DeviceCapability.KEYBOARD_SHORTCUT
                )
            ),
            QuickSetupStep(
                id = "files",
                title = "Choose file access scope",
                kind = SetupStepKind.SYSTEM_CONSENT,
                required = false,
                capabilityHints = setOf(
                    DeviceCapability.FILE_READ,
                    DeviceCapability.FILE_WRITE,
                    DeviceCapability.FILE_UPLOAD,
                    DeviceCapability.FILE_DOWNLOAD
                )
            ),
            QuickSetupStep(
                id = "background-service",
                title = "Run ULTRON companion in background",
                kind = SetupStepKind.BACKGROUND_SERVICE,
                required = true,
                capabilityHints = setOf(DeviceCapability.BACKGROUND_TASKS)
            )
        )
    )

    private fun cloudPlan(): QuickSetupPlan = QuickSetupPlan(
        primaryActionLabel = "Connect Cloud Executor",
        finalProfile = ControlProfile.MAX_APPROVED,
        steps = listOf(
            QuickSetupStep(
                id = "pair-cloud",
                title = "Authorize cloud executor",
                kind = SetupStepKind.DEVICE_PAIRING,
                required = true
            )
        )
    )
}
