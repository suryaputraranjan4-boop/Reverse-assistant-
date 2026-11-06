package com.reverseassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AssistantBackup(
    val messages: List<ChatMessage>,
    val provider: String,
    val model: String,
    val speakReplies: Boolean,
    val persona: String,
    val speechRate: Float,
    val speechPitch: Float
)

/** Portable backup for non-secret app data. API keys and OS permissions are intentionally excluded. */
object BackupManager {
    private const val MAX_BACKUP_CHARS = 2_000_000

    fun create(context: Context, messages: List<ChatMessage>): String {
        val settings = context.getSharedPreferences("reverse_assistant_settings", Context.MODE_PRIVATE)
        val chat = JSONArray()
        messages.takeLast(120).forEach { message ->
            chat.put(JSONObject().put("text", message.text.take(20000)).put("fromUser", message.fromUser))
        }
        return JSONObject()
            .put("format", "reverse-assistant-backup")
            .put("schemaVersion", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("messages", chat)
            .put("settings", JSONObject()
                .put("provider", settings.getString("provider", "GEMINI"))
                .put("model", settings.getString("model", ""))
                .put("speakReplies", settings.getBoolean("speak_replies", true))
                .put("persona", settings.getString("assistant_persona", "Helpful, clear, and concise"))
                .put("speechRate", settings.getFloat("speech_rate", 1f).toDouble())
                .put("speechPitch", settings.getFloat("speech_pitch", 1f).toDouble()))
            .toString(2)
    }

    fun parse(raw: String): AssistantBackup {
        require(raw.length <= MAX_BACKUP_CHARS) { "Backup file is too large." }
        val root = JSONObject(raw)
        require(root.optString("format") == "reverse-assistant-backup") { "This is not a Reverse Assistant backup." }
        require(root.optInt("schemaVersion", -1) == 1) { "Unsupported backup version." }
        val settings = root.optJSONObject("settings") ?: JSONObject()
        val array = root.optJSONArray("messages") ?: JSONArray()
        val messages = (0 until minOf(array.length(), 120)).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val value = item.optString("text").take(20000)
            if (value.isBlank()) null else ChatMessage(value, item.optBoolean("fromUser"))
        }
        val provider = settings.optString("provider", "GEMINI")
            .takeIf { candidate -> com.reverseassistant.ai.AiProvider.values().any { it.name == candidate } }
            ?: "GEMINI"
        return AssistantBackup(
            messages = messages,
            provider = provider,
            model = settings.optString("model", "").take(160),
            speakReplies = settings.optBoolean("speakReplies", true),
            persona = settings.optString("persona", "Helpful, clear, and concise").take(300),
            speechRate = settings.optDouble("speechRate", 1.0).toFloat().coerceIn(0.6f, 1.5f),
            speechPitch = settings.optDouble("speechPitch", 1.0).toFloat().coerceIn(0.7f, 1.4f)
        )
    }
}
