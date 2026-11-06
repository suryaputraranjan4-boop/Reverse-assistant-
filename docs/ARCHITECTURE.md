# Architecture

## Runtime layers

1. **Android shell and UI** — Kotlin + Jetpack Compose Material 3. Owns lifecycle, navigation surfaces, settings, permissions, and chat UI. The current visual system uses a dark navy palette with violet/teal accents, a provider card, companion panel, chat surface, and grouped composer tools.
2. **Provider router** — request adapters for Gemini, OpenAI, Claude, and OpenRouter. Streaming uses provider-specific SSE endpoints. API keys remain local and are encrypted with Android Keystore.
3. **Vision input** — camera photos and OS-consented MediaProjection frames can be attached to the next user-sent message. The screen service samples frames locally while active; it does not stream continuous video to the model.
4. **Voice** — Android speech recognition for one-shot input and foreground dictation, TextToSpeech for output, and a foreground wake-phrase service. Wake phrases use the configured Android speech-recognition provider rather than a dedicated offline keyword model. Commands are placed in the composer and are not auto-submitted.
5. **Memory** — user-selected Obsidian vault via Storage Access Framework. Notes are Markdown in a dedicated Reverse Assistant folder. The user explicitly chooses what to save; retrieval is lexical keyword ranking, not vector embeddings.
6. **Touch controls** — optional AccessibilityService. The user must enable it in Settings. It supports allow-listed app launching and gestures. AI plans are capped at five actions and each action needs a separate confirmation.
7. **Avatar** — original procedural OpenGL ES 2.0 renderer with idle movement, blinking, and TextToSpeech-linked mouth movement. PMX/MMD ZIP files can be safely extracted to app-private storage, but imported skeletal models are not rendered, rigged, or animated.
8. **Built-in capabilities** — local toggles for web search, Obsidian, phone actions, and screen context. These are not external MCP/plugin-server connections.

## Current implementation

- Chat history, transcript export, backup/restore, provider/model preferences, encrypted API-key storage, and assistant style preferences.
- Provider streaming adapters and one-shot image input for supported providers. Provider-specific live compatibility still needs broader validation.
- Ranked DuckDuckGo HTML search with source links; subject to network availability and rate limits.
- Bounded TXT/Markdown/CSV/JSON/PDF/DOCX extraction. PDF import is capped at 20 MB, 30 pages, and 120,000 output characters. Scanned PDFs fall back to on-device ML Kit OCR for Latin and Devanagari scripts.
- MediaProjection screen sampling with explicit OS consent, foreground notification, and Stop action. Latest frame is attached only to the next message the user sends.
- Wake phrase service with explicit start/stop and persistent notification. Recognition may rely on Android's speech provider/network.
- Accessibility actions are allow-listed, user-requested, and individually confirmed. Failed or cancelled steps stop the remaining plan.
- GitHub Actions builds and unit tests are the current automated validation. Physical-device testing and provider credential/API tests are not performed by CI.

## Known incomplete work

- Imported PMX/MMD rendering, skeletal rigging, animation retargeting, and imported-model lip sync.
- Direct live screen-video streaming to an AI provider.
- Optional Vosk offline wake-phrase recognition with a user-imported model; Android SpeechRecognizer remains the fallback when no offline model is installed.
- OCR scripts beyond Latin and Devanagari, handwriting recognition, and exact document layout reconstruction.
- External MCP/plugin server adapters and user-configurable third-party connectors.
- Broad provider compatibility and physical-device testing.

## Safety and consent

No accessibility permission is enabled automatically. The user grants it in Android Settings and can revoke it at any time. Camera, microphone, and screen capture are initiated through visible controls and OS permission flows. Wake-word and screen-capture foreground services show persistent notifications and explicit Stop actions. Obsidian writes happen only after user action. API keys are not committed to the repository.

## Avatar renderer

The app includes an original procedural OpenGL ES 2.0 companion built from generated sphere meshes. It has subtle idle rotation, periodic blinking, and mouth animation driven by Android TextToSpeech utterance callbacks. It does not load PMX/MMD packages; that format needs a dedicated runtime/conversion pipeline and model assets cleared for use.

## Document import

- TXT/Markdown/CSV/JSON import is capped at 1.5 MB raw input.
- PDF text extraction uses PDFBox-Android, caps files at 20 MB and extraction at 30 pages / 120,000 characters. Image-only/scanned pages are rendered locally and OCR'd with bundled ML Kit Latin and Devanagari models.
- DOCX extraction reads the main WordprocessingML document text; embedded objects, comments, tracked changes, and complex layout are not preserved.
- Parsing runs on an IO dispatcher. Selected document text is included only in the next message sent by the user.
