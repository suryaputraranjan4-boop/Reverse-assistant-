package com.reverseassistant.mcp

import android.content.Context
import com.reverseassistant.security.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedMcpServer(
    val id: String,
    val name: String,
    val endpoint: String,
    val enabled: Boolean
)

/** Stores MCP endpoint metadata locally and encrypts optional bearer tokens with Android Keystore. */
object McpServerRegistry {
    private const val PREFS = "reverse_assistant_mcp"
    private const val SERVERS = "servers"

    fun list(context: Context): List<SavedMcpServer> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SERVERS, "[]") ?: "[]"
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index ->
                val item = json.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val name = item.optString("name")
                val endpoint = item.optString("endpoint")
                if (id.isBlank() || name.isBlank() || endpoint.isBlank()) null
                else SavedMcpServer(id, name, endpoint, item.optBoolean("enabled", true))
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, name: String, endpoint: String, bearerToken: String = ""): SavedMcpServer {
        val safeName = name.trim()
        require(safeName.isNotBlank() && safeName.length <= 80) { "Server name must be 1–80 characters." }
        val safeEndpoint = McpServerClient.validateEndpoint(endpoint).toString()
        val existing = list(context)
        require(existing.none { it.endpoint.equals(safeEndpoint, ignoreCase = true) }) {
            "This MCP endpoint is already saved."
        }
        val server = SavedMcpServer(UUID.randomUUID().toString(), safeName, safeEndpoint, true)
        SecretStore.put(context, tokenKey(server.id), bearerToken.trim())
        persist(context, existing + server)
        return server
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val current = list(context)
        require(current.any { it.id == id }) { "MCP server not found." }
        persist(context, current.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun remove(context: Context, id: String) {
        val current = list(context)
        persist(context, current.filterNot { it.id == id })
        SecretStore.put(context, tokenKey(id), "")
    }

    fun bearerToken(context: Context, id: String): String =
        SecretStore.get(context, tokenKey(id))

    private fun tokenKey(id: String) = "mcp_bearer_$id"

    private fun persist(context: Context, servers: List<SavedMcpServer>) {
        val json = JSONArray()
        servers.forEach { server ->
            json.put(JSONObject().put("id", server.id).put("name", server.name)
                .put("endpoint", server.endpoint).put("enabled", server.enabled))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(SERVERS, json.toString()).apply()
    }
}
