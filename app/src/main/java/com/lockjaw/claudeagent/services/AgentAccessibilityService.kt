// services/AgentAccessibilityService.kt
package com.lockjaw.claudeagent.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lockjaw.claudeagent.claude.ScreenEventBus

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
        private const val TAG = "AccessibilityAgent"
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Broadcast notable events to the rest of the app
        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                ScreenEventBus.emit(event)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    // ─── Screen Content ───────────────────────────────────────────────────────

    fun getScreenContent(): String {
        return try {
            val root = rootInActiveWindow ?: return "No active window"
            val sb = StringBuilder()
            sb.append("App: ${root.packageName}\n")
            extractText(root, sb, depth = 0)
            sb.toString().take(2000) // limit size
        } catch (e: Exception) {
            "Screen read failed: ${e.message}"
        }
    }

    private fun extractText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        node ?: return
        if (depth > 12) return
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val content = when {
            !text.isNullOrBlank() -> text
            !desc.isNullOrBlank() -> "[${desc}]"
            else -> null
        }
        if (content != null) {
            val indent = "  ".repeat(minOf(depth, 6))
            val clickable = if (node.isClickable) "⟨tap⟩" else ""
            sb.appendLine("$indent$content $clickable".trim())
        }
        for (i in 0 until node.childCount) {
            extractText(node.getChild(i), sb, depth + 1)
        }
    }

    // ─── Click ────────────────────────────────────────────────────────────────

    fun clickElement(text: String?, viewId: String?) {
        val root = rootInActiveWindow ?: throw Exception("No active window")
        val node = when {
            !text.isNullOrBlank() -> findNodeByText(root, text)
            !viewId.isNullOrBlank() -> root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
            else -> throw Exception("No text or id provided")
        } ?: throw Exception("Element not found: ${text ?: viewId}")

        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            // Click the center of the node's bounds
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            clickCoordinates(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
    }

    private fun clickCoordinates(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        // Exact match first
        root.findAccessibilityNodeInfosByText(text).firstOrNull()?.let { return it }
        // Partial match walk
        return walkAndFind(root) { node ->
            node.text?.toString()?.contains(text, ignoreCase = true) == true ||
                    node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
        }
    }

    private fun walkAndFind(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        node ?: return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            walkAndFind(node.getChild(i), predicate)?.let { return it }
        }
        return null
    }

    // ─── Text Input ───────────────────────────────────────────────────────────

    fun inputText(viewId: String?, text: String) {
        val root = rootInActiveWindow ?: throw Exception("No active window")
        val node = if (!viewId.isNullOrBlank()) {
            root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
        } else {
            // Find focused editable field
            walkAndFind(root) { it.isEditable && it.isFocused }
                ?: walkAndFind(root) { it.isEditable }
        } ?: throw Exception("No editable field found")

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ─── Navigation ───────────────────────────────────────────────────────────

    fun scrollDown() {
        val root = rootInActiveWindow ?: return
        val scrollable = walkAndFind(root) { it.isScrollable } ?: return
        scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun takeScreenshot() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        }
    }

    // ─── Swipe ────────────────────────────────────────────────────────────────

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
