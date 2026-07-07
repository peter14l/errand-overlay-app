package com.errand.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import android.util.Log

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

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // No-op for now; we pull nodes on-demand rather than handling push events
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

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
                packageName = node.packageName?.toString() ?: "",
                viewIdResourceName = node.viewIdResourceName ?: "",
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                node = node // Keep actual reference if we need to click it
            )
        )
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                traverseNode(child, result, depth + 1)
            }
        }
    }

    fun performClick(node: AccessibilityNodeInfo): Boolean {
        var temp: AccessibilityNodeInfo? = node
        while (temp != null) {
            if (temp.isClickable) {
                return temp.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            temp = temp.parent
        }
        // Fallback: Click by coordinates if we can
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val x = (bounds.left + bounds.right) / 2f
        val y = (bounds.top + bounds.bottom) / 2f
        return clickAtCoordinates(x, y)
    }

    fun performTypeText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) return false
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun clickAtCoordinates(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun performScrollDown(): Boolean {
        // Simple swipe up to scroll down
        val path = Path()
        path.moveTo(500f, 1500f)
        path.lineTo(500f, 500f)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun performScrollUp(): Boolean {
        // Simple swipe down to scroll up
        val path = Path()
        path.moveTo(500f, 500f)
        path.lineTo(500f, 1500f)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}

data class AccessibilityNodeInfoCompat(
    val text: String?,
    val contentDescription: String?,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val packageName: String,
    val viewIdResourceName: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val node: AccessibilityNodeInfo
)
