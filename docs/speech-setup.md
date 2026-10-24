# Speech engines: setup on Windows 11

The translator uses three local speech engines. This page installs them, then checks them one at a time.

| Engine | Job | Runs on | Comes from |
|---|---|---|---|
| **Silero VAD** | Detects when someone is speaking | CPU | Already included (nothing to do) |
| **Whisper** (`large-v3-turbo`) | Speech to text, English / Japanese / Chinese | NVIDIA GPU | You build `vct_stt.dll` and download the model |
| **Piper voice** (`zh_CN-huayan`) | Reads translations aloud in Chinese | CPU | You download sherpa-onnx and the voice |

Run all commands in the **repository folder** in a **Developer PowerShell for VS**. In Visual Studio,
that's **Tools → Command Line → Developer PowerShell**. For a plain PowerShell, load it as described in
[native/README.md](../native/README.md).

After every step, the folder looks like this:

```
real-time-translator\
├── native\stt\build\Release\vct_stt.dll                 (step 2)
├── native\sherpa-onnx\sherpa-onnx-c-api.dll, onnxruntime.dll, ...   (step 4)
├── models\whisper\ggml-large-v3-turbo-q8_0.bin           (step 3)
└── models\tts\vits-piper-zh_CN-huayan-medium\            (step 5)
```

