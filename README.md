# Voice Chat Translator

On-demand voice-chat translation for Windows 11 (personal use). Speech recognition, VAD and
TTS run locally; only the text of an utterance you explicitly ask to translate is sent to OpenAI.

## Status: Phase 3 (subtitles over the game)

| Component | State |
|---|---|
| Audio utilities: resampler (48/44.1 kHz → 16 kHz), downmix, frame assembler | Implemented, unit-tested |
| Utterance segmenter (VAD hysteresis, pre-roll, min length, forced split) | Implemented, unit-tested |
| Energy VAD (fallback / test double) | Implemented, unit-tested |
| Hallucination filter for Whisper output | Implemented, tested through the session tests |
| Conversation buffer (bounded by count and age) | Implemented, unit-tested |
| Feedback guard (ignores the app's own TTS heard back) | Implemented, unit-tested |
| OpenAI provider (Responses API, `store:false`, error classification) | Implemented, tested against a local mock server, **not yet against the real API** |
| Translation service (retry/backoff, deadline, cancel, dedupe, cache, runtime provider switch) | Implemented, unit-tested |
| JSONL transcripts (crash-safe append, compaction, recovery) | Implemented, unit-tested |
| Session orchestrator (Mode A: translate others, replay, retry, cancel, pause) | Implemented, end-to-end tested with fake STT/TTS/audio |
| WASAPI capture DLL: microphone, process loopback (Discord / all except this app), endpoint loopback | Implemented; compiles with MinGW and ran under Wine. **Not yet run on Windows 11** (see [native/README.md](native/README.md)) |
| FFM binding, `CaptureLoop` (reconnect, default-device follow, Discord restart, format fallback) | Implemented, unit-tested; binding tested against a native stub |
| `captureCheck` diagnostic (device list, level meters, WAV recording) | Implemented; for running on Windows |
| Silero VAD (ONNX Runtime, bundled model) | Implemented; matches the official Python wrapper frame by frame |
| Whisper via whisper.cpp 1.8.5 (`native/stt`, CUDA on Windows) | Implemented; runs on Windows 11 with CUDA (RTX 4060 Ti): 15 s of audio in under 0.9 s |
| TTS via sherpa-onnx 1.13.8 C API (Piper / MeloTTS / Kokoro voices) | Implemented; Chinese Piper voice runs on Windows 11. Kokoro and MeloTTS voices not tested |
| Headphone playback (Java Sound) | Implemented, tested with a null audio device |
| `speechCheck` diagnostic (`transcribe`, `say`, `live`) | Implemented; the `live` pipeline (capture, VAD, Whisper, OpenAI, Chinese TTS) works on Windows 11. Setup in [docs/speech-setup.md](docs/speech-setup.md) |
| API key in Windows Credential Manager (advapi32 through FFM) | Implemented; same calls verified in C under Wine. **Java test runs on Windows only** |
| Global hotkeys (RegisterHotKey message loop through FFM) | Implemented; same calls verified in C under Wine. **Java test runs on Windows only** |
| Settings (JSON in `%APPDATA%`) and session controller | Implemented, unit-tested with fake engines |
| JavaFX window, settings dialog, tray icon (`gradlew run`) | Implemented; runs on Windows 11 |
| Translate my speech: automatic, English or Japanese, into VB-CABLE (`SpeechRelay`, OpenAI speech) | Works on Windows with Discord (setup: [docs/translate-my-speech.md](docs/translate-my-speech.md)) |
| Talk live: hold Ctrl+Alt+V to send your real voice with no delay (`LiveVoice`, held hotkeys) | Works on Windows with Discord |
| Lower delay for translate my speech: streamed OpenAI voice, 450 ms end of line, warm connection | Implemented, unit-tested. **Not yet measured on Windows** |
| Subtitles over the game: others' lines translated automatically into a click-through overlay (`SubtitleOverlay`) | Implemented, unit-tested, rendered in the UI preview. **Not yet run on Windows** (see [docs/subtitles.md](docs/subtitles.md)) |

145 tests, all passing on JDK 25 (Linux x64). Without the optional native
libraries, voices and Windows, 9 of them are skipped (Whisper binding, TTS synthesis, playback). The step 2 suite
(66 tests) also passed on Windows 11.

The Discord-only capture mode (`--remote discord`) has not been tried with Discord running yet.

## Build and test

Requires JDK 23 or newer (JDK 25 LTS recommended on Windows). The Gradle wrapper downloads Gradle 9.8.1 on first use.

```
gradlew.bat test        # Windows
./gradlew test          # macOS / Linux
```

## Run the app

After the setup in [docs/speech-setup.md](docs/speech-setup.md):

```
gradlew.bat run
```

1. Open **Settings**, paste your OpenAI API key (it goes to Windows Credential Manager) and press OK.
2. Press **Start**. Loading the engines takes a few seconds.
3. When someone speaks, press **Ctrl+Alt+T**, even while a game has focus. You hear the Chinese
   translation, and it appears in the window. **Ctrl+Alt+R** replays it. Double-click a line to translate it.
4. Closing the window keeps the app in the tray, and the hotkeys keep working. Quit from the tray menu.

To see everything the others say as Chinese subtitles over the game, see
[docs/subtitles.md](docs/subtitles.md) (on by default; **Ctrl+Alt+S** shows or hides them).

To have the others hear your speech translated into English or Japanese, see
[docs/translate-my-speech.md](docs/translate-my-speech.md) (needs the free VB-CABLE driver).

## Design decisions specific to Windows 11

- **Remote audio**: WASAPI *process loopback* (`ActivateAudioInterfaceAsync` with
  `AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK`). Two modes: include the Discord process tree, or
  capture everything *except* this app's own process tree. Excluding our own process removes the
  main feedback path at the source. `FeedbackGuard` is the backup.
- **Loopback is silent when nothing plays.** WASAPI delivers no packets in that case, so the session re-anchors
  timestamps to the wall clock on every audio block instead of trusting sample counts.
- **Native layer**: a small C++ DLL with a C interface, called through Java FFM (`java.lang.foreign`), so
  there is no hand-written JNI. Device add/remove events come from `IMMNotificationClient`.
- **Speech**: Whisper via whisper.cpp with CUDA for accurate recognition; Silero VAD and Piper via
  sherpa-onnx (Java bindings). GPL components are acceptable because this is for personal use.
- **Hotkeys** (step 4): `RegisterHotKey`, not a low-level keyboard hook, to stay clear of anti-cheat.
- **API key** (step 4): Windows Credential Manager. Until then, tests use fakes and no key is read.

## Transcript format

One file per session: `session-YYYYMMDD-HHmmss-<id>.jsonl`, UTF-8.
- While the session runs, the file is an append-only log, forced to disk after every line.
- On clean close, and on the next start after a crash, it is compacted to one line per utterance:

```json
{"type":"utt","id":"r-0001","t":"…","end":"…","src":"remote","speaker":null,"lang":"de","lang_conf":0.91,"text":"Wir gehen links.","conf":0.83,"translations":[{"lang":"en","text":"We go left.","provider":"openai","model":"…","at":"…"}]}
```

`speaker` is always `null` for now: mixed loopback audio does not identify speakers reliably.

## Known limitations

- `EnergyVad` reacts to loudness, so game noise counts as speech. It is a fallback only; Silero
  replaces it in step 3.
- The resampler uses a simple box filter. It is fine for speech recognition, but it is not a high-quality audio resampler.
- The mock-server tests check the documented Responses API format. The real endpoint has not been
  called yet, and `testConnection()` against your key is the first thing to try in step 4.
- `JsonlTranscriptWriter.recover()` must run at startup, **before** a new session opens
  (it compacts every un-compacted file in the folder).
