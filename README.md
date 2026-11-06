# Reverse Assistant

Android-first personal AI companion built with Kotlin, Jetpack Compose, Material 3, and OpenGL ES.

## Current experience

- Premium dark interface with a consistent violet/teal accent system, provider/model selector, companion panel, chat bubbles, message composer, and grouped quick tools.
- Provider switcher for Gemini, OpenAI, Claude, OpenRouter, DeepSeek, Groq, Mistral, xAI, Together AI, Fireworks, Cerebras, Perplexity, SambaNova, NVIDIA NIM, a custom OpenAI-compatible endpoint, and local llama.cpp. API keys are encrypted with Android Keystore.
- Streaming text responses through provider-specific server-sent-event endpoints.
- Local chat history (latest 120 messages), clear-chat, transcript export, and backup/restore. API keys and Android permissions are excluded from backups.
- Original procedural OpenGL ES 2.0 companion with idle movement, blinking, and TextToSpeech-synchronized mouth animation.
- Camera capture, one-shot screenshot, foreground microphone dictation, user-selected offline Vosk wake phrase recognition, and user-consented screen sampling. Android speech recognition remains the fallback when no Vosk model is imported.
- User-selected TXT, Markdown, CSV, JSON, PDF, and DOCX import. PDF text extraction is limited to 20 MB, 30 pages, and 120,000 characters; scanned/image-only PDFs fall back to on-device ML Kit OCR for Latin and Devanagari scripts.
- Ranked DuckDuckGo HTML web search with titles, snippets, and source URLs. Search depends on network access and may be rate-limited.
- Obsidian vault integration through Android's Storage Access Framework; notes are saved only after user action.
- Optional Android AccessibilityService for allow-listed phone gestures and app launching. AI-proposed plans are limited to five actions and require individual confirmation.
- Local capability switches for web search, Obsidian, phone actions, and screen context.
- PMX/MMD ZIP archive import into app-private storage with bounded extraction and path traversal checks. Imported models are catalogued but are not yet rendered, rigged, or animated.
- External MCP Streamable HTTP server manager: save trusted HTTPS endpoints, encrypt bearer tokens, discover tools, and call a selected tool only after a direct Run action.
- Optional on-device GGUF inference through the llama.cpp Android native runtime (arm64-v8a); the user imports model weights locally.
- Gemini Live screen-video session: after Android MediaProjection consent, sends downscaled JPEG screen frames to Gemini Live at approximately 1 FPS and routes user text prompts through the same session.

## Feature boundaries — read before testing

The following are **not complete**:
- PMX/MMD model rendering, skeletal rigging, VMD animation playback, physics, and lip sync for imported characters.
- Live screen video with providers other than Gemini Live. Gemini Live consumes discrete JPEG frames (about 1 FPS), not a high-frame-rate raw video stream; a Gemini API key and internet connection are required.
- A dedicated low-power keyword-spotting model. Offline Vosk speech recognition can match wake phrases after the user imports a compatible model, but it is not a specialized always-on KWS model.
- Automatic MCP tool selection or unattended tool execution. Users manually choose the server and tool, inspect arguments, and tap Run.
- Offline GGUF performance tuning across chipsets and model families. Model compatibility, RAM use, and speed depend on the imported GGUF and device.
- OCR scripts beyond Latin and Devanagari, handwriting recognition, and exact document layout reconstruction.
- Live API credential tests, provider-by-provider integration tests with real accounts, and physical-device validation.

A successful GitHub Actions build verifies compilation, unit tests, and APK packaging only. It does not prove runtime behavior on a physical device or validate provider API credentials. Provider calls require the user's own API keys and may incur charges.

## Build

Requirements: JDK 17, Android SDK Platform 35 and Build Tools, Android Gradle Plugin 8.9.2 / Gradle 8.11.1.

```bash
gradle testDebugUnitTest assembleDebug
```

Every push triggers the GitHub Actions debug build and unit tests; a debug APK artifact is uploaded when the workflow succeeds.

## Permissions and privacy

- Camera, microphone, screen capture, and accessibility are user initiated and require the relevant Android permission or OS consent.
- Wake-word and screen-capture foreground services show persistent notifications and expose Stop actions. Vosk model files are imported by the user and kept in app-private storage.
- The app does not silently capture the screen or operate the microphone in the background.
- Recognized wake commands are placed in the composer; they are not sent automatically.
- Screen frames are sampled only after Android MediaProjection consent. Gemini Live video mode sends those frames while the visible sharing session is active; normal screen mode attaches the latest frame only when the user presses Send.
- Touch automation is disabled until the user manually enables the accessibility service. Each proposed phone action requires a separate confirmation.
- Obsidian writes happen only after the user presses Save Memory and only inside the selected vault.
- API keys are stored locally using Android Keystore-backed encryption. Never commit credentials.

## Source and asset audit

- The user's Kavya-ai repository is used as an architectural reference; its repository has no declared public source licence, so code is not wholesale-copied.
- MYRAA MOBILE includes MIT-licensed source, but its third-party character models, textures, animations, and artwork have separate terms.
- Mark-LVI is CC BY-NC 4.0; any future code reuse must preserve attribution and non-commercial restrictions.
- Brahma-Ai-Evo has a personal-use/no-distribution licence; its source is not copied.
- PMX/MMD packages are not committed. Each imported model's author terms govern local use and redistribution.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/SOURCE_AUDIT.md](docs/SOURCE_AUDIT.md).
