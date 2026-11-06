# Reverse Assistant

Android-first personal AI assistant. This repository is the clean implementation target for the requirements collected in the project chats.

## Recently implemented capabilities

- Ranked web search: user-triggered DuckDuckGo HTML results with titles, snippets, and source links (network/rate-limit dependent).
- Multi-step phone action plans: up to five allow-listed actions, shown one at a time; each step requires a separate user confirmation. Failed/cancelled steps stop the remaining plan.
- Wake phrase service: user starts/stops a microphone foreground service with a persistent Stop notification. Android speech recognition listens for “Hey Reverse”, “Hello Reverse”, or “OK Reverse”; recognized commands are placed in the chat input and are never sent automatically. Recognition may use the device's configured speech service; this is not an offline keyword model.
- Continuous screen sharing: explicit Android MediaProjection consent starts a foreground service; frames are sampled locally about every 1.5 seconds and the latest frame is attached only to the next user-sent message. A persistent notification provides Stop sharing. This is not a continuous video stream to the AI provider.
- Built-in plugin registry: toggles for web search, Obsidian memory, phone actions, and screen context. These are local in-app capability modules, not external MCP/plugin-server connections.

## Product direction

- Android-native assistant with a conversational UI.
- Provider switcher for Google Gemini, OpenAI, Anthropic Claude, and OpenRouter.
- Camera capture, one-shot screenshot, and user-started continuous MediaProjection screen sampling after explicit OS consent.
- One-shot voice input, foreground dictation, and a user-started microphone foreground service for wake phrases. The wake service has a persistent notification and explicit Stop action. Spoken replies have rate/pitch controls and a stop control.
- Local chat history persisted on-device (latest 120 messages), with a clear-chat control.
- Import user-selected TXT, Markdown, CSV, JSON, PDF, or DOCX documents into the next prompt. PDF import is limited to 20 MB and 30 pages; text context is capped at 120,000 characters. Scanned PDFs need OCR, which is not included.
- Export chat transcripts as plain text.
- Persist provider/model, custom assistant personality, and speech preferences; toggle spoken replies, adjust speech rate/pitch, and stop current speech.
- Obsidian vault integration for durable Markdown memory, with query-keyword relevance retrieval.
- Optional Android AccessibilityService for explicit, user-authorized app launching by unique app label, taps, double taps, repeated taps, long presses, swipes, and vertical scrolling. Drag is available as a confirmed AI action.
- Original procedural OpenGL ES 3D companion with idle movement, blinking, and speech-synchronized mouth animation. PMX/MMD ZIP packages can be imported into app-private storage with bounded safe extraction; the imported skeletal model is not yet rendered or rigged.
- API credentials encrypted with Android Keystore and never committed to Git.

## Status

Implemented in this branch: local chat history, confirmed drag gestures, streaming responses for Gemini/OpenAI/Claude/OpenRouter, foreground continuous dictation (no wake word), one-shot OS-consented screen capture, TXT/Markdown/CSV/JSON/PDF/DOCX text import, chat export, speech controls, configurable assistant style, query-ranked Obsidian memory, a procedural 3D avatar with speech-synced mouth animation, backup/restore, privacy-safe diagnostics export, and a user-triggered DuckDuckGo Instant Answer lookup (abstracts/related topics only, not a full search engine). Still pending: custom PMX/MMD model import and skeletal rigging, continuous screen streaming, safe multi-step device orchestration, scanned-PDF OCR, full web search with ranked pages, external plugin integrations, offline LLM, broad provider compatibility tests, and physical-device validation.

Every push triggers the GitHub Actions debug build and unit tests. Check the latest workflow run before treating a new commit as build-verified. A successful CI build is not proof of physical-device compatibility. Provider calls require the user's own API keys and may incur provider charges. Physical-device testing and several advanced modules are still pending.

## Build

Requirements: JDK 17, Android SDK Platform 35 and Build Tools, Android Gradle Plugin 8.9.2 / Gradle 8.11.1.

Build a debug APK after installing Gradle 8.11.1:

    gradle assembleDebug

The GitHub Actions workflow also builds and uploads a debug APK on pushes and pull requests. A successful CI build is not a substitute for testing on the target phone.

## Permissions and privacy

- Camera and microphone are requested only when the corresponding feature is used.
- Touch automation is disabled until the user manually enables the accessibility service in Android Settings. Screen text is not collected for ordinary chat; it is accessed only when a message is detected as an explicit phone-control request, and proposed gestures require a separate confirmation.
- The service must describe its actions and only execute a user-requested task; it does not silently operate in the background.
- Obsidian access is granted through Android's Storage Access Framework folder picker. The app writes Markdown files only inside the selected vault.
- API keys are stored locally using an Android Keystore-backed encryption key. Do not paste keys into chat or commit them.

## Source and asset audit

- The user's Kavya-ai repository is the strongest reference for Android agent orchestration, memory retrieval, Gemini/OpenRouter providers, screen vision, and accessibility actions. Its repository does not currently declare a public source licence, so this project will not wholesale-copy it; the implementation here is independent unless the owner explicitly licenses specific files for reuse.
- The user's MYRAA MOBILE archive contains an MIT-licensed source export and a private APK. The source licence does not grant rights to third-party character models, textures, animations, or artwork. The private APK and model assets are not copied here.
- Mark-LVI is licensed CC BY-NC 4.0. Its cross-provider, avatar, and voice concepts may inform design; any future code reuse must retain attribution and the non-commercial restriction.
- Brahma-Ai-Evo uses a strict personal-use/no-distribution licence that prohibits publishing derivatives. Its source is not copied into this repository.
- model.zip contains third-party PMX/MMD character packages with individual author restrictions. They are not committed; use local import only after checking each model's permission.
- No separate memory-focused repository was supplied in the project chat. Obsidian is the chosen memory store.

See docs/ARCHITECTURE.md and docs/SOURCE_AUDIT.md for implementation boundaries.
