package com.errand.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ErrandAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ErrandAccessibility"
        private var instance: ErrandAccessibilityService? = null

        fun getInstance(): ErrandAccessibilityService? = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Accessibility Service Created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op; we pull nodes on-demand
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // ── Screen metrics ──────────────────────────────────────────────

    private fun getDisplayMetrics(): DisplayMetrics = resources.displayMetrics

    fun getScreenSize(): Pair<Int, Int> {
        val dm = getDisplayMetrics()
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    // ── App detection ───────────────────────────────────────────────

    fun getForegroundApp(): String {
        val rootNode = rootInActiveWindow ?: return ""
        return rootNode.packageName?.toString() ?: ""
    }

    // ── Window hierarchy ────────────────────────────────────────────

    fun getWindowHierarchy(): List<AccessibilityNodeInfoCompat> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfoCompat>()
        traverseNode(rootNode, result, 0)
        return result
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        result: MutableList<AccessibilityNodeInfoCompat>,
        depth: Int
    ) {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        result.add(
            AccessibilityNodeInfoCompat(
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                className = node.className?.toString() ?: "",
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isScrollable = node.isScrollable,
                isLongClickable = node.isLongClickable,
                packageName = node.packageName?.toString() ?: "",
                viewIdResourceName = node.viewIdResourceName ?: "",
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                node = node
            )
        )
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNode(child, result, depth + 1)
            }
        }
    }

    // ── Click ───────────────────────────────────────────────────────

    fun performClick(node: AccessibilityNodeInfo): Boolean {
        var temp: AccessibilityNodeInfo? = node
        while (temp != null) {
            if (temp.isClickable) {
                return temp.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            temp = temp.parent
        }
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val x = (bounds.left + bounds.right) / 2f
        val y = (bounds.top + bounds.bottom) / 2f
        return clickAtCoordinates(x, y)
    }

    fun clickAtCoordinates(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ── Long click ──────────────────────────────────────────────────

    fun performLongClick(node: AccessibilityNodeInfo): Boolean {
        var temp: AccessibilityNodeInfo? = node
        while (temp != null) {
            if (temp.isLongClickable) {
                return temp.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            }
            temp = temp.parent
        }
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val x = (bounds.left + bounds.right) / 2f
        val y = (bounds.top + bounds.bottom) / 2f
        return longClickAtCoordinates(x, y)
    }

    fun longClickAtCoordinates(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 800))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ── Swipe / Scroll ──────────────────────────────────────────────

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 500): Boolean {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun performSwipeDirection(direction: String, elementIndex: Int? = null, nodes: List<AccessibilityNodeInfoCompat>? = null): Boolean {
        val (screenW, screenH) = getScreenSize()
        val centerX = screenW / 2f
        val centerY = screenH / 2f

        var x1 = centerX
        var y1 = centerY
        var x2 = centerX
        var y2 = centerY

        if (elementIndex != null && nodes != null && elementIndex in nodes.indices) {
            val el = nodes[elementIndex]
            val elCenterX = (el.left + el.right) / 2f
            val elCenterY = (el.top + el.bottom) / 2f
            val elW = (el.right - el.left).toFloat()
            val elH = (el.bottom - el.top).toFloat()
            x1 = elCenterX
            y1 = elCenterY
            when (direction) {
                "left" -> { x2 = x1 - elW * 0.8f; y2 = y1 }
                "right" -> { x2 = x1 + elW * 0.8f; y2 = y1 }
                "up" -> { x2 = x1; y2 = y1 - elH * 0.8f }
                "down" -> { x2 = x1; y2 = y1 + elH * 0.8f }
            }
        } else {
            val swipeFraction = 0.35f
            when (direction) {
                "left" -> { x1 = centerX + screenW * swipeFraction; y1 = centerY; x2 = centerX - screenW * swipeFraction; y2 = centerY }
                "right" -> { x1 = centerX - screenW * swipeFraction; y1 = centerY; x2 = centerX + screenW * swipeFraction; y2 = centerY }
                "up" -> { x1 = centerX; y1 = centerY + screenH * swipeFraction; x2 = centerX; y2 = centerY - screenH * swipeFraction }
                "down" -> { x1 = centerX; y1 = centerY - screenH * swipeFraction; x2 = centerX; y2 = centerY + screenH * swipeFraction }
            }
        }

        return performSwipe(x1, y1, x2, y2)
    }

    fun performScrollDown(): Boolean {
        val (screenW, screenH) = getScreenSize()
        val centerX = screenW / 2f
        val startY = screenH * 0.7f
        val endY = screenH * 0.3f
        return performSwipe(centerX, startY, centerX, endY, 500)
    }

    fun performScrollUp(): Boolean {
        val (screenW, screenH) = getScreenSize()
        val centerX = screenW / 2f
        val startY = screenH * 0.3f
        val endY = screenH * 0.7f
        return performSwipe(centerX, startY, centerX, endY, 500)
    }

    // ── Text input ──────────────────────────────────────────────────

    fun performTypeText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) return false
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    // ── System navigation ───────────────────────────────────────────

    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun performRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }
}

data class AccessibilityNodeInfoCompat(
    val text: String?,
    val contentDescription: String?,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isLongClickable: Boolean = false,
    val packageName: String,
    val viewIdResourceName: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val node: AccessibilityNodeInfo
) {
    fun recycle() {
        node.recycle()
    }
}

fun List<AccessibilityNodeInfoCompat>.recycleAll() {
    forEach { it.recycle() }
}
