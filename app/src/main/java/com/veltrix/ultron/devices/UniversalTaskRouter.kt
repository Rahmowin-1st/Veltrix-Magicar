package com.veltrix.ultron.devices

import com.veltrix.ultron.agents.Principal

enum class DeviceTargetPreference { AUTO, PHONE, DESKTOP, CLOUD }
enum class DeviceRouteState { READY, WAITING_FOR_DEVICE, DENIED }

data class AuthenticatedExecutionContext(
    /** Principal identity established by transport/auth middleware. */
    val principal: Principal,
    /** Device owner this principal is currently delegated to act for. */
    val deviceOwnerPrincipalId: String,
    /** Optional hard allow-list. Empty means all devices owned by deviceOwnerPrincipalId. */
    val allowedDeviceIds: Set<String> = emptySet()
)

data class UniversalTaskIntent(
    val objective: String,
    val requiredCapabilities: Set<DeviceCapability> = emptySet(),
    val targetPreference: DeviceTargetPreference = DeviceTargetPreference.AUTO,
    val targetDeviceId: String? = null
)

data class DeviceRoute(
    val state: DeviceRouteState,
    val device: DeviceDescriptor? = null,
    val message: String
)

class UniversalTaskRouter(private val registry: DeviceRegistry) {
    fun route(
        auth: AuthenticatedExecutionContext,
        task: UniversalTaskIntent
    ): DeviceRoute {
        require(task.objective.isNotBlank()) { "Task objective is required" }

        task.targetDeviceId?.let { explicitId ->
            val device = registry.get(explicitId)
                ?: return DeviceRoute(DeviceRouteState.DENIED, message = "Unknown target device")
            if (device.ownerPrincipalId != auth.deviceOwnerPrincipalId) {
                return DeviceRoute(DeviceRouteState.DENIED, message = "Target device is outside delegated owner scope")
            }
            if (auth.allowedDeviceIds.isNotEmpty() && explicitId !in auth.allowedDeviceIds) {
                return DeviceRoute(DeviceRouteState.DENIED, message = "Target device is outside authenticated device scope")
            }
            if (!device.supports(task.requiredCapabilities)) {
                return DeviceRoute(DeviceRouteState.DENIED, message = "Target device lacks granted capabilities")
            }
            if (device.presence != DevicePresence.ONLINE) {
                return DeviceRoute(DeviceRouteState.WAITING_FOR_DEVICE, device, "Target device is offline")
            }
            return DeviceRoute(DeviceRouteState.READY, device, "Target device ready")
        }

        val preferredKind = when (task.targetPreference) {
            DeviceTargetPreference.AUTO -> null
            DeviceTargetPreference.PHONE -> DeviceKind.PHONE
            DeviceTargetPreference.DESKTOP -> DeviceKind.DESKTOP
            DeviceTargetPreference.CLOUD -> DeviceKind.CLOUD
        }

        val candidates = registry.eligibleDevices(
            ownerPrincipalId = auth.deviceOwnerPrincipalId,
            required = task.requiredCapabilities,
            preferredKind = preferredKind
        ).filter { auth.allowedDeviceIds.isEmpty() || it.id in auth.allowedDeviceIds }

        val selected = candidates.firstOrNull()
        if (selected != null) {
            return DeviceRoute(DeviceRouteState.READY, selected, "Routed to ${selected.displayName}")
        }

        val known = registry.listForOwner(auth.deviceOwnerPrincipalId)
            .filter { preferredKind == null || it.kind == preferredKind }
            .filter { auth.allowedDeviceIds.isEmpty() || it.id in auth.allowedDeviceIds }
            .firstOrNull { it.supports(task.requiredCapabilities) }

        return if (known != null) {
            DeviceRoute(DeviceRouteState.WAITING_FOR_DEVICE, known, "Matching device is currently offline")
        } else {
            DeviceRoute(DeviceRouteState.DENIED, message = "No delegated device has the required granted capabilities")
        }
    }
}
