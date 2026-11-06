package com.reverseassistant.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AiClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build()

    suspend fun ask(
        provider: AiProvider,
        model: String,
        apiKey: String,
        prompt: String,
        image: ByteArray? = null
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Add an API key for ${provider.label} in Settings." }
        require(prompt.isNotBlank()) { "Write a message first." }
        val image64 = image?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        val request = when (provider) {
            AiProvider.GEMINI -> gemini(model, apiKey, prompt, image64)
            AiProvider.OPENAI -> openAi("https://api.openai.com/v1/chat/completions", model, apiKey, prompt, image64, false)
            AiProvider.OPENROUTER -> openAi("https://openrouter.ai/api/v1/chat/completions", model, apiKey, prompt, image64, true)
            AiProvider.CLAUDE -> claude(model, apiKey, prompt, image64)
        }
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val safe = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw IllegalStateException(" ${provider.label} returned HTTP ${response.code}: ${safe?.take(240) ?: "Request failed"}")
            }
            val json = JSONObject(body)
            when (provider) {
                AiProvider.GEMINI -> json.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.let { parts ->
                        (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
                            .joinToString("\n").trim()
                    }.orEmpty()
                AiProvider.OPENAI, AiProvider.OPENROUTER ->
                    json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                AiProvider.CLAUDE -> json.optJSONArray("content")?.let { blocks ->
                    (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it)?.takeIf { b -> b.optString("type") == "text" }?.optString("text") }
                        .joinToString("\n").trim()
                }.orEmpty()
            }.ifBlank { "The provider returned an empty response." }
        }
    }

    private fun gemini(model: String, key: String, prompt: String, image64: String?): Request {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (image64 != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", image64)))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        return Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun openAi(url: String, model: String, key: String, prompt: String, image64: String?, router: Boolean): Request {
        val content: Any = if (image64 == null) prompt else JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$image64")))
        val body = JSONObject().put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("temperature", 0.7)
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (router) builder.header("HTTP-Referer", "https://github.com/suryaputraranjan4-boop/Reverse-assistant-")
            .header("X-Title", "Reverse Assistant")
        return builder.build()
    }

    private fun claude(model: String, key: String, prompt: String, image64: String?): Request {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        if (image64 != null) content.put(JSONObject().put("type", "image")
            .put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", image64)))
        val body = JSONObject().put("model", model).put("max_tokens", 2048)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        return Request.Builder().url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key).header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }
}
