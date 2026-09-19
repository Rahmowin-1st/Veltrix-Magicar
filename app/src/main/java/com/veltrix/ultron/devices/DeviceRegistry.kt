package com.veltrix.ultron.devices

import java.time.Instant

class DeviceRegistry {
    private val devices = linkedMapOf<String, DeviceDescriptor>()

    @Synchronized
    fun enroll(device: DeviceDescriptor): DeviceDescriptor {
        require(device.ownerPrincipalId.isNotBlank()) { "Device owner principal is required" }
        devices[device.id] = device
        return device
    }

    @Synchronized
    fun get(deviceId: String): DeviceDescriptor? = devices[deviceId]

    @Synchronized
    fun listForOwner(ownerPrincipalId: String): List<DeviceDescriptor> =
        devices.values.filter { it.ownerPrincipalId == ownerPrincipalId }

    @Synchronized
    fun updatePresence(
        deviceId: String,
        presence: DevicePresence,
        now: Instant = Instant.now()
    ): DeviceDescriptor = update(deviceId) {
        it.copy(presence = presence, lastSeenAt = now)
    }

    @Synchronized
    fun updateGrantedCapabilities(
        authenticatedPrincipalId: String,
        deviceId: String,
        granted: Set<DeviceCapability>
    ): DeviceDescriptor = updateOwned(authenticatedPrincipalId, deviceId) { current ->
        current.copy(grantedCapabilities = granted intersect current.availableCapabilities)
    }

    /**
     * Only the owner can directly change a device's control profile.
     * Remote agents may request a profile change, but cannot apply it themselves.
     */
    @Synchronized
    fun setControlProfile(
        authenticatedPrincipalId: String,
        deviceId: String,
        profile: ControlProfile
    ): DeviceDescriptor = updateOwned(authenticatedPrincipalId, deviceId) {
        it.copy(controlProfile = profile)
    }

    @Synchronized
    fun requestControlProfile(
        authenticatedPrincipalId: String,
        deviceId: String,
        profile: ControlProfile,
        reason: String
    ): DeviceControlReceipt {
        val device = requireNotNull(devices[deviceId]) { "Unknown device: $deviceId" }
        val isOwner = device.ownerPrincipalId == authenticatedPrincipalId
        return if (isOwner) {
            val updated = setControlProfile(authenticatedPrincipalId, deviceId, profile)
            DeviceControlReceipt(
                deviceId = deviceId,
                profile = updated.controlProfile,
                ownerApprovalRequired = false,
                message = "Control profile updated by owner"
            )
        } else {
            DeviceControlReceipt(
                deviceId = deviceId,
                profile = device.controlProfile,
                ownerApprovalRequired = true,
                message = "Owner approval required; remote principals cannot elevate device control"
            )
        }
    }

    @Synchronized
    fun eligibleDevices(
        ownerPrincipalId: String,
        required: Set<DeviceCapability>,
        preferredKind: DeviceKind? = null
    ): List<DeviceDescriptor> = devices.values
        .asSequence()
        .filter { it.ownerPrincipalId == ownerPrincipalId }
        .filter { it.presence == DevicePresence.ONLINE }
        .filter { preferredKind == null || it.kind == preferredKind }
        .filter { it.controlProfile != ControlProfile.READ_ONLY || required.isEmpty() }
        .filter { it.supports(required) }
        .sortedWith(compareBy<DeviceDescriptor> { it.kind.ordinal }.thenBy { it.displayName })
        .toList()

    private fun updateOwned(
        authenticatedPrincipalId: String,
        deviceId: String,
        transform: (DeviceDescriptor) -> DeviceDescriptor
    ): DeviceDescriptor {
        val current = requireNotNull(devices[deviceId]) { "Unknown device: $deviceId" }
        require(current.ownerPrincipalId == authenticatedPrincipalId) {
            "Only the device owner can mutate this setting"
        }
        val next = transform(current)
        devices[deviceId] = next
        return next
    }

    private fun update(
        deviceId: String,
        transform: (DeviceDescriptor) -> DeviceDescriptor
    ): DeviceDescriptor {
        val current = requireNotNull(devices[deviceId]) { "Unknown device: $deviceId" }
        val next = transform(current)
        devices[deviceId] = next
        return next
    }
}
