package com.veltrix.ultron.remote

import android.content.Context
import android.os.Build
import com.veltrix.ultron.BuildConfig
import com.veltrix.ultron.devices.DeviceCapability
import com.veltrix.ultron.platform.AndroidCapabilityController

private val BUILD_SHA_PATTERN = Regex("[0-9a-f]{40}")

internal fun normalizeBridgeBuildSha(raw: String): String? {
    val normalized = raw.trim().lowercase()
    return normalized.takeIf { BUILD_SHA_PATTERN.matches(it) }
}

internal fun bridgePlatformLabel(sdkInt: Int, rawBuildSha: String): String {
    val base = "ANDROID-$sdkInt"
    val buildSha = normalizeBridgeBuildSha(rawBuildSha) ?: return base
    return "$base;build=$buildSha"
}

class AndroidBridgeCapabilitySnapshotFactory(context: Context) {
    private val controller = AndroidCapabilityController(context.applicationContext)

    fun snapshot(): BridgeCapabilitySnapshot {
        val status = controller.status()
        val available = linkedSetOf(
            DeviceCapability.SCREEN_OBSERVE,
            DeviceCapability.OPEN_APP,
            DeviceCapability.UI_CLICK,
            DeviceCapability.UI_TYPE,
            DeviceCapability.UI_SCROLL,
            DeviceCapability.UI_GESTURE,
            DeviceCapability.PHONE_AUTOMATION,
            DeviceCapability.NOTIFICATION_READ,
            DeviceCapability.BACKGROUND_TASKS
        )
        val granted = linkedSetOf<DeviceCapability>()
        if (status.executorConnected) granted += DeviceCapability.SCREEN_OBSERVE
        if (status.accessibilityEnabled && status.executorConnected) {
            granted += setOf(
                DeviceCapability.OPEN_APP,
                DeviceCapability.UI_CLICK,
                DeviceCapability.UI_TYPE,
                DeviceCapability.UI_SCROLL,
                DeviceCapability.UI_GESTURE,
                DeviceCapability.PHONE_AUTOMATION
            )
        }
        if (status.notificationAccessEnabled) granted += DeviceCapability.NOTIFICATION_READ
        if (status.assistantServiceActive) granted += DeviceCapability.BACKGROUND_TASKS

        return BridgeCapabilitySnapshot(
            platform = bridgePlatformLabel(Build.VERSION.SDK_INT, BuildConfig.VELTRIX_BUILD_SHA),
            availableCapabilities = available.mapTo(linkedSetOf()) { it.name },
            grantedCapabilities = granted.mapTo(linkedSetOf()) { it.name }
        )
    }
}
