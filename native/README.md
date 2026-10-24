# vct_audio: native WASAPI capture

A small C++ DLL that captures audio on Windows 11. Java calls it through FFM (`com.vct.capture.WasapiBackend`).

| Source | WASAPI mechanism | Used for |
|---|---|---|
| Microphone | Shared-mode capture client | Your own voice (`Source.MIC`) |
| One app's audio (Discord) | Process loopback, `INCLUDE_TARGET_PROCESS_TREE` | Other participants (`Source.REMOTE`) |
| All audio except this app | Process loopback, `EXCLUDE_TARGET_PROCESS_TREE` | Other participants, if voice chat is not Discord |
| Everything an output device plays | Endpoint loopback | Fallback |

Windows converts every source to the requested format (16 kHz mono float), so Java receives audio that is ready for VAD and Whisper.

## Build on Windows

1. Install **Visual Studio Build Tools** (2022 or newer) with the **Desktop development with C++** workload.
   That workload includes the compiler, the Windows SDK and CMake.
2. Open **x64 Native Tools Command Prompt for VS** from the Start menu.
3. In the repository folder, run:

   ```
   cmake -S native -B native\build -A x64
   cmake --build native\build --config Release
   ```

   The result is `native\build\Release\vct_audio.dll`. It needs no Visual C++ Redistributable,
   because the C++ runtime is linked statically.

### From PowerShell (for example, the VS Code terminal)

A normal PowerShell does not know `cmake` or the compiler: only the Visual Studio prompts add them
to `PATH`. Load them into the current terminal first (this works for any Visual Studio version):

```powershell
$vs = & "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -property installationPath
& "$vs\Common7\Tools\Launch-VsDevShell.ps1" -Arch amd64 -HostArch amd64 -SkipAutomaticLocation
```

Skip this if `cmake --version` already works: the terminal is a Developer PowerShell already.

If the second line fails with *"running scripts is disabled on this system"*, PowerShell's execution
policy blocks `.ps1` files. Allow them for **this terminal only** (it resets when the window closes),
then run the two lines again:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

PowerShell also does not run programs from the current folder by name, so type `.\gradlew`
instead of `gradlew`:

```powershell
cmake -S native -B native\build -A x64
cmake --build native\build --config Release
.\gradlew test
.\gradlew captureCheck --args="--seconds 15 --remote discord"
```

## Check it on your PC

```
gradlew captureCheck --args="--list"                       # PowerShell: .\gradlew
gradlew captureCheck --args="--seconds 15 --remote discord"
```

The first command lists your microphones and outputs and says whether Discord is running. The
second records 15 seconds and shows a level meter twice per second for each source:

```
mic    ##############        -17.2 dBFS   remote ##########            -28.9 dBFS
```

It then writes `build\capture-check\mic.wav` and `remote.wav`. Listen to both: speech should be
clear, without gaps or crackling, and `remote.wav` must not contain your own voice.

Other options: `--remote others` (all audio except this app), `--remote system` (the whole default
output), `--remote none`, and `--mic <id>`, with an id from `--list`.

### If something fails

| Message | Cause and fix |
|---|---|
| `Audio library ... not found` | Build the DLL first (see above). |
| `Windows blocked microphone access` | Settings > Privacy & security > Microphone: turn on access for desktop apps. |
| `Discord is not running` | Start Discord. The check keeps retrying. |
| Meter for `remote` stays at -99 dBFS | Nothing is playing: process loopback delivers no audio while the app is silent. Join a voice channel, or play a video in Discord. |
| `0x...` code with "Unexpected audio error" | Please send me the full line. |

## Design notes

- **Pull model.** Each stream has its own capture thread inside the DLL. The thread runs at MMCSS
  "Audio" priority and fills a 2-second ring buffer. Java drains it with `vct_read`, and the DLL never
  calls into Java. If Java falls behind by more than 2 s, the oldest audio is dropped and counted in `vct_stats`.
- **COM threads.** All COM work runs on threads the DLL creates, in the multithreaded apartment.
  This is because Java threads, such as the JavaFX thread, may already be in a single-threaded apartment.
- **Errors.** Every function returns an HRESULT. `CaptureException` turns it into a message with a fix.
- **Device changes.** `IMMNotificationClient` counts device events. `CaptureLoop` polls the count
  and reopens the stream when the Windows default device changes.

## Without Windows

`stub/vct_audio_stub.c` implements the same C interface and produces a 440 Hz sine instead of
capturing. On macOS and Linux, `gradlew test` builds it automatically, so the FFM binding is tested there too.

The DLL itself can be compile-checked with MinGW-w64:

```
cmake -S native -B build-mingw -DCMAKE_SYSTEM_NAME=Windows -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++-posix
cmake --build build-mingw
```
