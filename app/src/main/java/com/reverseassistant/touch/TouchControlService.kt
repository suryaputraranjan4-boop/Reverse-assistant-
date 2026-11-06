package com.reverseassistant.touch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** Gesture executor. The user must enable this service in Android Settings. */
class TouchControlService : AccessibilityService() {
    companion object {
        @Volatile var active: TouchControlService? = null
            private set
    }

    override fun onServiceConnected() { super.onServiceConnected(); active = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { active = null; super.onDestroy() }

    fun screenContext(maxItems: Int = 45): String {
        val root = rootInActiveWindow ?: return "No accessible screen is currently available."
        val lines = mutableListOf<String>()
        collectText(root, lines, maxItems.coerceIn(1, 80))
        return lines.joinToString("\\n").ifBlank { "The current screen exposes no readable text." }
    }

    private fun collectText(node: android.view.accessibility.AccessibilityNodeInfo?, out: MutableList<String>, limit: Int) {
        if (node == null || out.size >= limit) return
        val label = listOf(node.text?.toString(), node.contentDescription?.toString())
            .filterNotNull().map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(" | ")
        if (label.isNotBlank()) {
            val traits = buildList {
                if (node.isClickable) add("clickable")
                if (node.isScrollable) add("scrollable")
                if (node.isEditable) add("editable")
            }
            out.add(if (traits.isEmpty()) label else "$label [${traits.joinToString()}]")
        }
        for (i in 0 until node.childCount) {
            if (out.size >= limit) break
            collectText(node.getChild(i), out, limit)
        }
    }

    fun tapText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickMatchingNode(root, target.trim().lowercase())
    }

    private fun clickMatchingNode(node: android.view.accessibility.AccessibilityNodeInfo?, target: String): Boolean {
        if (node == null || target.isBlank()) return false
        val labels = listOf(node.text?.toString(), node.contentDescription?.toString())
            .filterNotNull().map { it.trim().lowercase() }
        if (labels.any { it == target || it.contains(target) } && node.isClickable) {
            return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            if (clickMatchingNode(node.getChild(i), target)) return true
        }
        return false
    }

    fun tap(x: Float, y: Float): Boolean = dispatch(points = listOf(Stroke(x,y,0,70)))
    fun longPress(x: Float, y: Float, holdMs: Long = 700): Boolean =
        dispatch(points = listOf(Stroke(x,y,0,holdMs.coerceIn(500,3000))))

    fun doubleTap(x: Float, y: Float): Boolean =
        dispatch(points = listOf(Stroke(x,y,0,65), Stroke(x,y,210,65)))

    fun repeatedTap(x: Float, y: Float, count: Int): Boolean {
        val safeCount = count.coerceIn(1, 10)
        return dispatch(points = (0 until safeCount).map { Stroke(x,y,it * 180L,65) })
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 420): Boolean =
        dispatch(points = listOf(Stroke(x1,y1,0,durationMs.coerceIn(150,1500),x2,y2)))

    fun scrollUp(): Boolean = swipe(0.5f,0.78f,0.5f,0.28f)
    fun scrollDown(): Boolean = swipe(0.5f,0.28f,0.5f,0.78f)
    fun drag(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 700): Boolean =
        swipe(x1,y1,x2,y2,durationMs)

    private data class Stroke(
        val x: Float, val y: Float, val start: Long, val duration: Long,
        val endX: Float = x, val endY: Float = y
    )

    private fun dispatch(points: List<Stroke>): Boolean {
        val dm = resources.displayMetrics
        val builder = GestureDescription.Builder()
        points.forEach { s ->
            val path = Path().apply {
                moveTo(s.x.coerceIn(0f,1f) * dm.widthPixels, s.y.coerceIn(0f,1f) * dm.heightPixels)
                lineTo(s.endX.coerceIn(0f,1f) * dm.widthPixels, s.endY.coerceIn(0f,1f) * dm.heightPixels)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, s.start, s.duration))
        }
        return dispatchGesture(builder.build(), null, null)
    }
}
