package com.reverseassistant.ai

import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * User-consented, text-response Gemini Live session for sampled screen frames.
 * The API key stays in the app's encrypted settings store; it is sent to Google's
 * documented WebSocket endpoint and must never be logged or committed.
 */
class GeminiLiveVideoSession(
    apiKey: String,
    private val model: String = "gemini-2.5-flash-native-audio-preview-12-2025",
    private val onReady: () -> Unit,
    private val onText: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val socketUrl: HttpUrl = HttpUrl.Builder()
        .scheme("wss")
        .host("generativelanguage.googleapis.com")
        .addPathSegments("ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
        .addQueryParameter("key", apiKey)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var setupComplete = false
    @Volatile private var closed = false
    private val responseText = StringBuilder()

    init { require(apiKey.isNotBlank()) { "Add a Gemini API key before starting live video." } }

    fun start() {
        check(!closed) { "Live session has already been closed." }
        socket = client.newWebSocket(
            Request.Builder().url(socketUrl.toString().replaceFirst("https://", "wss://")).build(),
            object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject().put("setup", JSONObject()
                    .put("model", "models/$model")
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT")))
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put(
                        "text", "You are observing a user-consented Android screen feed. Use incoming frames as visual context. Answer only when the user sends a text prompt; do not assume or describe private content unnecessarily."
                    ))))
                )
                if (!webSocket.send(setup.toString())) fail("Could not send Gemini Live setup.")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = JSONObject(text)
                    if (event.has("error")) {
                        fail(event.optJSONObject("error")?.optString("message") ?: "Gemini Live returned an error.")
                        return
                    }
                    if (event.has("setupComplete")) {
                        setupComplete = true
                        onReady()
                    }
                    val serverContent = event.optJSONObject("serverContent")
                    val parts = serverContent?.optJSONObject("modelTurn")?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            val delta = part.optString("text")
                            if (delta.isNotBlank()) responseText.append(delta)
                        }
                    }
                    if (serverContent?.optBoolean("turnComplete", false) == true) {
                        val reply = responseText.toString().trim()
                        responseText.setLength(0)
                        if (reply.isNotBlank()) onText(reply)
                    }
                } catch (error: Exception) {
                    fail(error.message ?: "Could not read Gemini Live response.")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed) fail(t.message ?: "Gemini Live connection failed.")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed && code != 1000) fail("Gemini Live closed: $reason")
            }
        })
    }

    fun sendVideoFrame(jpeg: ByteArray): Boolean {
        if (!setupComplete || closed || jpeg.isEmpty()) return false
        val message = JSONObject().put("realtimeInput", JSONObject().put("video",
            JSONObject().put("mimeType", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
        return socket?.send(message.toString()) == true
    }

    fun sendText(text: String): Boolean {
        if (!setupComplete || closed || text.isBlank()) return false
        val message = JSONObject().put("clientContent", JSONObject()
            .put("turns", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("turnComplete", true))
        return socket?.send(message.toString()) == true
    }

    fun close() {
        closed = true
        setupComplete = false
        socket?.close(1000, "User stopped live screen sharing")
        socket = null
        client.dispatcher.executorService.shutdown()
    }

    private fun fail(message: String) {
        if (closed) return
        closed = true
        setupComplete = false
        socket?.cancel()
        socket = null
        onError(message.take(300))
    }
}
