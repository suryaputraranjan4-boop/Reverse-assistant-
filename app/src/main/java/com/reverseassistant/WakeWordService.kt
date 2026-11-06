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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * User-started, visible foreground wake-phrase listener.
 * It only forwards a command after a configured phrase and never sends it automatically.
 */
class WakeWordService : Service() {
    companion object {
        const val ACTION_STOP = "com.reverseassistant.STOP_WAKE_WORD"
        @Volatile var active: Boolean = false
        @Volatile var onCommand: ((String) -> Unit)? = null
        @Volatile var onStopped: (() -> Unit)? = null
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 74
        private val phrases = listOf("hey reverse", "hello reverse", "ok reverse")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var awaitingCommand = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            return START_NOT_STICKY
        }
        active = true
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

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopListening()
            return START_NOT_STICKY
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
                speech.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onResults(results: Bundle?) {
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                        if (spoken.isNotBlank()) processSpeech(spoken)
                        restartListening()
                    }
                    override fun onError(error: Int) {
                        if (!stopping) restartListening()
                    }
                })
            }
        }
        startListening()
        return START_NOT_STICKY
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

    private fun startListening() {
        if (stopping) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try { recognizer?.startListening(intent) } catch (_: Exception) { restartListening() }
    }

    private fun restartListening() {
        if (stopping) return
        handler.postDelayed({
            if (!stopping) {
                try { recognizer?.cancel() } catch (_: Exception) { }
                startListening()
            }
        }, 700)
    }

    private fun stopListening() {
        if (stopping) return
        stopping = true
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Exception) { }
        try { recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
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
        try { recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
        active = false
        onStopped?.invoke()
        onStopped = null
        super.onDestroy()
    }
}