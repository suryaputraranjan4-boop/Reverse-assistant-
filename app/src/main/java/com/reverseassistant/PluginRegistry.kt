package com.reverseassistant

import android.content.Context

data class AssistantPlugin(
    val id: String,
    val name: String,
    val description: String,
    val defaultEnabled: Boolean = true
)

/**
 * Local capability plug-in registry. These are in-app integrations, not remote MCP servers.
 * Third-party connectors require a separately reviewed adapter and explicit credentials/permissions.
 */
object PluginRegistry {
    private const val PREFS = "reverse_assistant_plugins"
    val plugins = listOf(
        AssistantPlugin("web_search", "Web search", "Search ranked web pages and show source links."),
        AssistantPlugin("obsidian", "Obsidian memory", "Read relevant vault notes and save conversation memories."),
        AssistantPlugin("phone_actions", "Phone automation", "Propose allow-listed gestures; every step needs confirmation."),
        AssistantPlugin("screen_context", "Screen context", "Use OS-consented screen frames only during an active share.")
    )

    fun isEnabled(context: Context, id: String): Boolean {
        val plugin = plugins.firstOrNull { it.id == id } ?: return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(id, plugin.defaultEnabled)
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        require(plugins.any { it.id == id }) { "Unknown plug-in." }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(id, enabled).apply()
    }
}