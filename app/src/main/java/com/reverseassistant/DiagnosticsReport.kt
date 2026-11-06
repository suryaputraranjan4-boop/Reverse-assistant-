package com.reverseassistant

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import com.reverseassistant.ai.AiProvider
import com.reverseassistant.security.SecretStore
import com.reverseassistant.touch.TouchControlService

object DiagnosticsReport {
    fun create(context: Context): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val providers = AiProvider.values().joinToString("\n") { provider ->
            val configured = !SecretStore.get(context, provider.secretName).isNullOrBlank()
            "- ${provider.label} API key configured: ${if (configured) "yes" else "no"}"
        }
        val accessibility = TouchControlService.active != null
        val speech = SpeechRecognizer.isRecognitionAvailable(context)
        return buildString {
            appendLine("Reverse Assistant diagnostics")
            appendLine("App version: ${packageInfo.versionName ?: "unknown"}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Speech recognition available: ${if (speech) "yes" else "no"}")
            appendLine("Touch accessibility service active: ${if (accessibility) "yes" else "no"}")
            appendLine("API key status (keys are never included):")
            appendLine(providers)
            appendLine("Screen capture: requires fresh Android MediaProjection approval per session")
            appendLine("Microphone: foreground-only user-started dictation")
            appendLine("Generated at: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        }
    }
}
