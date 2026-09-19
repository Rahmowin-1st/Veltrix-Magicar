package com.veltrix.ultron.executor

enum class AccessibilityActionType {
    OPEN_APP,
    CLICK_TEXT,
    SET_TEXT_BY_VIEW_ID,
    SET_TEXT_SEMANTIC,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    GLOBAL_BACK,
    GLOBAL_HOME,
    GLOBAL_RECENTS,
    TAP,
    DOUBLE_TAP,
    LONG_PRESS,
    SWIPE,
    DRAG
}

data class AccessibilityAction(
    val type: AccessibilityActionType,
    val packageName: String? = null,
    val text: String? = null,
    val viewId: String? = null,
    val value: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val x2: Float? = null,
    val y2: Float? = null,
    val durationMs: Long? = null
)

data class AccessibilityActionResult(
    val accepted: Boolean,
    val action: AccessibilityActionType,
    val message: String,
    val evidence: Map<String, String> = emptyMap()
)
