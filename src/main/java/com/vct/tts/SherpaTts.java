package com.vct.tts;

import com.vct.util.AppHome;
import com.vct.audio.AudioClip;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * {@link TtsEngine} over sherpa-onnx's C API (offline TTS) through FFM, for Piper, MeloTTS and
 * Kokoro voices. Runs on the CPU.
 *
 * <p>The {@code language} argument of {@link #synthesize} is not used: a voice speaks the language
 * it was trained for, so pick the voice folder per language. {@code voiceId} is the speaker number
 * for voices with several speakers (Kokoro), or null for the default.
 *
 * <p>Caution: sherpa-onnx ends the whole process (it calls {@code _Exit}) on some invalid model
 * files instead of returning an error. {@link TtsVoice#detect} catches missing files beforehand.
 */
@SuppressWarnings("restricted") // calling native code is this class's purpose
public final class SherpaTts implements TtsEngine {

    /** System property naming the sherpa-onnx-c-api library file. */
    public static final String LIBRARY_PROPERTY = "vct.tts.lib";

    // SherpaOnnxOfflineTtsConfig, measured with offsetof() against c-api.h of sherpa-onnx 1.13.8 on
    // x64 Linux and x64 Windows (identical). Unset fields stay zero, which the C API reads as "default".
    static final long CONFIG_SIZE = 448;
    static final long VITS_MODEL = 0, VITS_LEXICON = 8, VITS_TOKENS = 16, VITS_DATA_DIR = 24, VITS_DICT_DIR = 48;
    static final long NUM_THREADS = 56, PROVIDER = 64;
    static final long KOKORO_MODEL = 128, KOKORO_VOICES = 136, KOKORO_TOKENS = 144, KOKORO_DATA_DIR = 152,
            KOKORO_LEXICON = 176;
    static final long RULE_FSTS = 416;
    // SherpaOnnxGeneratedAudio { const float *samples; int32_t n; int32_t sample_rate; }
    static final long AUDIO_SIZE = 16, AUDIO_SAMPLES = 0, AUDIO_N = 8, AUDIO_RATE = 12;

    private final MethodHandle generate;
    private final MethodHandle destroyAudio;
    private final MethodHandle destroy;
    private final MemorySegment tts;
    private final int sampleRate;
    private final int speakers;
    private final String version;
    private final TtsVoice voice;
    private boolean closed;

    private SherpaTts(SymbolLookup lib, TtsVoice voice, int threads) throws TtsException {
        Linker linker = Linker.nativeLinker();
        MethodHandle versionStr = bind(linker, lib, "SherpaOnnxGetVersionStr", FunctionDescriptor.of(ADDRESS));
        MethodHandle create = bind(linker, lib, "SherpaOnnxCreateOfflineTts", FunctionDescriptor.of(ADDRESS, ADDRESS));
        MethodHandle rate = bind(linker, lib, "SherpaOnnxOfflineTtsSampleRate", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        MethodHandle numSpeakers = bind(linker, lib, "SherpaOnnxOfflineTtsNumSpeakers", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        generate = bind(linker, lib, "SherpaOnnxOfflineTtsGenerate",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_FLOAT));
        destroyAudio = bind(linker, lib, "SherpaOnnxDestroyOfflineTtsGeneratedAudio", FunctionDescriptor.ofVoid(ADDRESS));
        destroy = bind(linker, lib, "SherpaOnnxDestroyOfflineTts", FunctionDescriptor.ofVoid(ADDRESS));
        this.voice = voice;

        version = ((MemorySegment) invoke(versionStr)).reinterpret(64).getString(0, StandardCharsets.UTF_8);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment c = arena.allocate(CONFIG_SIZE, 8); // zeroed
            if (voice.kind() == TtsVoice.Kind.KOKORO) {
                put(c, KOKORO_MODEL, voice.model(), arena);
                put(c, KOKORO_VOICES, voice.voices(), arena);
                put(c, KOKORO_TOKENS, voice.tokens(), arena);
                put(c, KOKORO_DATA_DIR, voice.dataDir(), arena);
                put(c, KOKORO_LEXICON, joined(voice.lexicons()), arena);
            } else {
                put(c, VITS_MODEL, voice.model(), arena);
                put(c, VITS_TOKENS, voice.tokens(), arena);
                put(c, VITS_LEXICON, joined(voice.lexicons()), arena);
                put(c, VITS_DATA_DIR, voice.dataDir(), arena);
                put(c, VITS_DICT_DIR, voice.dictDir(), arena);
            }
            c.set(JAVA_INT, NUM_THREADS, threads);
            c.set(ADDRESS, PROVIDER, arena.allocateFrom("cpu"));
            put(c, RULE_FSTS, joined(voice.ruleFsts()), arena);
            tts = (MemorySegment) invoke(create, c);
        }
        if (tts.equals(MemorySegment.NULL)) {
            throw new TtsException("sherpa-onnx could not load the voice in " + voice.dir().toAbsolutePath()
                    + ". Check that the folder is complete; details are on the console.");
        }
        sampleRate = (int) invoke(rate, tts);
        speakers = (int) invoke(numSpeakers, tts);
    }

    /**
     * Loads the library from {@code -Dvct.tts.lib}, or from {@code native/sherpa-onnx} in the
     * {@link AppHome app folder}, then the voice.
     */
    public static SherpaTts load(Path voiceDir) throws TtsException {
        String configured = System.getProperty(LIBRARY_PROPERTY);
        Path lib = configured != null ? Path.of(configured)
                : AppHome.resolve("native", "sherpa-onnx", System.mapLibraryName("sherpa-onnx-c-api"));
        return load(lib, voiceDir, 2);
    }

    public static SherpaTts load(Path library, Path voiceDir, int threads) throws TtsException {
        TtsVoice voice = TtsVoice.detect(voiceDir);
        if (!Files.isRegularFile(library)) {
            throw new TtsException("sherpa-onnx library not found: " + library.toAbsolutePath()
                    + ". Download it (see docs/speech-setup.md) or pass -D" + LIBRARY_PROPERTY + "=<path>.");
        }
        // Load ONNX Runtime through its Java binding first. Windows keeps one onnxruntime.dll per
        // process, and sherpa-onnx then uses that one (same minor version, see build.gradle.kts).
        ai.onnxruntime.OrtEnvironment.getEnvironment();
        SymbolLookup lib;
        try {
            lib = SymbolLookup.libraryLookup(library.toAbsolutePath(), Arena.global());
        } catch (IllegalArgumentException e) {
            throw new TtsException("Could not load " + library.toAbsolutePath() + ": " + e.getMessage());
        }
        return new SherpaTts(lib, voice, threads);
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int numSpeakers() {
        return speakers;
    }

    /** The sherpa-onnx version of the loaded library, e.g. "1.13.8". */
    public String version() {
        return version;
    }

    public TtsVoice voice() {
        return voice;
    }

    @Override
    public synchronized AudioClip synthesize(String text, String language, String voiceId, float speed) throws TtsException {
        if (closed) throw new TtsException("Speech synthesis is closed");
        if (text == null || text.isBlank()) return new AudioClip(new float[0], sampleRate);
        int sid = speaker(voiceId);
        if (!(speed > 0.25f && speed < 4f)) throw new IllegalArgumentException("speed out of range: " + speed);
        MemorySegment audio;
        try (Arena arena = Arena.ofConfined()) {
            audio = (MemorySegment) invoke(generate, tts, arena.allocateFrom(text, StandardCharsets.UTF_8), sid, speed);
        }
        if (audio.equals(MemorySegment.NULL)) throw new TtsException("sherpa-onnx failed to synthesize the text");
        try {
            MemorySegment a = audio.reinterpret(AUDIO_SIZE);
            int n = a.get(JAVA_INT, AUDIO_N);
            int rate = a.get(JAVA_INT, AUDIO_RATE);
            float[] samples = new float[Math.max(n, 0)];
            if (n > 0) {
                MemorySegment.copy(a.get(ADDRESS, AUDIO_SAMPLES).reinterpret((long) n * Float.BYTES), JAVA_FLOAT, 0,
                        samples, 0, n);
            }
            return new AudioClip(samples, rate);
        } finally {
            invoke(destroyAudio, audio);
        }
    }

    int speaker(String voiceId) throws TtsException {
        if (voiceId == null || voiceId.isBlank()) return 0;
        int sid;
        try {
            sid = Integer.parseInt(voiceId.strip());
        } catch (NumberFormatException e) {
            throw new TtsException("Voice id must be a speaker number, got \"" + voiceId + "\"");
        }
        if (sid < 0 || sid >= Math.max(speakers, 1)) {
            throw new TtsException("Speaker " + sid + " does not exist; this voice has " + speakers + " speaker(s), 0 to "
                    + (speakers - 1) + ".");
        }
        return sid;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        invoke(destroy, tts);
    }

    private static String joined(List<Path> paths) {
        return paths.isEmpty() ? null : paths.stream().map(p -> p.toAbsolutePath().toString()).collect(Collectors.joining(","));
    }

    private static void put(MemorySegment config, long offset, Object value, Arena arena) {
        if (value == null) return; // stays NULL
        String s = value instanceof Path p ? p.toAbsolutePath().toString() : value.toString();
        config.set(ADDRESS, offset, arena.allocateFrom(s, StandardCharsets.UTF_8));
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lib, String name, FunctionDescriptor fd) {
        MemorySegment symbol = lib.find(name)
                .orElseThrow(() -> new IllegalArgumentException("missing symbol " + name + " (different sherpa-onnx version?)"));
        return linker.downcallHandle(symbol, fd);
    }

    private static Object invoke(MethodHandle h, Object... args) {
        try {
            return h.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t); // downcalls do not throw checked exceptions
        }
    }
}
