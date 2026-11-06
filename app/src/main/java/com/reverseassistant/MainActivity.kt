package com.reverseassistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.reverseassistant.ai.AiClient
import com.reverseassistant.ai.AiProvider
import com.reverseassistant.memory.ObsidianVault
import com.reverseassistant.security.SecretStore
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.launch

data class ChatMessage(val text: String, val fromUser: Boolean)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        setContent { ReverseAssistantApp() }
    }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ReverseAssistantApp() {
        val scope = rememberCoroutineScope()
        var provider by remember { mutableStateOf(AiProvider.GEMINI) }
        var model by remember { mutableStateOf(AiProvider.GEMINI.defaultModel) }
        var prompt by remember { mutableStateOf("") }
        var apiKey by remember { mutableStateOf("") }
        var showSettings by remember { mutableStateOf(false) }
        var showProviderMenu by remember { mutableStateOf(false) }
        var image by remember { mutableStateOf<ByteArray?>(null) }
        var status by remember { mutableStateOf("Choose an AI provider and add its API key in Settings.") }
        var busy by remember { mutableStateOf(false) }
        var lastReply by remember { mutableStateOf("") }
        val messages = remember { mutableStateListOf<ChatMessage>() }

        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap != null) {
                val out = ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                image = out.toByteArray()
                status = "Photo attached. Ask the assistant about it."
            }
        }
        val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) prompt = spoken
        }
        val vaultPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try { ObsidianVault.saveTree(this@MainActivity, uri); status = "Obsidian vault connected." }
                catch (e: Exception) { status = e.message ?: "Could not connect vault." }
            }
        }

        MaterialTheme(colorScheme = darkColorScheme(
            primary = Color(0xFFB7A1FF), secondary = Color(0xFF80CBC4),
            background = Color(0xFF101018), surface = Color(0xFF1B1B28)
        )) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Column { Text("REVERSE ASSISTANT"); Text("Android AI companion", style = MaterialTheme.typography.labelSmall) } },
                        actions = { TextButton(onClick = { showSettings = true }) { Text("SETTINGS") } },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF171722), titleContentColor = Color.White)
                    )
                },
                containerColor = Color(0xFF101018)
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            OutlinedButton(onClick = { showProviderMenu = true }) { Text(provider.label + " ▾") }
                            DropdownMenu(expanded = showProviderMenu, onDismissRequest = { showProviderMenu = false }) {
                                AiProvider.values().forEach { item ->
                                    DropdownMenuItem(text = { Text(item.label) }, onClick = {
                                        provider = item; model = item.defaultModel
                                        apiKey = SecretStore.get(this@MainActivity, item.secretName)
                                        showProviderMenu = false
                                    })
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("3D AVATAR", color = Color(0xFFB7A1FF), style = MaterialTheme.typography.labelMedium)
                    Box(Modifier.fillMaxWidth().height(88.dp).background(Color(0xFF1B1B28), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                        Text("Avatar renderer module — model import pending", color = Color.LightGray)
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(messages) { msg ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start) {
                                Surface(color = if (msg.fromUser) Color(0xFF34304A) else Color(0xFF222231), shape = RoundedCornerShape(16.dp)) {
                                    Text(msg.text, Modifier.padding(12.dp), color = Color.White)
                                }
                            }
                        }
                    }
                    if (image != null) Text("📷 Image attached", color = Color(0xFF80CBC4))
                    OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Message Reverse Assistant") }, minLines = 2)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { camera.launch(null) }) { Icon(Icons.Default.CameraAlt, "Camera", tint = Color.White) }
                        IconButton(onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Reverse Assistant")
                            }
                            try { voice.launch(intent) } catch (_: Exception) { status = "No speech recognition app is available." }
                        }) { Icon(Icons.Default.Mic, "Voice input", tint = Color.White) }
                        TextButton(onClick = {
                            if (lastReply.isBlank()) status = "Ask something first."
                            else try { ObsidianVault.appendMemory(this@MainActivity, "Conversation note", lastReply); status = "Saved to Obsidian." }
                            catch (e: Exception) { status = e.message ?: "Connect Obsidian first." }
                        }) { Text("SAVE MEMORY") }
                        Spacer(Modifier.weight(1f))
                        Button(enabled = !busy, onClick = {
                            val text = prompt.trim()
                            if (text.isEmpty()) { status = "Write a message first."; return@Button }
                            messages.add(ChatMessage(text, true))
                            prompt = ""
                            busy = true
                            status = "Thinking with ${provider.label}…"
                            val key = SecretStore.get(this@MainActivity, provider.secretName)
                            val photo = image
                            image = null
                            scope.launch {
                                try {
                                    val savedMemory = ObsidianVault.readMemory(this@MainActivity)
                                    val contextPrompt = if (savedMemory.isBlank()) text else
                                        "Relevant user-approved Obsidian memory (use only when relevant):\n$savedMemory\n\nCurrent request:\n$text"
                                    val reply = AiClient().ask(provider, model, key, contextPrompt, photo)
                                    messages.add(ChatMessage(reply, false))
                                    lastReply = reply
                                    status = "Ready"
                                    tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "assistant-reply")
                                } catch (e: Exception) {
                                    val error = e.message ?: "Request failed."
                                    messages.add(ChatMessage(error, false)); status = error
                                } finally { busy = false }
                            }
                        }) { Icon(Icons.Default.Send, "Send") }
                    }
                    Text(status, color = Color(0xFFB0B0C2), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { vaultPicker.launch(null) }) { Text("CONNECT OBSIDIAN") }
                        TextButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("ENABLE TOUCH CONTROLS") }
                    }
                }
            }
        }

        if (showSettings) {
            var draft by remember { mutableStateOf(SecretStore.get(this@MainActivity, provider.secretName)) }
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("${provider.label} API key") },
                text = { Column {
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("API key") }, singleLine = true)
                    Text("Stored encrypted with Android Keystore. Never commit API keys.", style = MaterialTheme.typography.bodySmall)
                } },
                confirmButton = { TextButton(onClick = {
                    SecretStore.put(this@MainActivity, provider.secretName, draft)
                    apiKey = draft; showSettings = false; status = "Key saved on this device."
                }) { Text("SAVE") } },
                dismissButton = { TextButton(onClick = { showSettings = false }) { Text("CANCEL") } }
            )
        }
    }
}
