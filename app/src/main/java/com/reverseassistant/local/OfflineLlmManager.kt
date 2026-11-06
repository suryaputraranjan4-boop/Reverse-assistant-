package com.reverseassistant.local

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Single-model on-device GGUF inference backed by the upstream llama.cpp Android JNI binding. */
object OfflineLlmManager {
    @Volatile private var engine: InferenceEngine? = null
    @Volatile private var loadedPath: String? = null

    suspend fun load(context: Context, modelPath: String) = withContext(Dispatchers.IO) {
        val file = java.io.File(modelPath)
        require(file.isFile && file.canRead()) { "The selected GGUF model file is not readable." }
        val current = engine ?: AiChat.getInferenceEngine(context.applicationContext).also { engine = it }
        val state = current.state.first {
            it is InferenceEngine.State.Initialized ||
                it is InferenceEngine.State.ModelReady ||
                it is InferenceEngine.State.Error
        }
        if (state is InferenceEngine.State.Error) {
            current.cleanUp()
        } else if (state is InferenceEngine.State.ModelReady && loadedPath == file.absolutePath) {
            return@withContext
        } else if (state is InferenceEngine.State.ModelReady) {
            current.cleanUp()
        }
        current.loadModel(file.absolutePath)
        current.setSystemPrompt(
            "You are Reverse Assistant, a helpful offline AI assistant. Be accurate, concise, and honest about uncertainty. " +
                "You are running locally on the user's Android device without cloud access."
        )
        loadedPath = file.absolutePath
    }

    fun isReady(): Boolean = engine?.state?.value is InferenceEngine.State.ModelReady

    fun generate(prompt: String): Flow<String> {
        require(prompt.isNotBlank()) { "Write a message first." }
        val current = engine ?: error("Import and load a GGUF model first.")
        check(current.state.value is InferenceEngine.State.ModelReady) { "The offline model is not loaded." }
        return current.sendUserPrompt(prompt, predictLength = 512)
    }

    fun loadedModelPath(): String? = loadedPath

    fun unload() {
        engine?.cleanUp()
        loadedPath = null
    }
}
