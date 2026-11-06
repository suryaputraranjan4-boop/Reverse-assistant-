package com.reverseassistant.ai

enum class AiProvider(val label: String, val secretName: String, val defaultModel: String) {
    GEMINI("Gemini", "gemini_key", "gemini-2.5-flash"),
    OPENAI("OpenAI", "openai_key", "gpt-4o-mini"),
    CLAUDE("Claude", "claude_key", "claude-3-7-sonnet-20250219"),
    OPENROUTER("OpenRouter", "openrouter_key", "openrouter/auto")
}
