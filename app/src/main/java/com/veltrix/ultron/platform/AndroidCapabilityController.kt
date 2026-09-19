package com.veltrix.ultron.platform

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import com.veltrix.ultron.service.ScreenCaptureConsentActivity
import com.veltrix.ultron.service.UltronNotificationListenerService
import com.veltrix.ultron.service.UltronOverlayService
import com.veltrix.ultron.service.UltronVoiceInteractionService

/**
 * Centralized Android capability discovery and user-consent entry points.
 *
 * This class never enables privileged capabilities itself. It only reports
 * state and returns system-owned consent/settings intents.
 */
class AndroidCapabilityController(private val context: Context) {

    data class Status(
        val assistantRoleAvailable: Boolean,
        val assistantRoleHeld: Boolean,
        val assistantServiceActive: Boolean,
        val overlayAllowed: Boolean,
        val accessibilityEnabled: Boolean,
        val executorConnected: Boolean,
        val notificationAccessEnabled: Boolean,
        val notificationPermissionGranted: Boolean,
        val microphonePermissionGranted: Boolean,
        val screenCaptureState: ScreenCaptureState,
        val freshScreenFrameReady: Boolean
    ) {
        /** Notification-listener access is optional context, not a MAX_APPROVED prerequisite. */
        val maxApprovedReady: Boolean
            get() = (!assistantRoleAvailable || assistantRoleHeld) &&
                overlayAllowed &&
                accessibilityEnabled &&
                notificationPermissionGranted &&
                microphonePermissionGranted
    }

    data class GuidedSetupAction(
        val label: String,
        val intent: Intent
    )

    fun status(): Status {
        val capture = AndroidScreenCaptureBridge.snapshot()
        return Status(
            assistantRoleAvailable = isAssistantRoleAvailable(),
            assistantRoleHeld = isAssistantRoleHeld(),
            assistantServiceActive = VoiceInteractionService.isActiveService(
                context,
                ComponentName(context, UltronVoiceInteractionService::class.java)
            ),
            overlayAllowed = Settings.canDrawOverlays(context),
            accessibilityEnabled = isAccessibilityEnabled(),
            executorConnected = AndroidExecutorBridge.isConnected(),
            notificationAccessEnabled = NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName),
            notificationPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            microphonePermissionGranted =
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            screenCaptureState = capture.state,
            freshScreenFrameReady = capture.freshFrameReady
        )
    }

    /**
     * Returns the next required system-owned special-access screen. Optional
     * notification-listener context remains available as a separate manual action.
     * Every returned Intent is resolved first and falls back to a safe Settings UI.
     */
    fun nextMaxApprovedSetupAction(current: Status = status()): GuidedSetupAction? {
        if (current.assistantRoleAvailable && !current.assistantRoleHeld) {
            assistantRoleRequestIntent()?.let { return GuidedSetupAction("Select Veltrix assistant", it) }
        }
        if (!current.accessibilityEnabled) {
            return GuidedSetupAction("Enable Veltrix Executor", accessibilitySettingsIntent())
        }
        if (!current.overlayAllowed) {
            return GuidedSetupAction("Allow Veltrix overlay", overlaySettingsIntent())
        }
        return null
    }

    fun missingRuntimePermissions(current: Status = status()): Array<String> = buildList {
        if (!current.microphonePermissionGranted) add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !current.notificationPermissionGranted) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun assistantRoleRequestIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val roles = context.getSystemService(RoleManager::class.java) ?: return null
        if (!roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return null
        if (roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) return null
        return roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            .takeIf(::isResolvable)
    }

    fun overlaySettingsIntent(): Intent = firstResolvable(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        appDetailsIntent(),
        Intent(Settings.ACTION_SETTINGS)
    )

    fun showOverlay(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        context.startService(
            Intent(context, UltronOverlayService::class.java)
                .setAction(UltronOverlayService.ACTION_SHOW)
        )
        return true
    }

    fun hideOverlay() {
        context.startService(
            Intent(context, UltronOverlayService::class.java)
                .setAction(UltronOverlayService.ACTION_HIDE)
        )
    }

    fun screenCaptureConsentIntent(): Intent {
        val sourcePackage = AndroidExecutorBridge.observer.observe().packageName
        return ScreenCaptureConsentActivity.createIntent(context, sourcePackage)
    }

    fun accessibilitySettingsIntent(): Intent = firstResolvable(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        appDetailsIntent(),
        Intent(Settings.ACTION_SETTINGS)
    )

    fun notificationAccessSettingsIntent(): Intent {
        val listener = ComponentName(context, UltronNotificationListenerService::class.java)
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
                )
            }
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        return firstResolvable(*candidates.toTypedArray())
    }

    private fun isAssistantRoleAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roles = context.getSystemService(RoleManager::class.java) ?: return false
        if (!roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return false
        if (roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) return true
        return roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT).let(::isResolvable)
    }

    private fun isAssistantRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roles = context.getSystemService(RoleManager::class.java) ?: return false
        return roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val service = info.resolveInfo?.serviceInfo ?: return@any false
                service.packageName == context.packageName &&
                    service.name == "com.veltrix.ultron.service.UltronAccessibilityService"
            }
    }

    private fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )

    private fun firstResolvable(vararg candidates: Intent): Intent =
        candidates.firstOrNull(::isResolvable) ?: Intent(Settings.ACTION_SETTINGS)

    private fun isResolvable(intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null
}
