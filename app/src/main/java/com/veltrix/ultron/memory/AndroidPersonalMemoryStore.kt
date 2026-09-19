package com.veltrix.ultron.memory

import android.content.Context
import com.veltrix.ultron.planner.AndroidOwnerPlannerPermissionStore

/** Canonical Android construction boundary: encrypted persistence is always policy-bound. */
object AndroidPersonalMemoryStore {
    fun create(
        context: Context,
        ownerPrincipalId: String = DEFAULT_OWNER_PRINCIPAL_ID,
        deviceId: String = DEFAULT_DEVICE_ID
    ): PolicyBoundPersonalMemoryStore {
        val appContext = context.applicationContext
        return PolicyBoundPersonalMemoryStore(
            delegate = AndroidEncryptedPersonalMemoryStore(appContext),
            privacy = PersonalMemoryPrivacyFirewall(
                ownerPermissions = AndroidOwnerPlannerPermissionStore(appContext),
                ownerPrincipalId = ownerPrincipalId,
                deviceId = deviceId
            )
        )
    }

    const val DEFAULT_OWNER_PRINCIPAL_ID = "owner"
    const val DEFAULT_DEVICE_ID = "android-local"
}