`models\` and `native\sherpa-onnx\` are in `.gitignore`, so these large files are never committed.

## 1. Install the CUDA Toolkit

Whisper uses your RTX 4060 Ti through CUDA. Building the CUDA code requires NVIDIA's CUDA Toolkit.

1. Download the latest version for **Windows x86_64** from https://developer.nvidia.com/cuda-downloads.
2. Install it **after** Visual Studio, with the default components. The installer adds
   **Visual Studio Integration** only for Visual Studio versions it finds. Visual Studio 2026 (its
   folder is `Microsoft Visual Studio\18`) needs a recent CUDA release that lists VS 2026 support,
   so take the newest one.
3. Check it in a **new** terminal: `nvcc --version` prints the version.

The installer also puts the CUDA runtime DLLs on `PATH`, and `vct_stt.dll` needs them when it runs.

## 2. Build the Whisper library

```powershell
cmake -S native\stt -B native\stt\build -A x64
cmake --build native\stt\build --config Release
```

- The first command downloads whisper.cpp 1.8.5 from GitHub.
- The second compiles it, including the CUDA code for RTX 40 GPUs (compute capability 8.9).
  **The first build takes about 10 to 30 minutes.** Later builds are fast.
- The result is `native\stt\build\Release\vct_stt.dll`.

Without a GPU, or to compare: add `-DVCT_CUDA=OFF` to the first command (and use a new build folder).

## 3. Download the Whisper model

Download **`ggml-large-v3-turbo-q8_0.bin`** (about 0.9 GB) into `models\whisper\`:

```powershell
New-Item -ItemType Directory -Force models\whisper | Out-Null
Invoke-WebRequest https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin -OutFile models\whisper\ggml-large-v3-turbo-q8_0.bin
```

`q8_0` is 8-bit quantized: about half the GPU memory of the full model, with nearly the same accuracy.
That leaves room for games on your 8 GB card.

> I could not open Hugging Face from my build environment, so this link is not verified. If it fails,
> open https://huggingface.co/ggerganov/whisper.cpp/tree/main, download `ggml-large-v3-turbo-q8_0.bin`
> (or `ggml-large-v3-turbo-q5_0.bin`, smaller), and pass `--whisper <file>` if the name differs.

## 4. Download sherpa-onnx (the TTS engine)

Version **1.13.8** exactly: the app fills sherpa-onnx's C structures at fixed offsets, measured
for this version. This npm package contains just the Windows DLLs. I inspected this exact file,
SHA-256 `fe522f02a5c113c2567a43982107ef41ae517f9a431931e90731e7e4d4341073`.

```powershell
Invoke-WebRequest https://registry.npmjs.org/sherpa-onnx-win-x64/-/sherpa-onnx-win-x64-1.13.8.tgz -OutFile sherpa.tgz
(Get-FileHash sherpa.tgz -Algorithm SHA256).Hash   # must match the value above
tar -xzf sherpa.tgz
New-Item -ItemType Directory -Force native\sherpa-onnx | Out-Null
Move-Item package\*.dll native\sherpa-onnx\
Remove-Item -Recurse package, sherpa.tgz
```

## 5. Download the Chinese voice

```powershell
New-Item -ItemType Directory -Force models\tts | Out-Null
Invoke-WebRequest https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-huayan-medium.tar.bz2 -OutFile voice.tar.bz2
tar -xjf voice.tar.bz2 -C models\tts
Remove-Item voice.tar.bz2
```

The folder `models\tts\vits-piper-zh_CN-huayan-medium\` must contain a `.onnx` file, `tokens.txt` and
`espeak-ng-data\`.

> This link is not verified either (GitHub downloads were blocked for me). If it fails, find
> `vits-piper-zh_CN-huayan-medium.tar.bz2` under **tts-models** on
> https://github.com/k2-fsa/sherpa-onnx/releases.

I chose this Piper voice for the first run because it uses exactly the code path I could test (an English
Piper voice through the same sherpa-onnx library). Better-sounding Chinese voices (Kokoro, MeloTTS)
work with `--voice <folder>` too, but are not tested yet.

## 6. Check, one engine at a time

**a. Tests.** These now include the Whisper tests against your `vct_stt.dll`:

```powershell
.\gradlew test
```

**b. Whisper on a recording.** `remote.wav` from the capture check has the video's English speech:

```powershell
.\gradlew speechCheck --args="transcribe build\capture-check\remote.wav"
```

Expected: `CUDA` appears in the build line, the text matches what was said, and **Run 2** decodes a few seconds of audio
in well under a second. Run 1 is slower because the GPU warms up.

**c. The Chinese voice** (listen in your headphones):

```powershell
.\gradlew speechCheck --args="say 我们应该往左走。"
```

**d. Everything together.** Set your OpenAI key for this terminal, then start without a model to list the models you can use:

```powershell
$env:OPENAI_API_KEY = "sk-..."
.\gradlew speechCheck --args="live --remote others"
.\gradlew speechCheck --args="live --remote others --openai-model <name from the list>"
```

Play a video or join voice chat. Each recognized line appears with its language. Press **Enter** to
translate the latest remote line into Chinese and hear it. **r** + Enter replays it, and **q** + Enter quits.
Choose a fast **non-reasoning** model: reasoning models think before answering, which adds seconds.

Your API key is only read from the environment for this test. The app stores it in Windows
Credential Manager later (step 4 of Phase 1).

## If something fails

| Message | Cause and fix |
|---|---|
| CMake: `No CUDA toolset found` | The CUDA Toolkit's Visual Studio integration is missing. Reinstall CUDA after Visual Studio (step 1). |
| CMake: `unsupported Microsoft Visual Studio version` | This CUDA version doesn't support your Visual Studio. Install a newer CUDA Toolkit. |
| `Could not load ...vct_stt.dll` | Usually missing CUDA runtime DLLs. Open a **new** terminal after installing CUDA, so `PATH` is updated. |
| `WARNING: this build has no CUDA support` | The library was built without CUDA. Rebuild after installing the toolkit, in a new build folder. |
| `whisper.cpp could not load the model` | The model file is incomplete or wrong. Download it again. |
| The app closes **without any message** while loading the voice | sherpa-onnx ends the whole process on some broken voice files. Download the voice folder again. |
| `Speaker N does not exist` | The voice has fewer speakers. Piper voices have one (speaker 0). |

Please send me the full output of anything that fails, and the timings from 6b.
