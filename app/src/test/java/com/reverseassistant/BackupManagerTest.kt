package com.reverseassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupManagerTest {
    @Test fun parsesMessagesAndClampsSpeechSettings() {
        val payload = JSONObject()
            .put("format", "reverse-assistant-backup")
            .put("schemaVersion", 1)
            .put("messages", JSONArray().put(JSONObject().put("text", "hello").put("fromUser", true)))
            .put("settings", JSONObject().put("provider", "OPENAI").put("model", "gpt-test")
                .put("speechRate", 4.0).put("speechPitch", 0.1))
        val backup = BackupManager.parse(payload.toString())
        assertEquals(1, backup.messages.size)
        assertEquals("hello", backup.messages[0].text)
        assertEquals("OPENAI", backup.provider)
        assertEquals(1.5f, backup.speechRate)
        assertEquals(0.7f, backup.speechPitch)
    }

    @Test fun rejectsUnknownBackupFormat() {
        val payload = JSONObject().put("format", "other").put("schemaVersion", 1)
        assertThrows(IllegalArgumentException::class.java) {
            BackupManager.parse(payload.toString())
        }
    }

    @Test fun invalidProviderFallsBackToGemini() {
        val payload = JSONObject().put("format", "reverse-assistant-backup").put("schemaVersion", 1)
            .put("settings", JSONObject().put("provider", "UNSAFE_PROVIDER"))
        assertEquals("GEMINI", BackupManager.parse(payload.toString()).provider)
    }
}
