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
import androidx.compose.material.icons.filled.Stop
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
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(val text: String, val fromUser: Boolean)

private const val CHAT_HISTORY_PREFS = "reverse_assistant_chat"
private const val CHAT_HISTORY_KEY = "messages"

private fun loadChatHistory(context: android.content.Context): List<ChatMessage> {
    return try {
        val raw = context.getSharedPreferences(CHAT_HISTORY_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(CHAT_HISTORY_KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val text = item.optString("text")
            if (text.isBlank()) null else ChatMessage(text, item.optBoolean("fromUser"))
        }.takeLast(120)
    } catch (_: Exception) { emptyList() }
}

private fun saveChatHistory(context: android.content.Context, messages: List<ChatMessage>) {
    val array = JSONArray()
    messages.takeLast(120).forEach { message ->
        array.put(JSONObject().put("text", message.text).put("fromUser", message.fromUser))
    }
    context.getSharedPreferences(CHAT_HISTORY_PREFS, android.content.Context.MODE_PRIVATE)
        .edit().putString(CHAT_HISTORY_KEY, array.toString()).apply()
}

private fun isExplicitPhoneControlRequest(message: String): Boolean {
    val text = message.lowercase(Locale.ROOT)
    val directPhrases = listOf(
        "control my phone", "operate my phone", "use my phone",
        "control this phone", "operate this phone",
        "tap the screen", "click the screen", "swipe the screen",
        "scroll the screen", "long press", "double tap",
        "screen par tap", "screen pe tap", "tap karo", "click karo",
        "scroll karo", "swipe karo", "app kholo", "app open karo",
        "phone chalao", "phone operate karo", "phone control karo",
        "button dabao", "screen par click"
    )
    if (directPhrases.any(text::contains)) return true
    val hasDevice = listOf("phone", "mobile", "screen", "app").any(text::contains)
    val hasAction = listOf(
        "open", "launch", "tap", "click", "scroll", "swipe",
        "press", "drag", "navigate", "kholo", "dabao", "chalao"
    ).any(text::contains)
    return hasDevice && hasAction
}

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
        val settingsPrefs = remember { getSharedPreferences("reverse_assistant_settings", MODE_PRIVATE) }
        var provider by remember {
            mutableStateOf(AiProvider.values().firstOrNull { it.name == settingsPrefs.getString("provider", "GEMINI") } ?: AiProvider.GEMINI)
        }
        var model by remember { mutableStateOf(settingsPrefs.getString("model", null) ?: provider.defaultModel) }
        var speakReplies by remember { mutableStateOf(settingsPrefs.getBoolean("speak_replies", true)) }
        var speechRate by remember { mutableFloatStateOf(settingsPrefs.getFloat("speech_rate", 1.0f).coerceIn(0.6f, 1.5f)) }
        var speechPitch by remember { mutableFloatStateOf(settingsPrefs.getFloat("speech_pitch", 1.0f).coerceIn(0.7f, 1.4f)) }
        var attachedDocument by remember { mutableStateOf("") }
        var attachedDocumentName by remember { mutableStateOf("") }
        var prompt by remember { mutableStateOf("") }
        var apiKey by remember { mutableStateOf("") }
        var showSettings by remember { mutableStateOf(false) }
        var showProviderMenu by remember { mutableStateOf(false) }
        var image by remember { mutableStateOf<ByteArray?>(null) }
        var status by remember { mutableStateOf("Choose an AI provider and add its API key in Settings.") }
        var busy by remember { mutableStateOf(false) }
        var lastReply by remember { mutableStateOf("") }
        LaunchedEffect(provider, model, speakReplies, speechRate, speechPitch) {
            settingsPrefs.edit().putString("provider", provider.name).putString("model", model)
                .putBoolean("speak_replies", speakReplies).putFloat("speech_rate", speechRate)
                .putFloat("speech_pitch", speechPitch).apply()
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(speechPitch)
        }
        var pendingAction by remember { mutableStateOf<PhoneAction?>(null) }
        val messages = remember {
            mutableStateListOf<ChatMessage>().apply { addAll(loadChatHistory(this@MainActivity)) }
        }
        LaunchedEffect(messages.size) {
            saveChatHistory(this@MainActivity, messages.toList())
        }

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
        val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    val input = contentResolver.openInputStream(uri) ?: error("Could not open this document.")
                    val output = ByteArrayOutputStream()
                    input.use { stream ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = stream.read(buffer)
                            if (count <= 0 || total >= 120_000) break
                            val allowed = minOf(count, 120_000 - total)
                            output.write(buffer, 0, allowed)
                            total += allowed
                        }
                    }
                    attachedDocument = output.toString("UTF-8")
                    attachedDocumentName = uri.lastPathSegment?.substringAfterLast('/')?.take(80) ?: "Attached text file"
                    status = "Document attached (up to 120 KB). It will be sent to the selected AI provider with your next message."
                } catch (e: Exception) { status = e.message ?: "Could not read document." }
            }
        }
        val exportChat = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                try {
                    val transcript = messages.joinToString("\n\n") {
                        (if (it.fromUser) "You: " else "Assistant: ") + it.text
                    }
                    contentResolver.openOutputStream(uri)?.use { it.write(transcript.toByteArray(Charsets.UTF_8)) }
                    status = "Chat transcript exported."
                } catch (e: Exception) { status = e.message ?: "Could not export chat." }
            }
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
                    if (attachedDocument.isNotBlank()) Text("📄 " + attachedDocumentName + " attached — sent only when you press Send", color = Color(0xFF80CBC4), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Message Reverse Assistant") }, minLines = 2)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { camera.launch(null) }) { Icon(Icons.Default.CameraAlt, "Camera", tint = Color.White) }
                        TextButton(onClick = { documentPicker.launch(arrayOf("text/plain", "text/markdown", "text/csv", "application/json")) }) { Text("FILE") }
                        TextButton(onClick = { exportChat.launch("reverse-assistant-chat.txt") }) { Text("EXPORT") }
                        TextButton(onClick = { tts?.stop(); status = "Speech stopped." }) { Icon(Icons.Default.Stop, "Stop speech") }
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
                                        Allowed types: launch_app, tap_text, tap, double_tap, long_press, repeat_tap, swipe, drag, scroll_up, scroll_down.
                                        For launch_app, target must be the exact visible app name.
                                        Coordinate fields x,y,x2,y2 must be normalized from 0.0 to 1.0.
                                        For repeat_tap use count 1..10. For long_press/swipe/drag use duration_ms.
                                        Do not guess targets or perform sensitive actions. If uncertain, ask a question
                                        in normal text instead. The app requires the user to review and confirm the action.
                                    """.trimIndent() else """
                                        Phone control was not requested. Do not return phone_action JSON or suggest
                                        operating the device. Answer normally without accessing screen contents.
                                    """.trimIndent()
                                    val documentSection = if (attachedDocument.isBlank()) "" else
                                        "User-attached document (" + attachedDocumentName.take(80) + "; user explicitly attached it for this request):\n" +
                                        attachedDocument.take(120000) + "\n\n"
                                    val contextPrompt = memorySection + documentSection + actionGuide +
                                        "\n\nVisible Android screen context (only if explicitly requested):\n$screenContext\n\nRecent conversation:\n$recentChat\n\nRespond to the latest user message."
                                    val reply = AiClient().ask(provider, model, key, contextPrompt, photo)
                                    attachedDocument = ""
                                    attachedDocumentName = ""
                                    val proposedAction = if (requestsPhoneControl) PhoneAction.parse(reply) else null
                                    if (proposedAction != null) {
                                        pendingAction = proposedAction
                                        messages.add(ChatMessage("I prepared a phone action. Review the confirmation card below before it runs.", false))
                                        lastReply = "Proposed action: ${proposedAction.description}"
                                    } else {
                                        messages.add(ChatMessage(reply, false))
                                        lastReply = reply
                                        if (speakReplies) tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, Bundle(), "assistant-reply")
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
                        TextButton(onClick = {
                            messages.clear()
                            lastReply = ""
                            pendingAction = null
                            status = "Chat history cleared on this device."
                        }) { Text("CLEAR CHAT") }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = speakReplies, onCheckedChange = { speakReplies = it })
                        Text("Speak AI replies")
                    }
                    Text("Speech speed: " + String.format(Locale.getDefault(), "%.1f", speechRate) + "×")
                    Slider(value = speechRate, onValueChange = { speechRate = it }, valueRange = 0.6f..1.5f)
                    Text("Speech pitch: " + String.format(Locale.getDefault(), "%.1f", speechPitch) + "×")
                    Slider(value = speechPitch, onValueChange = { speechPitch = it }, valueRange = 0.7f..1.4f)
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
