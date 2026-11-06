# Reverse Assistant

Android-first personal AI assistant. This repository is the clean implementation target for the requirements collected in the project chats.

## Product direction

- Android-native assistant with a conversational UI.
- Provider switcher for Google Gemini, OpenAI, Anthropic Claude, and OpenRouter.
- Camera capture and one-shot Android MediaProjection screenshot capture after explicit OS consent; captured media is attached only to the next message the user sends.
- One-shot voice input plus user-started continuous dictation while the app is foregrounded; listening stops when the app is backgrounded. Spoken replies have rate/pitch controls and a stop control.
- Local chat history persisted on-device (latest 120 messages), with a clear-chat control.
- Import user-selected TXT, Markdown, CSV, JSON, PDF, or DOCX documents into the next prompt. PDF import is limited to 20 MB and 30 pages; text context is capped at 120,000 characters. Scanned PDFs need OCR, which is not included.
- Export chat transcripts as plain text.
- Persist provider/model, custom assistant personality, and speech preferences; toggle spoken replies, adjust speech rate/pitch, and stop current speech.
- Obsidian vault integration for durable Markdown memory, with query-keyword relevance retrieval.
- Optional Android AccessibilityService for explicit, user-authorized app launching by unique app label, taps, double taps, repeated taps, long presses, swipes, and vertical scrolling. Drag is available as a confirmed AI action.
- Original procedural OpenGL ES 3D companion with idle movement, blinking, and speech-synchronized mouth animation. Third-party PMX/MMD character import remains separate and license-gated.
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
