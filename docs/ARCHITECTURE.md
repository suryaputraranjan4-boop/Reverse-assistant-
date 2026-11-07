# Architecture

## Runtime layers

1. **Android shell and UI** — Kotlin + Jetpack Compose Material 3. Owns lifecycle, navigation surfaces, settings, permissions, and chat UI. The current visual system uses a dark navy palette with violet/teal accents, a provider card, companion panel, chat surface, and grouped composer tools.
2. **Provider router** — request adapters for Gemini, OpenAI, Claude, OpenRouter, DeepSeek, Groq, Mistral, xAI, Together AI, Fireworks, Cerebras, Perplexity, SambaNova, NVIDIA NIM, custom OpenAI-compatible endpoints, and local llama.cpp. Cloud API keys remain local and are encrypted with Android Keystore.
3. **Vision input** — camera photos and OS-consented MediaProjection frames can be attached to the next user-sent message. A separate Gemini Live mode sends downscaled JPEG frames over a WebSocket at approximately 1 FPS while the user-visible screen-share session is active. Its outbound queue is capped at 2 MiB, individual JPEGs are capped at 1 MiB, and frames are dropped under backpressure to favor fresh screen context. Other providers use ordinary chat requests.
4. **Voice** — Android speech recognition for one-shot input and foreground dictation, TextToSpeech for output, and a foreground wake-phrase service. A user-imported Vosk model enables offline speech recognition and phrase matching; Android SpeechRecognizer is the fallback. This is not a specialized low-power keyword-spotting model. Commands are placed in the composer and are not auto-submitted.
5. **Memory** — user-selected Obsidian vault via Storage Access Framework. Notes are Markdown in a dedicated Reverse Assistant folder. The user explicitly chooses what to save; retrieval is lexical keyword ranking, not vector embeddings.
6. **Touch controls** — optional AccessibilityService. The user must enable it in Settings. It supports allow-listed app launching and gestures. AI plans are capped at five actions and each action needs a separate confirmation.
7. **Avatar** — original procedural OpenGL ES 2.0 renderer with idle movement, blinking, and TextToSpeech-linked mouth movement. A separate Assimp-backed Android native module imports PMX/PMD geometry and renders a static triangle mesh with diffuse material colors in the companion viewport. Skeletons, VMD motion, physics, and textures are not yet rendered.
8. **Capabilities and MCP** — local toggles for web search, Obsidian, phone actions, and screen context. A separate user-managed MCP Streamable HTTP dialog stores trusted HTTPS endpoints, encrypts bearer tokens, discovers tools, and runs a selected tool only after the user enters arguments and taps Run.

## Current implementation

- Chat history, transcript export, backup/restore, provider/model preferences, encrypted API-key storage, and assistant style preferences.
- Provider streaming adapters and one-shot image input for supported providers. Real credential/API compatibility still needs provider-by-provider validation.
- External MCP Streamable HTTP server registry, tool discovery, and explicit user-triggered tool calls.
- Offline llama.cpp inference for imported GGUF models on arm64-v8a devices; model weights are not bundled.
- Ranked DuckDuckGo HTML search with source links; subject to network availability and rate limits.
- Bounded TXT/Markdown/CSV/JSON/PDF/DOCX extraction. PDF import is capped at 20 MB, 30 pages, and 120,000 output characters. Scanned PDFs fall back to on-device ML Kit OCR for Latin and Devanagari scripts.
- MediaProjection screen sampling with explicit OS consent, foreground notification, and Stop action. Standard mode attaches the latest frame to the next user message; Gemini Live mode streams discrete JPEG frames at approximately 1 FPS.
- Wake phrase service with explicit start/stop and persistent notification. A compatible user-imported Vosk model enables local recognition; Android SpeechRecognizer is the fallback.
- Accessibility actions are allow-listed, user-requested, and individually confirmed. Failed or cancelled steps stop the remaining plan.
- GitHub Actions builds and unit tests are the current automated validation. Physical-device testing, live provider credentials, and external MCP server tests are not performed by CI.

## Known incomplete work

- Imported PMX/MMD skeletal rigging, bone/morph animation, VMD playback, physics, texture-map rendering, and imported-model lip sync. Static mesh geometry and diffuse material colors are supported by the preview.
- Gemini Live supports discrete screen frames at approximately 1 FPS; high-frame-rate video and live video on other providers are not implemented.
- A specialized low-power offline keyword-spotting engine; current Vosk mode performs offline speech recognition and matches wake phrases.
- OCR scripts beyond Latin and Devanagari, handwriting recognition, and exact document layout reconstruction.
- Automatic MCP tool routing, background tool execution, and server-specific OAuth flows.
- Broad provider compatibility and physical-device testing.

## Safety and consent

No accessibility permission is enabled automatically. The user grants it in Android Settings and can revoke it at any time. Camera, microphone, and screen capture are initiated through visible controls and OS permission flows. Wake-word and screen-capture foreground services show persistent notifications and explicit Stop actions. Obsidian writes happen only after user action. API keys are not committed to the repository.

## Avatar renderer

The app includes an original procedural OpenGL ES 2.0 companion built from generated sphere meshes. It has subtle idle rotation, periodic blinking, and mouth animation driven by Android TextToSpeech utterance callbacks. When the user imports a PMX/PMD archive, the Assimp MMD importer extracts static triangles and diffuse material colors for preview. Bone transforms, morphs, VMD animation, physics, and texture maps are not yet applied. The user remains responsible for each model's usage licence.

## Document import

- TXT/Markdown/CSV/JSON import is capped at 1.5 MB raw input.
- PDF text extraction uses PDFBox-Android, caps files at 20 MB and extraction at 30 pages / 120,000 characters. Image-only/scanned pages are rendered locally and OCR'd with bundled ML Kit Latin and Devanagari models.
- DOCX extraction reads the main WordprocessingML document text; embedded objects, comments, tracked changes, and complex layout are not preserved.
- Parsing runs on an IO dispatcher. Selected document text is included only in the next message sent by the user.
