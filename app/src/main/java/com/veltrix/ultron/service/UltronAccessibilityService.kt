package com.veltrix.ultron.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.veltrix.ultron.car.CarSessionRuntime
import com.veltrix.ultron.core.ScreenBounds
import com.veltrix.ultron.core.ScreenNodeObservation
import com.veltrix.ultron.core.ScreenObservation
import com.veltrix.ultron.executor.AccessibilityAction
import com.veltrix.ultron.executor.AccessibilityActionResult
import com.veltrix.ultron.executor.AccessibilityActionType
import com.veltrix.ultron.executor.SemanticTargetMatcher
import com.veltrix.ultron.platform.AndroidExecutorBridge
import com.veltrix.ultron.platform.AndroidExecutorEndpoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest

/**
 * Low-level Android UI executor.
 *
 * This service does not make permission or policy decisions. Callers must pass
 * through the Mission/Permission engine before invoking an action.
 */
class UltronAccessibilityService : AccessibilityService(), AndroidExecutorEndpoint {
    private val serviceHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        AndroidExecutorBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AndroidExecutorBridge.detach(this)
        super.onDestroy()
    }

    override fun snapshot(): ScreenObservation = onServiceThread(
        fallback = ScreenObservation(packageName = null),
        block = ::snapshotNow
    )

    override fun dispatch(action: AccessibilityAction): AccessibilityActionResult {
        if (!CarSessionRuntime.allowsUiMutation()) {
            return action.fail("Car session is idle; explicit user wake is required")
        }
        CarSessionRuntime.markExecuting()
        return onServiceThread(
            fallback = action.fail("Android executor service thread timed out"),
            block = { executeNow(action) }
        )
    }

    private fun snapshotNow(): ScreenObservation {
        val root = rootInActiveWindow
        val nodes = root.collectNodeObservations(limit = 180)
        return ScreenObservation(
            packageName = root?.packageName?.toString(),
            className = root?.className?.toString(),
            visibleText = root.collectVisibleText(limit = 120),
            nodes = nodes,
            fingerprint = screenFingerprint(root?.packageName?.toString(), root?.className?.toString(), nodes)
        )
    }

    private fun executeNow(action: AccessibilityAction): AccessibilityActionResult = when (action.type) {
        AccessibilityActionType.OPEN_APP -> openApp(action)
        AccessibilityActionType.CLICK_TEXT -> clickByText(action)
        AccessibilityActionType.SET_TEXT_BY_VIEW_ID -> setTextByViewId(action)
        AccessibilityActionType.SET_TEXT_SEMANTIC -> setTextSemantic(action)
        AccessibilityActionType.SCROLL_FORWARD -> scroll(action, forward = true)
        AccessibilityActionType.SCROLL_BACKWARD -> scroll(action, forward = false)
        AccessibilityActionType.GLOBAL_BACK -> globalAction(action, GLOBAL_ACTION_BACK, "Back")
        AccessibilityActionType.GLOBAL_HOME -> globalAction(action, GLOBAL_ACTION_HOME, "Home")
        AccessibilityActionType.GLOBAL_RECENTS -> globalAction(action, GLOBAL_ACTION_RECENTS, "Recents")
        AccessibilityActionType.TAP -> pointGesture(action, 60L, "Tap")
        AccessibilityActionType.DOUBLE_TAP -> doubleTap(action)
        AccessibilityActionType.LONG_PRESS -> pointGesture(action, action.durationMs ?: 700L, "Long press")
        AccessibilityActionType.SWIPE -> pathGesture(action, action.durationMs ?: 280L, "Swipe")
        AccessibilityActionType.DRAG -> pathGesture(action, action.durationMs ?: 850L, "Drag")
    }

    private fun openApp(action: AccessibilityAction): AccessibilityActionResult {
        val targetPackage = action.packageName?.trim().orEmpty()
        if (targetPackage.isEmpty()) return action.fail("OPEN_APP requires packageName")

        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            ?: return action.fail("Package has no launchable activity")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            startActivity(launchIntent)
            action.ok(
                "App launch requested",
                mapOf("package" to targetPackage)
            )
        }.getOrElse { error ->
            action.fail("App launch failed: ${error.javaClass.simpleName}")
        }
    }

    /**
     * Semantic click fallback for native views and browser-exposed web nodes.
     * Only visible/enabled nodes are candidates and equal top scores are refused.
     */
    private fun clickByText(action: AccessibilityAction): AccessibilityActionResult {
        val query = action.text?.trim().orEmpty()
        if (query.isEmpty()) return action.fail("CLICK_TEXT requires text")
        val root = rootInActiveWindow ?: return action.fail("No active accessibility window")

        val ranked = mutableListOf<RankedClickable>()
        root.collectSemanticNodes(limit = 180).forEach { node ->
            val score = SemanticTargetMatcher.score(
                query = query,
                values = node.semanticValues(),
                focused = node.isFocused || node.isAccessibilityFocused
            )
            if (score <= 0) return@forEach
            val clickable = node.findClickableAncestor() ?: return@forEach
            val index = ranked.indexOfFirst { it.node == clickable }
            if (index < 0) {
                ranked += RankedClickable(clickable, score)
            } else if (score > ranked[index].score) {
                ranked[index] = RankedClickable(clickable, score)
            }
        }
        if (ranked.isEmpty()) return action.fail("No visible clickable node matched semantic target")

        val topScore = ranked.maxOf { it.score }
        val top = ranked.filter { it.score == topScore }
        if (top.size != 1) return action.fail("Semantic click target is ambiguous; more screen context is required")
        val selected = top.single().node

        val accepted = selected.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (accepted) {
            action.ok(
                "Semantic click accepted",
                mapOf(
                    "query" to query,
                    "package" to (root.packageName?.toString() ?: "unknown"),
                    "score" to topScore.toString()
                )
            )
        } else {
            action.fail("Android rejected click action")
        }
    }

    private fun setTextByViewId(action: AccessibilityAction): AccessibilityActionResult {
        val viewId = action.viewId?.trim().orEmpty()
        val value = action.value ?: return action.fail("SET_TEXT_BY_VIEW_ID requires value")
        if (viewId.isEmpty()) return action.fail("SET_TEXT_BY_VIEW_ID requires viewId")
        val root = rootInActiveWindow ?: return action.fail("No active accessibility window")
        val node = root.findAccessibilityNodeInfosByViewId(viewId)
            .firstOrNull { it.isVisibleToUser && it.isEnabled }
            ?: return action.fail("No visible enabled node matched viewId")
        return setTextOnNode(action, node, value, mapOf("viewId" to viewId))
    }

    /**
     * Finds an editable field by semantic label/hint/content/resource id rather
     * than requiring a model to know raw Android view ids. Ambiguous matches are
     * refused instead of guessing.
     */
    private fun setTextSemantic(action: AccessibilityAction): AccessibilityActionResult {
        val query = action.text?.trim().orEmpty()
        val value = action.value ?: return action.fail("SET_TEXT_SEMANTIC requires value")
        val root = rootInActiveWindow ?: return action.fail("No active accessibility window")
        val editables = root.collectEditableNodes(limit = 100)
        if (editables.isEmpty()) return action.fail("No visible enabled editable field found")

        val selected = if (query.isBlank()) {
            val focused = editables.filter { it.isFocused || it.isAccessibilityFocused }
            when {
                focused.size == 1 -> focused.single()
                focused.isEmpty() && editables.size == 1 -> editables.single()
                else -> return action.fail("Editable field is ambiguous; specify its label or hint")
            }
        } else {
            val ranked = editables
                .map { node ->
                    node to SemanticTargetMatcher.score(
                        query = query,
                        values = node.semanticValues(),
                        focused = node.isFocused || node.isAccessibilityFocused
                    )
                }
                .filter { (_, score) -> score > 0 }
                .sortedByDescending { (_, score) -> score }
            if (ranked.isEmpty()) return action.fail("No editable field matched semantic target")
            val topScore = ranked.first().second
            val top = ranked.filter { it.second == topScore }
            if (top.size != 1) return action.fail("Editable field target is ambiguous")
            top.single().first
        }

        val evidence = buildMap {
            put("target", query.ifBlank { "focused-editable" })
            selected.viewIdResourceName?.let { put("viewId", it) }
            selected.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { put("hint", it) }
            put("length", value.length.toString())
        }
        return setTextOnNode(action, selected, value, evidence)
    }

    private fun setTextOnNode(
        action: AccessibilityAction,
        node: AccessibilityNodeInfo,
        value: String,
        evidence: Map<String, String>
    ): AccessibilityActionResult {
        if (!node.isVisibleToUser || !node.isEnabled) return action.fail("Editable field is not safely actionable")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (accepted) {
            action.ok("Text action accepted", evidence)
        } else {
            action.fail("Android rejected text action")
        }
    }

    private fun scroll(action: AccessibilityAction, forward: Boolean): AccessibilityActionResult {
        val root = rootInActiveWindow ?: return action.fail("No active accessibility window")
        val scrollable = root.findFirstScrollable()
            ?: return action.fail("No visible enabled scrollable node found")
        val actionId = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val accepted = scrollable.performAction(actionId)
        return if (accepted) action.ok(if (forward) "Scroll forward accepted" else "Scroll backward accepted")
        else action.fail("Android rejected scroll action")
    }

    private fun globalAction(
        action: AccessibilityAction,
        globalAction: Int,
        label: String
    ): AccessibilityActionResult {
        val accepted = performGlobalAction(globalAction)
        return if (accepted) action.ok("$label accepted") else action.fail("Android rejected $label action")
    }

    private fun pointGesture(action: AccessibilityAction, durationMs: Long, label: String): AccessibilityActionResult {
        val x = action.x ?: return action.fail("$label requires x")
        val y = action.y ?: return action.fail("$label requires y")
        if (x < 0f || y < 0f) return action.fail("$label coordinates must be non-negative")
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(40L, 5_000L)))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        return if (dispatched) action.ok("$label gesture dispatched", mapOf("x" to x.toString(), "y" to y.toString()))
        else action.fail("Android rejected $label gesture")
    }

    private fun doubleTap(action: AccessibilityAction): AccessibilityActionResult {
        val x = action.x ?: return action.fail("Double tap requires x")
        val y = action.y ?: return action.fail("Double tap requires y")
        if (x < 0f || y < 0f) return action.fail("Double tap coordinates must be non-negative")
        val first = Path().apply { moveTo(x, y) }
        val second = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(first, 0L, 55L))
            .addStroke(GestureDescription.StrokeDescription(second, 140L, 55L))
            .build()
        return if (dispatchGesture(gesture, null, null)) action.ok("Double tap gesture dispatched")
        else action.fail("Android rejected double tap gesture")
    }

    private fun pathGesture(action: AccessibilityAction, durationMs: Long, label: String): AccessibilityActionResult {
        val x1 = action.x ?: return action.fail("$label requires x")
        val y1 = action.y ?: return action.fail("$label requires y")
        val x2 = action.x2 ?: return action.fail("$label requires x2")
        val y2 = action.y2 ?: return action.fail("$label requires y2")
        if (minOf(x1, y1, x2, y2) < 0f) return action.fail("$label coordinates must be non-negative")
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(80L, 8_000L)))
            .build()
        return if (dispatchGesture(gesture, null, null)) {
            action.ok("$label gesture dispatched", mapOf("x1" to x1.toString(), "y1" to y1.toString(), "x2" to x2.toString(), "y2" to y2.toString()))
        } else action.fail("Android rejected $label gesture")
    }

    private fun <T> onServiceThread(fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrDefault(fallback)

        val result = AtomicReference(fallback)
        val latch = CountDownLatch(1)
        val posted = serviceHandler.post {
            result.set(runCatching(block).getOrDefault(fallback))
            latch.countDown()
        }
        if (!posted) return fallback
        if (!latch.await(3, TimeUnit.SECONDS)) return fallback
        return result.get()
    }

    private fun AccessibilityNodeInfo.findClickableAncestor(): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = this
        while (current != null) {
            if (current.isVisibleToUser && current.isEnabled && current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun AccessibilityNodeInfo.findFirstScrollable(): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(this)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && node.isEnabled && node.isScrollable) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun AccessibilityNodeInfo.collectEditableNodes(limit: Int): List<AccessibilityNodeInfo> {
        val output = ArrayList<AccessibilityNodeInfo>(minOf(limit, 16))
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(this)
        while (queue.isNotEmpty() && output.size < limit) {
            val node = queue.removeFirst()
            val supportsSetText = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            if (node.isVisibleToUser && node.isEnabled && (node.isEditable || supportsSetText)) output += node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return output
    }

    private fun AccessibilityNodeInfo.collectSemanticNodes(limit: Int): List<AccessibilityNodeInfo> {
        val output = ArrayList<AccessibilityNodeInfo>(minOf(limit, 32))
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(this)
        while (queue.isNotEmpty() && output.size < limit) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && node.isEnabled && node.semanticValues().any { !it.isNullOrBlank() }) output += node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return output
    }

    private fun AccessibilityNodeInfo.semanticValues(): List<String?> = listOf(
        viewIdResourceName,
        hintText?.toString(),
        text?.toString(),
        contentDescription?.toString(),
        paneTitle?.toString(),
        tooltipText?.toString()
    )

    private fun AccessibilityNodeInfo?.collectNodeObservations(limit: Int): List<ScreenNodeObservation> {
        if (this == null) return emptyList()
        val output = ArrayList<ScreenNodeObservation>(minOf(limit, 48))
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(this)
        while (queue.isNotEmpty() && output.size < limit) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                output += ScreenNodeObservation(
                    bounds = ScreenBounds(rect.left, rect.top, rect.right, rect.bottom),
                    text = node.text?.toString()?.takeIf(String::isNotBlank),
                    hint = node.hintText?.toString()?.takeIf(String::isNotBlank),
                    contentDescription = node.contentDescription?.toString()?.takeIf(String::isNotBlank),
                    viewId = node.viewIdResourceName,
                    className = node.className?.toString(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    enabled = node.isEnabled,
                    focused = node.isFocused || node.isAccessibilityFocused
                )
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return output
    }

    private fun screenFingerprint(
        packageName: String?,
        className: String?,
        nodes: List<ScreenNodeObservation>
    ): String {
        val canonical = buildString {
            append(packageName.orEmpty()).append('|').append(className.orEmpty())
            nodes.take(120).forEach { node ->
                append('|').append(node.viewId.orEmpty())
                append(':').append(node.text.orEmpty().take(80))
                append(':').append(node.contentDescription.orEmpty().take(80))
                append(':').append(node.bounds.left).append(',').append(node.bounds.top)
                append(',').append(node.bounds.right).append(',').append(node.bounds.bottom)
                append(':').append(if (node.clickable) '1' else '0')
                append(if (node.editable) '1' else '0')
                append(if (node.scrollable) '1' else '0')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }

    private fun AccessibilityNodeInfo?.collectVisibleText(limit: Int): List<String> {
        if (this == null) return emptyList()
        val output = ArrayList<String>(minOf(limit, 32))
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(this)

        while (queue.isNotEmpty() && output.size < limit) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let(output::add)
                node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { output += "hint:$it" }
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(output::add)
                node.paneTitle?.toString()?.takeIf { it.isNotBlank() }?.let { output += "pane:$it" }
                node.tooltipText?.toString()?.takeIf { it.isNotBlank() }?.let { output += "tooltip:$it" }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return output.distinct()
    }

    private fun AccessibilityAction.ok(
        message: String,
        evidence: Map<String, String> = emptyMap()
    ) = AccessibilityActionResult(
        accepted = true,
        action = type,
        message = message,
        evidence = evidence
    )

    private fun AccessibilityAction.fail(message: String) = AccessibilityActionResult(
        accepted = false,
        action = type,
        message = message
    )

    private data class RankedClickable(
        val node: AccessibilityNodeInfo,
        val score: Int
    )
}