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
- Persist provider/model and speech preferences; toggle spoken replies, adjust speech rate/pitch, and stop current speech.
- Obsidian vault integration for durable Markdown memory, with query-keyword relevance retrieval.
- Optional Android AccessibilityService for explicit, user-authorized app launching by unique app label, taps, double taps, repeated taps, long presses, swipes, and vertical scrolling. Drag is available as a confirmed AI action.
- Original procedural OpenGL ES 3D companion with idle movement, blinking, and speech-synchronized mouth animation. Third-party PMX/MMD character import remains separate and license-gated.
- API credentials encrypted with Android Keystore and never committed to Git.

## Status

Chat history persistence and the AI drag action have now been added. Implemented in this branch: streaming text responses for Gemini/OpenAI/Claude/OpenRouter, foreground continuous dictation (no wake word), one-shot OS-consented screen capture, user-selected TXT/Markdown/CSV/JSON import, chat export, speech controls, and keyword-ranked Obsidian memory retrieval. Still pending: importing the user-supplied PMX/MMD character model and a full skeletal rig, continuous live screen streaming, multi-step device orchestration, scanned-PDF OCR, integrated web search, plugin integrations, robust provider compatibility tests, and real-device validation.

The latest feature batch is awaiting its GitHub Actions build. The earlier successful CI run (42193647302) predates these changes and is not evidence that the current branch builds. A successful CI build is not proof of physical-device compatibility. Provider calls require the user's own API keys and may incur provider charges. Physical-device testing and several advanced modules are still pending.

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
