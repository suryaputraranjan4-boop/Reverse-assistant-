package com.reverseassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener as AndroidRecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService as VoskSpeechService
import java.io.File
import java.util.Locale

/**
 * Visible, user-started foreground wake phrase service.
 * Uses a user-imported Vosk model offline when available; otherwise uses Android speech recognition.
 * Commands are put in the composer and are never auto-submitted.
 */
class WakeWordService : Service() {
    companion object {
        const val ACTION_STOP = "com.reverseassistant.STOP_WAKE_WORD"
        @Volatile var active: Boolean = false
        @Volatile var onCommand: ((String) -> Unit)? = null
        @Volatile var onStopped: (() -> Unit)? = null
        @Volatile var onStatus: ((String) -> Unit)? = null
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 74
        private val phrases = listOf("hey reverse", "hello reverse", "ok reverse")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var androidRecognizer: SpeechRecognizer? = null
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var voskSpeech: VoskSpeechService? = null
    private var awaitingCommand = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            return START_NOT_STICKY
        }
        active = true
        stopping = false
        createChannel()
        val stopIntent = Intent(this, WakeWordService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 74, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Reverse Assistant wake phrase")
            .setContentText("Microphone is active. Say “Hey Reverse”, or tap Stop.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop listening", stopPending)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIFICATION_ID, notification)

        val offlinePath = getSharedPreferences("reverse_assistant_voice", MODE_PRIVATE)
            .getString("vosk_model_path", "").orEmpty()
        if (offlinePath.isNotBlank()) {
            try {
                require(File(offlinePath).isDirectory) { "Offline Vosk model folder is missing." }
                startVosk(offlinePath)
                onStatus?.invoke("Offline wake phrase engine active. Say “Hey Reverse”.")
            } catch (error: Exception) {
                onStatus?.invoke("Offline wake model failed to start: ${error.message ?: "unknown error"}")
                stopListening()
            }
        } else {
            onStatus?.invoke("Using Android speech recognition. Import an offline Vosk model for local wake phrases.")
            startAndroidRecognition()
        }
        return START_NOT_STICKY
    }

    private fun startAndroidRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onStatus?.invoke("No Android speech recognition service is available.")
            stopListening()
            return
        }
        if (androidRecognizer == null) {
            androidRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
                speech.setRecognitionListener(object : AndroidRecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onResults(results: Bundle?) {
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (spoken.isNotBlank()) processSpeech(spoken)
                        restartAndroidRecognition()
                    }
                    override fun onError(error: Int) {
                        if (!stopping) restartAndroidRecognition()
                    }
                })
            }
        }
        startAndroidListening()
    }

    private fun startVosk(modelPath: String) {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val model = Model(modelPath)
        val recognizer = Recognizer(model, 16000.0f)
        val speech = VoskSpeechService(recognizer, 16000.0f)
        voskModel = model
        voskRecognizer = recognizer
        voskSpeech = speech
        check(speech.startListening(object : VoskRecognitionListener {
            override fun onPartialResult(hypothesis: String?) = Unit
            override fun onResult(hypothesis: String?) = processVoskResult(hypothesis)
            override fun onFinalResult(hypothesis: String?) = processVoskResult(hypothesis)
            override fun onError(error: Exception?) {
                if (!stopping) onStatus?.invoke("Offline microphone recognition stopped: ${error?.message ?: "unknown error"}")
            }
            override fun onTimeout() = Unit
        })) { "Could not start the offline Vosk recognizer." }
    }

    private fun processVoskResult(raw: String?) {
        val spoken = runCatching { JSONObject(raw.orEmpty()).optString("text") }.getOrDefault("").trim()
        if (spoken.isNotBlank()) processSpeech(spoken)
    }

    private fun processSpeech(spoken: String) {
        if (awaitingCommand) {
            awaitingCommand = false
            onCommand?.invoke(spoken)
            return
        }
        val normalized = spoken.lowercase(Locale.ROOT)
        val phrase = phrases.firstOrNull { normalized.startsWith(it) } ?: return
        val command = spoken.drop(phrase.length).trim().trimStart(',', ':', '.', ' ')
        if (command.isNotBlank()) onCommand?.invoke(command)
        else awaitingCommand = true
    }

    private fun startAndroidListening() {
        if (stopping) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try { androidRecognizer?.startListening(intent) } catch (_: Exception) { restartAndroidRecognition() }
    }

    private fun restartAndroidRecognition() {
        if (stopping) return
        handler.postDelayed({
            if (!stopping) {
                try { androidRecognizer?.cancel() } catch (_: Exception) { }
                startAndroidListening()
            }
        }, 700)
    }

    private fun stopListening() {
        if (stopping) return
        stopping = true
        handler.removeCallbacksAndMessages(null)
        try { androidRecognizer?.cancel() } catch (_: Exception) { }
        try { androidRecognizer?.destroy() } catch (_: Exception) { }
        androidRecognizer = null
        try { voskSpeech?.stop() } catch (_: Exception) { }
        try { voskSpeech?.shutdown() } catch (_: Exception) { }
        try { voskRecognizer?.close() } catch (_: Exception) { }
        try { voskModel?.close() } catch (_: Exception) { }
        voskSpeech = null
        voskRecognizer = null
        voskModel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Wake phrase microphone", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        try { androidRecognizer?.destroy() } catch (_: Exception) { }
        androidRecognizer = null
        try { voskSpeech?.stop() } catch (_: Exception) { }
        try { voskSpeech?.shutdown() } catch (_: Exception) { }
        try { voskRecognizer?.close() } catch (_: Exception) { }
        try { voskModel?.close() } catch (_: Exception) { }
        voskSpeech = null
        voskRecognizer = null
        voskModel = null
        active = false
        onStopped?.invoke()
        onStopped = null
        super.onDestroy()
    }
}
