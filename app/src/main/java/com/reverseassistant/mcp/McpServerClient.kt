package com.reverseassistant.mcp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class McpServerConfig(
    val name: String,
    val endpoint: String,
    val bearerToken: String = ""
)

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

/**
 * Minimal MCP Streamable HTTP client for explicitly configured servers.
 * It never executes tools automatically: callers must present tool metadata and obtain user approval.
 */
class McpServerClient(
    private val config: McpServerConfig,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(65, TimeUnit.SECONDS)
        .build()
) : Closeable {
    private val ids = AtomicLong(1)
    private var sessionId: String? = null
    private var protocolVersion = DEFAULT_PROTOCOL_VERSION
    private var initialized = false

    fun initialize(): JSONObject {
        validateEndpoint(config.endpoint)
        val response = post(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", ids.getAndIncrement())
                .put("method", "initialize")
                .put("params", JSONObject()
                    .put("protocolVersion", DEFAULT_PROTOCOL_VERSION)
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "Reverse Assistant").put("version", "0.1.0")))
        )
        val result = response.optJSONObject("result") ?: rpcError(response)
        protocolVersion = result.optString("protocolVersion", DEFAULT_PROTOCOL_VERSION)
        val notification = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/initialized")
        post(notification, allowEmpty = true)
        initialized = true
        return result
    }

    fun listTools(): List<McpTool> {
        check(initialized) { "Connect to the MCP server before listing tools." }
        val response = post(
            JSONObject().put("jsonrpc", "2.0").put("id", ids.getAndIncrement())
                .put("method", "tools/list").put("params", JSONObject())
        )
        val result = response.optJSONObject("result") ?: rpcError(response)
        val tools = result.optJSONArray("tools") ?: JSONArray()
        return (0 until tools.length()).mapNotNull { index ->
            val item = tools.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").trim()
            if (name.isBlank()) return@mapNotNull null
            McpTool(
                name = name,
                description = item.optString("description"),
                inputSchema = item.optJSONObject("inputSchema") ?: JSONObject()
            )
        }
    }

    fun callTool(name: String, arguments: JSONObject): JSONObject {
        check(initialized) { "Connect to the MCP server before calling a tool." }
        require(name.isNotBlank() && name.length <= 200) { "Invalid MCP tool name." }
        val response = post(
            JSONObject().put("jsonrpc", "2.0").put("id", ids.getAndIncrement())
                .put("method", "tools/call")
                .put("params", JSONObject().put("name", name).put("arguments", arguments))
        )
        val result = response.optJSONObject("result") ?: rpcError(response)
        check(!result.optBoolean("isError", false)) {
            result.optJSONArray("content")?.toString()?.take(800) ?: "MCP tool returned an error."
        }
        return result
    }

    private fun post(payload: JSONObject, allowEmpty: Boolean = false): JSONObject {
        val builder = Request.Builder().url(config.endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", protocolVersion)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (config.bearerToken.isNotBlank()) {
            builder.header("Authorization", "Bearer ${config.bearerToken}")
        }
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string().orEmpty().take(500)
                throw IllegalStateException("MCP server returned HTTP ${response.code}: ${detail.ifBlank { "Request failed" }}")
            }
            response.header("Mcp-Session-Id")?.let { sessionId = it }
            val body = response.body?.string().orEmpty()
            if (body.isBlank() && allowEmpty) return JSONObject()
            if (body.isBlank()) error("MCP server returned an empty JSON-RPC response.")
            val json = if (response.header("Content-Type").orEmpty().contains("text/event-stream", true)) {
                body.lineSequence().filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trim() }
                    .filter { it.isNotBlank() && it != "[DONE]" }
                    .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                    .lastOrNull()
            } else runCatching { JSONObject(body) }.getOrNull()
            return json ?: error("MCP server response was not valid JSON-RPC.")
        }
    }

    private fun rpcError(response: JSONObject): Nothing {
        val error = response.optJSONObject("error")
        throw IllegalStateException(
            "MCP error ${error?.optInt("code") ?: "unknown"}: ${error?.optString("message")?.take(500) ?: "Missing result"}"
        )
    }

    override fun close() {
        initialized = false
        sessionId = null
    }

    companion object {
        private const val DEFAULT_PROTOCOL_VERSION = "2025-03-26"

        /** HTTPS is required except loopback HTTP for local development servers. */
        fun validateEndpoint(value: String): URI {
            val uri = try { URI(value.trim()) } catch (_: Exception) {
                throw IllegalArgumentException("Enter a valid MCP server URL.")
            }
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            require(host != null && uri.userInfo == null && uri.fragment == null && uri.rawQuery == null) {
                "MCP URL must include a host and cannot contain credentials, query strings, or fragments. Put tokens in the encrypted token field."
            }
            val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
            require(scheme == "https" || (scheme == "http" && loopback)) {
                "MCP servers must use HTTPS. HTTP is allowed only for localhost testing."
            }
            require(uri.port == -1 || uri.port in 1..65535) { "Invalid MCP server port." }
            return uri
        }
    }
}
