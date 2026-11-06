package com.reverseassistant.touch

import org.json.JSONObject

/** Strict allow-list for a single phone gesture proposed by the AI. */
data class PhoneAction(
    val type: String,
    val target: String = "",
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val x2: Float = 0.5f,
    val y2: Float = 0.5f,
    val durationMs: Long = 420,
    val count: Int = 2
) {
    val description: String get() = when (type) {
        "tap_text" -> "Tap the screen item: $target"
        "launch_app" -> "Open app: $target"
        "tap" -> "Tap at screen position"
        "double_tap" -> "Double tap at screen position"
        "long_press" -> "Long press at screen position"
        "repeat_tap" -> "Tap repeatedly ($count times)"
        "swipe" -> "Swipe across the screen"
        "drag" -> "Drag across the screen"
        "scroll_up" -> "Scroll up"
        "scroll_down" -> "Scroll down"
        else -> "Unsupported action"
    }

    fun execute(service: TouchControlService): Boolean = when (type) {
        "tap_text" -> service.tapText(target)
        "launch_app" -> service.launchApp(target)
        "tap" -> service.tap(x, y)
        "double_tap" -> service.doubleTap(x, y)
        "long_press" -> service.longPress(x, y, durationMs)
        "repeat_tap" -> service.repeatedTap(x, y, count)
        "swipe" -> service.swipe(x, y, x2, y2, durationMs)
        "drag" -> service.drag(x, y, x2, y2, durationMs)
        "scroll_up" -> service.scrollUp()
        "scroll_down" -> service.scrollDown()
        else -> false
    }

    companion object {
        fun parse(response: String): PhoneAction? {
            return try {
                val start = response.indexOf('{')
                val end = response.lastIndexOf('}')
                if (start < 0 || end <= start) return null
                val root = JSONObject(response.substring(start, end + 1))
                val obj = root.optJSONObject("phone_action") ?: root
                val type = obj.optString("type", obj.optString("action")).lowercase()
                if (type !in setOf("tap_text", "launch_app", "tap", "double_tap", "long_press", "repeat_tap", "swipe", "drag", "scroll_up", "scroll_down")) return null
                val target = obj.optString("target")
                if ((type == "tap_text" || type == "launch_app") && target.isBlank()) return null
                fun coord(name: String, fallback: Double) = obj.optDouble(name, fallback).toFloat().coerceIn(0f, 1f)
                PhoneAction(
                    type = type,
                    target = target.take(100),
                    x = coord("x", 0.5),
                    y = coord("y", 0.5),
                    x2 = coord("x2", 0.5),
                    y2 = coord("y2", 0.5),
                    durationMs = obj.optLong("duration_ms", 420).coerceIn(150, 1500),
                    count = obj.optInt("count", 2).coerceIn(1, 10)
                )
            } catch (_: Exception) { null }
        }
    }
}
