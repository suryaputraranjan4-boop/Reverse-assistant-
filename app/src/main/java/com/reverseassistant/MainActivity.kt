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
import com.reverseassistant.touch.PhoneAction
import com.reverseassistant.touch.TouchControlService
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
        var pendingAction by remember { mutableStateOf<PhoneAction?>(null) }
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
                                    val recentChat = messages.takeLast(12).joinToString("\n") {
                                        (if (it.fromUser) "User: " else "Assistant: ") + it.text
                                    }
                                    val memorySection = if (savedMemory.isBlank()) "" else
                                        "Relevant user-approved Obsidian memory (use only when relevant):\n$savedMemory\n\n"
                                    // Screen contents are private. Do not inspect or send them to a cloud
                                    // provider unless the user explicitly asks for phone-control help.
                                    val requestsPhoneControl = isExplicitPhoneControlRequest(text)
                                    val screenContext = if (requestsPhoneControl) {
                                        TouchControlService.active?.screenContext()
                                            ?: "Touch service is not enabled; no screen context is available."
                                    } else {
                                        "Not accessed: the user did not request phone control."
                                    }
                                    val actionGuide = if (requestsPhoneControl) """
                                        The user explicitly requested phone control. If one safe, clear action is
                                        appropriate, return exactly one JSON object and no other text:
                                        {"phone_action":{"type":"tap_text","target":"visible label"}}
                                        Allowed types: tap_text, tap, double_tap, long_press, repeat_tap, swipe, scroll_up, scroll_down.
                                        Coordinate fields x,y,x2,y2 must be normalized from 0.0 to 1.0.
                                        For repeat_tap use count 1..10. For long_press/swipe use duration_ms.
                                        Do not guess targets or perform sensitive actions. If uncertain, ask a question
                                        in normal text instead. The app requires the user to review and confirm the action.
                                    """.trimIndent() else """
                                        Phone control was not requested. Do not return phone_action JSON or suggest
                                        operating the device. Answer normally without accessing screen contents.
                                    """.trimIndent()
                                    val contextPrompt = memorySection + actionGuide +
                                        "\n\nVisible Android screen context (only if explicitly requested):\n$screenContext\n\nRecent conversation:\n$recentChat\n\nRespond to the latest user message."
                                    val reply = AiClient().ask(provider, model, key, contextPrompt, photo)
                                    val proposedAction = if (requestsPhoneControl) PhoneAction.parse(reply) else null
                                    if (proposedAction != null) {
                                        pendingAction = proposedAction
                                        messages.add(ChatMessage("I prepared a phone action. Review the confirmation card below before it runs.", false))
                                        lastReply = "Proposed action: ${proposedAction.description}"
                                    } else {
                                        messages.add(ChatMessage(reply, false))
                                        lastReply = reply
                                        tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, Bundle(), "assistant-reply")
                                    }
                                    status = "Ready"
                                } catch (e: Exception) {
                                    val error = e.message ?: "Request failed."
                                    messages.add(ChatMessage(error, false)); status = error
                                } finally { busy = false }
                            }
                        }) { Icon(Icons.Default.Send, "Send") }
                    }
                    if (pendingAction != null) {
                        Surface(color = Color(0xFF2A2638), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("AI phone action proposal", color = Color(0xFFB7A1FF), style = MaterialTheme.typography.labelLarge)
                                Text(pendingAction!!.description, color = Color.White)
                                Text("Check the screen before confirming. Nothing runs until you tap Confirm.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        val service = TouchControlService.active
                                        val action = pendingAction
                                        if (service == null || action == null) {
                                            status = "Enable Reverse Assistant touch controls in Android Accessibility settings first."
                                        } else {
                                            val ok = action.execute(service)
                                            status = if (ok) "Confirmed action was dispatched." else "Android could not dispatch that action."
                                        }
                                        pendingAction = null
                                    }) { Text("CONFIRM & EXECUTE") }
                                    TextButton(onClick = { pendingAction = null; status = "Action cancelled." }) { Text("CANCEL") }
                                }
                            }
                        }
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
