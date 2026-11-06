package com.reverseassistant

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.media.projection.MediaProjectionManager
import android.speech.RecognitionListener
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.ui.viewinterop.AndroidView
import com.reverseassistant.ai.AiClient
import com.reverseassistant.ai.AiProvider
import com.reverseassistant.memory.ObsidianVault
import com.reverseassistant.security.SecretStore
import com.reverseassistant.search.WebSearchClient
import com.reverseassistant.touch.PhoneAction
import com.reverseassistant.touch.TouchControlService
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private var avatarSpeakingCallback: ((Boolean) -> Unit)? = null
    private var avatarView: Avatar3DView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var continuousListening = false
    private var speechTextCallback: ((String) -> Unit)? = null
    private var speechStatusCallback: ((String) -> Unit)? = null
    private var listeningStateCallback: ((Boolean) -> Unit)? = null
    private val speechHandler = Handler(Looper.getMainLooper())
    private var speechRestart: Runnable? = null
    private val wakePhrases = listOf("hey reverse", "hello reverse", "ok reverse")

    private fun startContinuousListening(
        onText: (String) -> Unit,
        onStatus: (String) -> Unit,
        onState: (Boolean) -> Unit
    ) {
        if (continuousListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onStatus("No speech recognition service is available.")
            return
        }
        continuousListening = true
        speechTextCallback = onText
        speechStatusCallback = onStatus
        listeningStateCallback = onState
        onState(true)
        onStatus("Live microphone active while this screen is open. Tap Stop Mic to end.")
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { speechStatusCallback?.invoke("Listening…") }
                    override fun onBeginningOfSpeech() { speechStatusCallback?.invoke("Hearing speech…") }
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() { speechStatusCallback?.invoke("Processing speech…") }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!partial.isNullOrBlank()) speechTextCallback?.invoke(partial)
                    }
                    override fun onResults(results: Bundle?) {
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!spoken.isNullOrBlank()) {
                            val normalized = spoken.trim()
                            val wake = wakePhrases.firstOrNull { normalized.lowercase(Locale.ROOT).startsWith(it) }
                            if (wake != null) {
                                val command = normalized.drop(wake.length).trim().trimStart(',', ':', '.', ' ')
                                if (command.isNotBlank()) speechTextCallback?.invoke(command)
                                else speechStatusCallback?.invoke("Wake phrase detected. Say your command…")
                            } else speechTextCallback?.invoke(normalized)
                        }
                        scheduleSpeechRestart()
                    }
                    override fun onError(error: Int) {
                        if (!continuousListening) return
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            val stateCallback = listeningStateCallback
                            speechStatusCallback?.invoke("Microphone permission is missing.")
                            stopContinuousListening()
                            stateCallback?.invoke(false)
                        } else {
                            scheduleSpeechRestart()
                        }
                    }
                })
            }
            startSpeechRecognition()
        } catch (e: Exception) {
            onStatus(e.message ?: "Could not start live microphone.")
            stopContinuousListening()
            onState(false)
        }
    }

    private fun startSpeechRecognition() {
        if (!continuousListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try { speechRecognizer?.startListening(intent) }
        catch (_: Exception) { scheduleSpeechRestart() }
    }

    private fun scheduleSpeechRestart() {
        speechRestart?.let(speechHandler::removeCallbacks)
        speechRestart = Runnable { startSpeechRecognition() }
        speechHandler.postDelayed(speechRestart!!, 650)
    }

    private fun stopContinuousListening() {
        continuousListening = false
        speechRestart?.let(speechHandler::removeCallbacks)
        speechRestart = null
        try { speechRecognizer?.cancel() } catch (_: Exception) { }
        try { speechRecognizer?.destroy() } catch (_: Exception) { }
        speechRecognizer = null
        speechStatusCallback?.invoke("Live microphone stopped.")
        speechTextCallback = null
        speechStatusCallback = null
        listeningStateCallback = null
    }

    override fun onPause() {
        avatarView?.onPause()
        if (continuousListening) {
            val stateCallback = listeningStateCallback
            stopContinuousListening()
            stateCallback?.invoke(false)
        }
        super.onPause()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        runOnUiThread { avatarSpeakingCallback?.invoke(true) }
                    }
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread { avatarSpeakingCallback?.invoke(false) }
                    }
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { avatarSpeakingCallback?.invoke(false) }
                    }
                })
            }
        }
        setContent { ReverseAssistantApp() }
    }
    override fun onResume() { super.onResume(); avatarView?.onResume() }
    override fun onDestroy() { avatarView = null; tts?.shutdown(); super.onDestroy() }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun ReverseAssistantApp() {
        val scope = rememberCoroutineScope()
        val settingsPrefs = remember { getSharedPreferences("reverse_assistant_settings", MODE_PRIVATE) }
        var provider by remember {
            mutableStateOf(AiProvider.values().firstOrNull { it.name == settingsPrefs.getString("provider", "GEMINI") } ?: AiProvider.GEMINI)
        }
        var model by remember { mutableStateOf(settingsPrefs.getString("model", null) ?: provider.defaultModel) }
        var speakReplies by remember { mutableStateOf(settingsPrefs.getBoolean("speak_replies", true)) }
        var assistantPersona by remember { mutableStateOf(settingsPrefs.getString("assistant_persona", "Helpful, clear, and concise") ?: "Helpful, clear, and concise") }
        var liveListening by remember { mutableStateOf(false) }
        var wakeWordActive by remember { mutableStateOf(WakeWordService.active) }
        var continuousScreen by remember { mutableStateOf(false) }
        var continuousScreenRequested by remember { mutableStateOf(false) }
        var avatarSpeaking by remember { mutableStateOf(false) }
        var speechRate by remember { mutableFloatStateOf(settingsPrefs.getFloat("speech_rate", 1.0f).coerceIn(0.6f, 1.5f)) }
        var speechPitch by remember { mutableFloatStateOf(settingsPrefs.getFloat("speech_pitch", 1.0f).coerceIn(0.7f, 1.4f)) }
        var attachedDocument by remember { mutableStateOf("") }
        var attachedDocumentName by remember { mutableStateOf("") }
        var importedModelStatus by remember { mutableStateOf("") }
        var prompt by remember { mutableStateOf("") }
        var apiKey by remember { mutableStateOf("") }
        var showSettings by remember { mutableStateOf(false) }
        var showPlugins by remember { mutableStateOf(false) }
        var pluginRevision by remember { mutableIntStateOf(0) }
        var showProviderMenu by remember { mutableStateOf(false) }
        var image by remember { mutableStateOf<ByteArray?>(null) }
        var status by remember { mutableStateOf("Choose an AI provider and add its API key in Settings.") }
        var busy by remember { mutableStateOf(false) }
        var lastReply by remember { mutableStateOf("") }
        DisposableEffect(Unit) {
            avatarSpeakingCallback = { speaking -> avatarSpeaking = speaking }
            onDispose { avatarSpeakingCallback = null }
        }
        DisposableEffect(Unit) {
            WakeWordService.onCommand = { command ->
                prompt = command
                status = "Wake phrase detected. Review the command, then press Send."
            }
            WakeWordService.onStopped = { wakeWordActive = false }
            onDispose {
                WakeWordService.onCommand = null
                WakeWordService.onStopped = null
            }
        }
        LaunchedEffect(provider, model, speakReplies, speechRate, speechPitch, assistantPersona) {
            settingsPrefs.edit().putString("provider", provider.name).putString("model", model)
                .putBoolean("speak_replies", speakReplies).putString("assistant_persona", assistantPersona)
                .putFloat("speech_rate", speechRate)
                .putFloat("speech_pitch", speechPitch).apply()
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(speechPitch)
        }
        var pendingAction by remember { mutableStateOf<PhoneAction?>(null) }
        var pendingActions by remember { mutableStateOf<List<PhoneAction>>(emptyList()) }
        val messages = remember {
            mutableStateListOf<ChatMessage>().apply { addAll(loadChatHistory(this@MainActivity)) }
        }
        LaunchedEffect(messages.toList()) {
            saveChatHistory(this@MainActivity, messages.toList())
        }

        val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startContinuousListening(
                    onText = { spoken -> prompt = spoken },
                    onStatus = { message -> status = message },
                    onState = { active -> liveListening = active }
                )
            } else {
                status = "Microphone permission was not granted."
                liveListening = false
            }
        }
        val wakeWordPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                try {
                    startForegroundService(Intent(this@MainActivity, WakeWordService::class.java))
                    wakeWordActive = true
                    status = "Wake phrase listener started. A persistent notification is visible; say Hey Reverse."
                } catch (e: Exception) {
                    status = e.message ?: "Could not start the wake phrase service."
                }
            } else status = "Microphone permission is required for wake phrase listening."
        }
        val screenCapture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                if (continuousScreenRequested) {
                    ScreenCaptureService.onScreenshot = null
                    ScreenCaptureService.onStopped = {
                        continuousScreen = false
                        status = "Live screen sharing stopped."
                    }
                    ScreenCaptureService.onFrame = { bytes ->
                        image = bytes
                        continuousScreen = true
                        status = "Live screen sharing is active. Latest frame is attached to your next message."
                    }
                } else {
                    ScreenCaptureService.onFrame = null
                    ScreenCaptureService.onScreenshot = { bytes ->
                        if (bytes != null) {
                            image = bytes
                            status = "Screen screenshot attached. It will be sent only when you press Send."
                        } else {
                            status = "Screen capture did not complete. Try again."
                        }
                    }
                }
                try {
                    val serviceIntent = Intent(this@MainActivity, ScreenCaptureService::class.java)
                        .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                        .putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
                        .putExtra(ScreenCaptureService.EXTRA_CONTINUOUS, continuousScreenRequested)
                    startForegroundService(serviceIntent)
                    status = if (continuousScreenRequested) "Starting live screen sharing…" else "Capturing the screen you approved…"
                } catch (e: Exception) {
                    ScreenCaptureService.onScreenshot = null
                    ScreenCaptureService.onFrame = null
                    continuousScreen = false
                    status = e.message ?: "Could not start screen capture."
                } finally {
                    continuousScreenRequested = false
                }
            } else {
                continuousScreenRequested = false
                status = "Screen capture cancelled."
            }
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
                scope.launch {
                    try {
                        val extracted = withContext(Dispatchers.IO) {
                            DocumentTextExtractor.extract(this@MainActivity, uri)
                        }
                        attachedDocument = extracted
                        attachedDocumentName = DocumentTextExtractor.displayName(uri)
                        status = if (attachedDocument.isBlank()) {
                            "No readable text found. Scanned PDFs may need OCR."
                        } else {
                            "Document text attached (up to 120,000 characters). It is sent only when you press Send."
                        }
                    } catch (e: Exception) { status = e.message ?: "Could not read document." }
                }
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
        val backupExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                try {
                    val backup = BackupManager.create(this@MainActivity, messages.toList())
                    contentResolver.openOutputStream(uri)?.use { it.write(backup.toByteArray(Charsets.UTF_8)) }
                    status = "Backup exported. API keys and Android permissions were intentionally excluded."
                } catch (e: Exception) { status = e.message ?: "Could not export backup." }
            }
        }
        val backupImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    val raw = contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 2_000_000) { "Backup file is too large." }
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    } ?: error("Could not open backup file.")
                    val backup = BackupManager.parse(raw)
                    messages.clear()
                    messages.addAll(backup.messages)
                    provider = AiProvider.values().first { it.name == backup.provider }
                    model = backup.model.ifBlank { provider.defaultModel }
                    speakReplies = backup.speakReplies
                    assistantPersona = backup.persona
                    speechRate = backup.speechRate
                    speechPitch = backup.speechPitch
                    apiKey = SecretStore.get(this@MainActivity, provider.secretName)
                    pendingAction = null
                    pendingActions = emptyList()
                    lastReply = ""
                    status = "Backup restored. API keys and Android permissions were not changed."
                } catch (e: Exception) { status = e.message ?: "Could not restore backup." }
            }
        }
        val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        status = "Importing model package to private app storage…"
                        val imported = withContext(Dispatchers.IO) {
                            ModelPackageImporter.import(this@MainActivity, uri)
                        }
                        importedModelStatus = imported.summary
                        status = "Model assets imported locally (${imported.summary}). The current avatar renderer still uses its built-in procedural model; PMX skeletal rendering is not connected yet."
                    } catch (e: Exception) {
                        status = e.message ?: "Could not import the model package."
                    }
                }
            }
        }
                val diagnosticsExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(DiagnosticsReport.create(this@MainActivity).toByteArray(Charsets.UTF_8))
                    }
                    status = "Privacy-safe diagnostics report exported. No API keys are included."
                } catch (e: Exception) { status = e.message ?: "Could not export diagnostics." }
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
                        actions = {
                            TextButton(onClick = { showPlugins = true }) { Text("PLUGINS") }
                            TextButton(onClick = { showSettings = true }) { Text("SETTINGS") }
                        },
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
                    Text("3D COMPANION", color = Color(0xFFB7A1FF), style = MaterialTheme.typography.labelMedium)
                    if (importedModelStatus.isNotBlank()) Text("Imported assets: $importedModelStatus", color = Color(0xFF80CBC4), style = MaterialTheme.typography.bodySmall)
                    AndroidView(
                        factory = { context -> Avatar3DView(context).also { avatarView = it } },
                        update = { view -> view.setSpeaking(avatarSpeaking) },
                        modifier = Modifier.fillMaxWidth().height(180.dp).background(Color(0xFF1B1B28), RoundedCornerShape(18.dp))
                    )
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
                    if (image != null) Text("📷 Photo/screen capture attached — sent only when you press Send", color = Color(0xFF80CBC4))
                    if (attachedDocument.isNotBlank()) Text("📄 " + attachedDocumentName + " attached — sent only when you press Send", color = Color(0xFF80CBC4), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Message Reverse Assistant") }, minLines = 2)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(onClick = { camera.launch(null) }) { Icon(Icons.Default.CameraAlt, "Camera", tint = Color.White) }
                        TextButton(enabled = PluginRegistry.isEnabled(this@MainActivity, "screen_context"), onClick = {
                            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            screenCapture.launch(manager.createScreenCaptureIntent())
                        }) { Text("SCREEN") }
                        TextButton(enabled = PluginRegistry.isEnabled(this@MainActivity, "screen_context"), onClick = {
                            if (continuousScreen) {
                                val stop = Intent(this@MainActivity, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP)
                                startService(stop)
                                ScreenCaptureService.onFrame = null
                                continuousScreen = false
                                status = "Live screen sharing stopped."
                            } else {
                                continuousScreenRequested = true
                                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                screenCapture.launch(manager.createScreenCaptureIntent())
                            }
                        }) { Text(if (continuousScreen) "STOP LIVE SCREEN" else "LIVE SCREEN") }
                        TextButton(onClick = { modelPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) { Text("IMPORT 3D MODEL") }
                        TextButton(onClick = { documentPicker.launch(arrayOf("text/plain", "text/markdown", "text/csv", "application/json", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }) { Text("FILE") }
                        TextButton(onClick = { exportChat.launch("reverse-assistant-chat.txt") }) { Text("EXPORT CHAT") }
                        TextButton(onClick = { backupExport.launch("reverse-assistant-backup.json") }) { Text("BACKUP") }
                        TextButton(onClick = { backupImport.launch(arrayOf("application/json", "text/json")) }) { Text("RESTORE") }
                        TextButton(onClick = { diagnosticsExport.launch("reverse-assistant-diagnostics.txt") }) { Text("DIAGNOSTICS") }
                        TextButton(onClick = {
                            if (wakeWordActive || WakeWordService.active) {
                                startService(Intent(this@MainActivity, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP))
                                wakeWordActive = false
                                status = "Wake phrase listening stopped."
                            } else {
                                wakeWordPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }) { Text(if (wakeWordActive) "STOP WAKE WORD" else "WAKE WORD") }
                        TextButton(onClick = {
                            if (liveListening) {
                                val wasListening = liveListening
                                stopContinuousListening()
                                liveListening = false
                                status = if (wasListening) "Live microphone stopped." else "Speech stopped."
                            }
                            tts?.stop()
                            avatarSpeaking = false
                        }) { Icon(Icons.Default.Stop, "Stop speech and microphone") }
                        TextButton(onClick = {
                            if (liveListening) {
                                stopContinuousListening()
                                liveListening = false
                            } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Text(if (liveListening) "STOP MIC" else "LIVE MIC · SAY ‘HEY REVERSE’") }
                        IconButton(onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Reverse Assistant")
                            }
                            try { voice.launch(intent) } catch (_: Exception) { status = "No speech recognition app is available." }
                        }) { Icon(Icons.Default.Mic, "Voice input", tint = Color.White) }
                        TextButton(enabled = PluginRegistry.isEnabled(this@MainActivity, "obsidian"), onClick = {
                            if (lastReply.isBlank()) status = "Ask something first."
                            else try { ObsidianVault.appendMemory(this@MainActivity, "Conversation note", lastReply); status = "Saved to Obsidian." }
                            catch (e: Exception) { status = e.message ?: "Connect Obsidian first." }
                        }) { Text("SAVE MEMORY") }
                        TextButton(enabled = !busy && prompt.isNotBlank() && PluginRegistry.isEnabled(this@MainActivity, "web_search"), onClick = {
                            val query = prompt.trim()
                            busy = true
                            status = "Searching the web…"
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { WebSearchClient.search(query) }
                                    messages.add(ChatMessage("Quick web lookup for: $query\n\n$result", false))
                                    status = "Quick lookup complete. Verify important details from the linked sources."
                                } catch (e: Exception) {
                                    status = e.message ?: "Web lookup failed."
                                } finally { busy = false }
                            }
                        }) { Text("QUICK WEB") }
                        Button(enabled = !busy, onClick = {
                            val text = prompt.trim()
                            if (text.isEmpty()) { status = "Write a message first."; return@Button }
                            messages.add(ChatMessage(text, true))
                            val assistantMessageIndex = messages.size
                            messages.add(ChatMessage("Connecting to AI…", false))
                            prompt = ""
                            busy = true
                            status = "Thinking with ${provider.label}…"
                            val key = SecretStore.get(this@MainActivity, provider.secretName)
                            val photo = image
                            image = null
                            scope.launch {
                                try {
                                    val savedMemory = if (PluginRegistry.isEnabled(this@MainActivity, "obsidian")) ObsidianVault.readRelevantMemory(this@MainActivity, text) else ""
                                    val recentChat = messages.dropLast(1).takeLast(12).joinToString("\n") {
                                        (if (it.fromUser) "User: " else "Assistant: ") + it.text
                                    }
                                    val memorySection = if (savedMemory.isBlank()) "" else
                                        "Relevant user-approved Obsidian memory (use only when relevant):\n$savedMemory\n\n"
                                    // Screen contents are private. Do not inspect or send them to a cloud
                                    // provider unless the user explicitly asks for phone-control help.
                                    val requestsPhoneControl = isExplicitPhoneControlRequest(text) && PluginRegistry.isEnabled(this@MainActivity, "phone_actions")
                                    val screenContext = if (requestsPhoneControl) {
                                        TouchControlService.active?.screenContext()
                                            ?: "Touch service is not enabled; no screen context is available."
                                    } else {
                                        "Not accessed: the user did not request phone control."
                                    }
                                    val actionGuide = if (requestsPhoneControl) """
                                        The user explicitly requested phone control. If one safe, clear action is
                                        appropriate, return a JSON plan with 1 to 5 ordered actions and no other text:
                                        {"phone_actions":[{"type":"tap_text","target":"visible label"}]}
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
                                    val contextPrompt = "Assistant style preference: " + assistantPersona.take(300) + "\n\n" + memorySection + documentSection + actionGuide +
                                        "\n\nVisible Android screen context (only if explicitly requested):\n$screenContext\n\nRecent conversation:\n$recentChat\n\nRespond to the latest user message."
                                    val reply = AiClient().askStreaming(provider, model, key, contextPrompt, photo) { delta ->
                                        if (!requestsPhoneControl && assistantMessageIndex in messages.indices) {
                                            messages[assistantMessageIndex] = ChatMessage(
                                                messages[assistantMessageIndex].text + delta, false
                                            )
                                        }
                                    }
                                    attachedDocument = ""
                                    attachedDocumentName = ""
                                    val proposedPlan = if (requestsPhoneControl) PhoneAction.parsePlan(reply) else null
                                    if (!proposedPlan.isNullOrEmpty()) {
                                        pendingAction = proposedPlan.first()
                                        pendingActions = proposedPlan.drop(1)
                                        messages[assistantMessageIndex] = ChatMessage(
                                            "I prepared ${proposedPlan.size} phone action(s). Review and confirm each step individually.", false
                                        )
                                        lastReply = "Proposed phone plan: ${proposedPlan.joinToString(" → ") { it.description }}"
                                    } else {
                                        messages[assistantMessageIndex] = ChatMessage(reply, false)
                                        lastReply = reply
                                        if (speakReplies) tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, Bundle(), "assistant-reply")
                                    }
                                    status = "Ready"
                                } catch (e: Exception) {
                                    val error = e.message ?: "Request failed."
                                    if (assistantMessageIndex in messages.indices) messages[assistantMessageIndex] = ChatMessage(error, false)
                                    status = error
                                } finally { busy = false }
                            }
                        }) { Icon(Icons.Default.Send, "Send") }
                    }
                    if (pendingAction != null) {
                        Surface(color = Color(0xFF2A2638), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("AI PHONE ACTION PLAN · STEP", color = Color(0xFFB7A1FF), style = MaterialTheme.typography.labelLarge)
                                Text("${pendingActions.size + 1} step(s) remaining including this one", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
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
                                            status = if (ok) "Step confirmed and dispatched." else "Android could not dispatch that action."
                                            if (ok) {
                                                pendingAction = pendingActions.firstOrNull()
                                                pendingActions = pendingActions.drop(1)
                                                if (pendingAction == null) status = "All confirmed phone-plan steps have been dispatched."
                                            } else {
                                                pendingAction = null
                                                pendingActions = emptyList()
                                            }
                                        }
                                        if (service == null || action == null) {
                                            pendingAction = null
                                            pendingActions = emptyList()
                                        }
                                    }) { Text("CONFIRM & EXECUTE") }
                                    TextButton(onClick = { pendingAction = null; pendingActions = emptyList(); status = "Remaining phone-plan steps cancelled." }) { Text("CANCEL") }
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
                            pendingActions = emptyList()
                            status = "Chat history cleared on this device."
                        }) { Text("CLEAR CHAT") }
                        TextButton(onClick = { vaultPicker.launch(null) }) { Text("CONNECT OBSIDIAN") }
                        TextButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("ENABLE TOUCH CONTROLS") }
                    }
                }
            }
        }

        if (showPlugins) {
            val enabledPlugins = remember(pluginRevision) { PluginRegistry.plugins.associate { plugin ->
                plugin.id to PluginRegistry.isEnabled(this@MainActivity, plugin.id)
            } }
            AlertDialog(
                onDismissRequest = { showPlugins = false },
                title = { Text("IN-APP PLUGINS") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PluginRegistry.plugins.forEach { plugin ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(plugin.name, style = MaterialTheme.typography.titleSmall)
                                    Text(plugin.description, style = MaterialTheme.typography.bodySmall)
                                }
                                Checkbox(
                                    checked = enabledPlugins[plugin.id] == true,
                                    onCheckedChange = { checked ->
                                        PluginRegistry.setEnabled(this@MainActivity, plugin.id, checked)
                                        pluginRevision++
                                    }
                                )
                            }
                        }
                        Text(
                            "These are built-in capability modules. External MCP/plugin servers are not connected by this switch.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { showPlugins = false }) { Text("DONE") } }
            )
        }

        if (showSettings) {
            var draft by remember { mutableStateOf(SecretStore.get(this@MainActivity, provider.secretName)) }
            var personaDraft by remember { mutableStateOf(assistantPersona) }
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("${provider.label} API key") },
                text = { Column {
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("API key") }, singleLine = true)
                    Text("Stored encrypted with Android Keystore. Never commit API keys.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = personaDraft, onValueChange = { personaDraft = it.take(300) }, label = { Text("Assistant personality / style") }, minLines = 2)
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
                    assistantPersona = personaDraft
                    apiKey = draft; showSettings = false; status = "Settings saved on this device."
                }) { Text("SAVE") } },
                dismissButton = { TextButton(onClick = { showSettings = false }) { Text("CANCEL") } }
            )
        }
    }
}
