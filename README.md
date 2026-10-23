# Voice Chat Translator

On-demand voice-chat translation for Windows 11 (personal use). Speech recognition, VAD and
TTS run locally; only the text of an utterance you explicitly ask to translate is sent to OpenAI.

## Status: Phase 1, step 1 of 4 (portable core)

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
| **WASAPI capture: mic + process loopback (native DLL + FFM)** | Not started (step 2) |
| **Silero VAD, Whisper (CUDA), Piper TTS through sherpa-onnx / whisper.cpp** | Not started (step 3) |
| **JavaFX UI, tray, hotkey, keychain storage (Windows Credential Manager), settings** | Not started (step 4) |

44 tests, all passing on JDK 23 (macOS arm64). They have not yet been run on Windows.

## Build and test

Requires JDK 23 or newer (JDK 25 LTS recommended on Windows). The Gradle wrapper downloads Gradle on first use.

```
gradlew.bat test        # Windows
./gradlew test          # macOS / Linux
```

There is no runnable app yet: step 1 is the library core plus its tests.

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
