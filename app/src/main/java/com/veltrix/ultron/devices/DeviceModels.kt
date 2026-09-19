package com.veltrix.ultron.devices

import java.time.Instant
import java.util.UUID

enum class DeviceKind { PHONE, DESKTOP, CLOUD }
enum class DevicePlatform { ANDROID, WINDOWS, MACOS, LINUX, WEB, UNKNOWN }
enum class DevicePresence { ONLINE, OFFLINE, DEGRADED }

enum class ControlProfile {
    /** Observation only. No mutating device actions. */
    READ_ONLY,

    /** Every mutating action is individually approved. */
    ASK_EACH_ACTION,

    /**
     * Use every capability the owner has already granted at the OS/device layer.
     * This profile never bypasses OS security, PIN, biometric, CAPTCHA, or a capability
     * that the owner has not granted.
     */
    MAX_APPROVED
}

enum class DeviceCapability {
    /** Semantic Accessibility observation. This never implies screenshot consent. */
    SCREEN_OBSERVE,

    /** One-session OS-approved MediaProjection capture. Never a durable grant. */
    SCREEN_CAPTURE,
    UI_CLICK,
    UI_TYPE,
    UI_SCROLL,
    UI_GESTURE,
    OPEN_APP,
    APP_SWITCH,
    NOTIFICATION_READ,
    FILE_READ,
    FILE_WRITE,
    FILE_UPLOAD,
    FILE_DOWNLOAD,
    BROWSER_NAVIGATE,
    CAMERA_CAPTURE,
    MICROPHONE_CAPTURE,
    CLIPBOARD_READ,
    CLIPBOARD_WRITE,
    WINDOW_CONTROL,
    KEYBOARD_SHORTCUT,
    DESKTOP_AUTOMATION,
    PHONE_AUTOMATION,
    BACKGROUND_TASKS
}

data class DeviceDescriptor(
    val id: String = UUID.randomUUID().toString(),
    val ownerPrincipalId: String,
    val kind: DeviceKind,
    val platform: DevicePlatform,
    val displayName: String,
    val availableCapabilities: Set<DeviceCapability>,
    val grantedCapabilities: Set<DeviceCapability> = emptySet(),
    val controlProfile: ControlProfile = ControlProfile.ASK_EACH_ACTION,
    val presence: DevicePresence = DevicePresence.OFFLINE,
    val enrolledAt: Instant = Instant.now(),
    val lastSeenAt: Instant? = null
) {
    /** Capabilities ULTRON may actually use right now. */
    val effectiveCapabilities: Set<DeviceCapability>
        get() = availableCapabilities intersect grantedCapabilities

    fun supports(required: Set<DeviceCapability>): Boolean =
        required.all(effectiveCapabilities::contains)
}

data class DeviceEnrollmentChallenge(
    val id: String = UUID.randomUUID().toString(),
    val ownerPrincipalId: String,
    val targetKind: DeviceKind,
    val targetPlatform: DevicePlatform,
    val expiresAt: Instant,
    val nonce: String
)

data class DeviceControlRequest(
    val deviceId: String,
    val requestedProfile: ControlProfile,
    val requestedByPrincipalId: String,
    val reason: String,
    val createdAt: Instant = Instant.now()
)

data class DeviceControlReceipt(
    val deviceId: String,
    val profile: ControlProfile,
    val ownerApprovalRequired: Boolean,
    val message: String
)
