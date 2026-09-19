package com.veltrix.ultron.privacy

import android.content.Context
import com.veltrix.ultron.planner.AndroidOwnerPlannerPermissionStore

/**
 * Android construction boundary for the typed privacy broker.
 * The same encrypted owner policy store used by planner/observation/notification/
 * conversation firewalls is reused here so there is one authoritative local policy.
 */
object AndroidOwnerPrivacyPolicyBroker {
    fun create(
        context: Context,
        ownerPrincipalId: String = DEFAULT_OWNER_PRINCIPAL_ID,
        deviceId: String = DEFAULT_DEVICE_ID
    ): OwnerPrivacyPolicyBroker = OwnerPrivacyPolicyBroker(
        ownerPrincipalId = ownerPrincipalId,
        deviceId = deviceId,
        store = AndroidOwnerPlannerPermissionStore(context.applicationContext)
    )

    const val DEFAULT_OWNER_PRINCIPAL_ID = "owner"
    const val DEFAULT_DEVICE_ID = "android-local"
}
