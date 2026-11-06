package com.reverseassistant.ai

/** Provider presets; compatible services share the OpenAI chat-completions wire format. */
enum class AiProvider(
    val label: String,
    val secretName: String,
    val defaultModel: String,
    val endpoint: String? = null
) {
    GEMINI("Gemini", "gemini_key", "gemini-2.5-flash"),
    OPENAI("OpenAI", "openai_key", "gpt-4o-mini", "https://api.openai.com/v1/chat/completions"),
    CLAUDE("Claude", "claude_key", "claude-sonnet-4-5"),
    OPENROUTER("OpenRouter", "openrouter_key", "openrouter/auto", "https://openrouter.ai/api/v1/chat/completions"),
    DEEPSEEK("DeepSeek", "deepseek_key", "deepseek-chat", "https://api.deepseek.com/chat/completions"),
    GROQ("Groq", "groq_key", "llama-3.3-70b-versatile", "https://api.groq.com/openai/v1/chat/completions"),
    MISTRAL("Mistral", "mistral_key", "mistral-small-latest", "https://api.mistral.ai/v1/chat/completions"),
    XAI("xAI", "xai_key", "grok-3-mini", "https://api.x.ai/v1/chat/completions"),
    TOGETHER("Together AI", "together_key", "meta-llama/Llama-3.3-70B-Instruct-Turbo", "https://api.together.xyz/v1/chat/completions"),
    FIREWORKS("Fireworks AI", "fireworks_key", "accounts/fireworks/models/llama-v3p3-70b-instruct", "https://api.fireworks.ai/inference/v1/chat/completions"),
    CEREBRAS("Cerebras", "cerebras_key", "llama-3.3-70b", "https://api.cerebras.ai/v1/chat/completions"),
    PERPLEXITY("Perplexity", "perplexity_key", "sonar", "https://api.perplexity.ai/chat/completions"),
    FIREWORKS("Fireworks AI", "fireworks_key", "accounts/fireworks/models/llama-v3p3-70b-instruct", "https://api.fireworks.ai/inference/v1/chat/completions"),
    SAMBANOVA("SambaNova", "sambanova_key", "Meta-Llama-3.3-70B-Instruct", "https://api.sambanova.ai/v1/chat/completions"),
    NVIDIA_NIM("NVIDIA NIM", "nvidia_nim_key", "meta/llama-3.3-70b-instruct", "https://integrate.api.nvidia.com/v1/chat/completions"),
    CUSTOM_OPENAI_COMPATIBLE("Custom OpenAI-compatible", "custom_provider_key", "your-model")
}
