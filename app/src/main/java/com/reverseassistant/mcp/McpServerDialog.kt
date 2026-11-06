package com.reverseassistant.mcp

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun McpServerDialog(context: Context, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf(McpServerRegistry.list(context)) }
    var selected by remember { mutableStateOf<SavedMcpServer?>(null) }
    var tools by remember { mutableStateOf(emptyList<McpTool>()) }
    var selectedTool by remember { mutableStateOf<McpTool?>(null) }
    var args by remember { mutableStateOf("{}") }
    var output by remember { mutableStateOf("Add a trusted Streamable HTTP MCP server to discover tools.") }
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun loadTools(server: SavedMcpServer) {
        selected = server
        selectedTool = null
        tools = emptyList()
        busy = true
        scope.launch {
            try {
                tools = withContext(Dispatchers.IO) {
                    McpServerClient(McpServerConfig(
                        server.name, server.endpoint, McpServerRegistry.bearerToken(context, server.id)
                    )).use { client ->
                        client.initialize()
                        client.listTools()
                    }
                }
                output = "Connected to ${server.name}. Found ${tools.size} tool(s)."
            } catch (error: Exception) {
                output = error.message ?: "MCP connection failed."
            } finally { busy = false }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("EXTERNAL MCP SERVERS") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Connect only servers you trust. Tokens are encrypted on this device. Every tool call requires your Run tap.")
                if (servers.isNotEmpty()) {
                    Text("SAVED SERVERS")
                    servers.forEach { server ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Checkbox(
                                checked = server.enabled,
                                onCheckedChange = { enabled ->
                                    McpServerRegistry.setEnabled(context, server.id, enabled)
                                    servers = McpServerRegistry.list(context)
                                }
                            )
                            OutlinedButton(
                                onClick = { loadTools(server) },
                                enabled = !busy && server.enabled,
                                modifier = Modifier.weight(1f)
                            ) { Text(server.name) }
                            TextButton(onClick = {
                                McpServerRegistry.remove(context, server.id)
                                servers = McpServerRegistry.list(context)
                                if (selected?.id == server.id) {
                                    selected = null
                                    tools = emptyList()
                                    selectedTool = null
                                }
                            }) { Text("REMOVE") }
                        }
                    }
                }
                Text("ADD SERVER")
                OutlinedTextField(value = name, onValueChange = { name = it.take(80) },
                    label = { Text("Server name") }, singleLine = true)
                OutlinedTextField(value = endpoint, onValueChange = { endpoint = it.take(500) },
                    label = { Text("HTTPS MCP endpoint") }, singleLine = true)
                OutlinedTextField(value = token, onValueChange = { token = it.take(2000) },
                    label = { Text("Bearer token (optional)") }, singleLine = true)
                Button(enabled = !busy && name.isNotBlank() && endpoint.isNotBlank(), onClick = {
                    try {
                        val item = McpServerRegistry.add(context, name, endpoint, token)
                        servers = McpServerRegistry.list(context)
                        name = ""; endpoint = ""; token = ""
                        loadTools(item)
                    } catch (error: Exception) {
                        output = error.message ?: "Could not save server."
                    }
                }) { Text(if (busy) "CONNECTING…" else "SAVE & CONNECT") }
                selected?.let { Text("TOOLS FROM ${it.name}") }
                tools.forEach { tool ->
                    OutlinedButton(
                        onClick = { selectedTool = tool; args = "{}" },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(tool.name) }
                    if (selectedTool?.name == tool.name) {
                        Text(tool.description)
                        Text("Input schema: ${tool.inputSchema}")
                        OutlinedTextField(
                            value = args, onValueChange = { args = it.take(12000) },
                            label = { Text("Tool arguments (JSON object)") }, minLines = 3, maxLines = 6
                        )
                        Button(enabled = !busy, onClick = {
                            val server = selected ?: return@Button
                            val parsed = runCatching { JSONObject(args) }.getOrElse {
                                output = "Arguments must be a valid JSON object."
                                return@Button
                            }
                            busy = true
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        McpServerClient(McpServerConfig(
                                            server.name, server.endpoint,
                                            McpServerRegistry.bearerToken(context, server.id)
                                        )).use { client ->
                                            client.initialize()
                                            client.callTool(tool.name, parsed)
                                        }
                                    }
                                    output = result.toString(2).take(5000)
                                } catch (error: Exception) {
                                    output = error.message ?: "MCP tool call failed."
                                } finally { busy = false }
                            }
                        }) { Text("RUN SELECTED TOOL") }
                    }
                }
                Text(if (busy) "Working…" else output)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("DONE") } }
    )
}
