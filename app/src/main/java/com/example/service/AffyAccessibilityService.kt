package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AffyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AffyAccessibilityService connected successfully")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType
        val pkg = event.packageName?.toString() ?: ""

        if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val textList = event.text.map { it.toString() }
            val joined = textList.joinToString(" ")
            if (joined.isNotBlank()) {
                lastNotificationText = joined
                lastNotificationTimestamp = System.currentTimeMillis()
                Log.d(TAG, "Notification observed ($pkg): $joined")
            }
        }
        lastObservedPackage = pkg
    }

    override fun onInterrupt() {
        Log.w(TAG, "AffyAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
    }

    fun findFirstClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var curr = node
        while (curr != null) {
            if (curr.isClickable) return curr
            curr = curr.parent
        }
        return null
    }

    fun findNodeByText(text: String, root: AccessibilityNodeInfo? = rootInActiveWindow): AccessibilityNodeInfo? {
        val r = root ?: return null
        val matches = r.findAccessibilityNodeInfosByText(text)
        return matches.firstOrNull()
    }

    fun findNodeByViewId(viewId: String, root: AccessibilityNodeInfo? = rootInActiveWindow): AccessibilityNodeInfo? {
        val r = root ?: return null
        val matches = r.findAccessibilityNodeInfosByViewId(viewId)
        return matches.firstOrNull()
    }

    fun findEditableNode(root: AccessibilityNodeInfo? = rootInActiveWindow): AccessibilityNodeInfo? {
        val r = root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(r)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.isEditable) return current
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val target = findFirstClickableParent(node) ?: node
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun typeText(text: String, targetNode: AccessibilityNodeInfo? = null): Boolean {
        val node = targetNode ?: findEditableNode() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAG = "AffyAccessibilityService"

        @Volatile
        var instance: AffyAccessibilityService? = null
            private set

        @Volatile
        var lastNotificationText: String = ""

        @Volatile
        var lastNotificationTimestamp: Long = 0L

        @Volatile
        var lastObservedPackage: String = ""

        fun isServiceRunning(): Boolean = instance != null
    }
}
