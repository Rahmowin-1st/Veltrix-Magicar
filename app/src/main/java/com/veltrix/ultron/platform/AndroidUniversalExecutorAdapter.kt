package com.veltrix.ultron.platform

import com.veltrix.ultron.devices.DeviceObservation
import com.veltrix.ultron.devices.DeviceSemanticNode
import com.veltrix.ultron.devices.UniversalAction
import com.veltrix.ultron.devices.UniversalActionResult
import com.veltrix.ultron.devices.UniversalActionType
import com.veltrix.ultron.devices.UniversalDeviceExecutorEndpoint
import com.veltrix.ultron.executor.AccessibilityAction
import com.veltrix.ultron.executor.AccessibilityActionType

/**
 * Adapter from the physical-device-verified Android executor bridge into the
 * protocol-neutral phone/desktop executor contract.
 */
class AndroidUniversalExecutorAdapter(
    override val deviceId: String
) : UniversalDeviceExecutorEndpoint {

    override fun observe(): DeviceObservation {
        val screen = AndroidExecutorBridge.observer.observe()
        return DeviceObservation(
            deviceId = deviceId,
            foregroundApp = screen.packageName,
            foregroundWindow = screen.className,
            visibleText = screen.visibleText,
            semanticNodes = screen.nodes.map { node ->
                DeviceSemanticNode(
                    left = node.bounds.left,
                    top = node.bounds.top,
                    right = node.bounds.right,
                    bottom = node.bounds.bottom,
                    text = node.text,
                    hint = node.hint,
                    contentDescription = node.contentDescription,
                    viewId = node.viewId,
                    className = node.className,
                    clickable = node.clickable,
                    editable = node.editable,
                    scrollable = node.scrollable,
                    enabled = node.enabled,
                    focused = node.focused
                )
            },
            screenFingerprint = screen.fingerprint
        )
    }

    override fun dispatch(action: UniversalAction): UniversalActionResult {
        if (action.type == UniversalActionType.BROWSER_NAVIGATE) {
            return AndroidBrowserIntentBridge.dispatch(action.target ?: action.value ?: action.text)
        }
        val androidAction = action.toAndroidAction()
            ?: return UniversalActionResult(
                accepted = false,
                action = action.type,
                message = "Universal action is not supported by Android adapter yet"
            )
        val result = AndroidExecutorBridge.transport.dispatch(androidAction)
        return UniversalActionResult(
            accepted = result.accepted,
            action = action.type,
            message = result.message,
            evidence = result.evidence
        )
    }

    private fun UniversalAction.toAndroidAction(): AccessibilityAction? = when (type) {
        UniversalActionType.OPEN_APP -> AccessibilityAction(
            type = AccessibilityActionType.OPEN_APP,
            packageName = target
        )
        UniversalActionType.CLICK -> AccessibilityAction(
            type = AccessibilityActionType.CLICK_TEXT,
            text = text ?: target
        )
        UniversalActionType.TYPE_TEXT -> AccessibilityAction(
            type = AccessibilityActionType.SET_TEXT_SEMANTIC,
            text = target,
            value = value ?: text.orEmpty()
        )
        UniversalActionType.SCROLL -> AccessibilityAction(AccessibilityActionType.SCROLL_FORWARD)
        UniversalActionType.TAP -> AccessibilityAction(
            type = when (metadata["gesture"]?.lowercase()) {
                "double_tap" -> AccessibilityActionType.DOUBLE_TAP
                "long_press" -> AccessibilityActionType.LONG_PRESS
                "swipe" -> AccessibilityActionType.SWIPE
                "drag", "drag_drop" -> AccessibilityActionType.DRAG
                else -> AccessibilityActionType.TAP
            },
            x = x,
            y = y,
            x2 = x2,
            y2 = y2,
            durationMs = durationMs
        )
        UniversalActionType.BACK -> AccessibilityAction(AccessibilityActionType.GLOBAL_BACK)
        UniversalActionType.HOME -> AccessibilityAction(AccessibilityActionType.GLOBAL_HOME)
        UniversalActionType.WINDOW_FOCUS,
        UniversalActionType.KEYBOARD_SHORTCUT,
        UniversalActionType.BROWSER_NAVIGATE,
        UniversalActionType.FILE_READ,
        UniversalActionType.FILE_WRITE,
        UniversalActionType.FILE_UPLOAD,
        UniversalActionType.FILE_DOWNLOAD -> null
    }
}
