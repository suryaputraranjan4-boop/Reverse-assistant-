# Architecture

## Runtime layers

1. **Android shell** — Kotlin + Jetpack Compose. Owns lifecycle, settings, permissions and chat UI.
2. **Provider router** — one request adapter per provider: Gemini, OpenAI, Claude, OpenRouter. The user selects the provider/model; keys remain local and encrypted with Android Keystore.
3. **Vision input** — camera intent returns a user-selected photo. The same provider request can carry text plus image content. Live camera streaming is not part of this first milestone.
4. **Voice** — Android speech-recognition intent for voice input and TextToSpeech for spoken output. Background always-on listening is intentionally not enabled.
5. **Memory** — user-selected Obsidian vault via Storage Access Framework. Notes are Markdown in a dedicated Reverse Assistant folder. The user explicitly chooses what to save.
6. **Touch controls** — optional AccessibilityService. The user must enable it in Settings. Its gesture API supports tap, double tap, bounded repeated tap, long press, swipe, scroll and drag. Gesture coordinates are normalized to the current display size.
7. **Avatar** — isolated renderer boundary. A 3D PMX/MMD runtime and model import are a follow-on milestone; no restricted model assets are bundled.

## Current implementation

- Provider text/image request adapters are present.
- API key encryption and Obsidian note append are present.
- Camera photo, voice transcription and TTS are wired into the UI.
- Accessibility exposes screen text and a validated gesture allow-list. When a phone-control request is detected, the AI can propose one action; the user must review and confirm it before dispatch.
- There is no APK build result or device test result committed yet.

## Safety and consent

No accessibility permission is enabled automatically. The user grants it in Android Settings and can revoke it at any time. Camera and voice actions are initiated by visible buttons. Obsidian writes happen only after the user presses Save Memory. API keys are not sent to the repository.
