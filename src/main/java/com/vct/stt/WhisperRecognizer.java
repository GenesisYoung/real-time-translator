package com.vct.stt;

import com.vct.util.AppHome;
import com.vct.audio.PcmSegment;
import com.vct.util.Json;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * {@link SpeechRecognizer} over whisper.cpp, through the native vct_stt library and FFM.
 *
 * <p>With more than one allowed language, Whisper detects the language but may only choose among
 * the allowed ones (e.g. English or Japanese); with one, that language is forced.
 *
 * <p>Calls are serialized: one Whisper context decodes one utterance at a time. Loading a large
 * model takes a few seconds and about 1 GB of (GPU) memory, so load it once per app run.
 */
@SuppressWarnings("restricted") // calling native code is this class's purpose
public final class WhisperRecognizer implements SpeechRecognizer {

    /** System property naming the vct_stt library file. */
    public static final String LIBRARY_PROPERTY = "vct.stt.lib";
    static final int API_VERSION = 1;

    /** One transcription with the time spent in each stage, for diagnostics. */
    public record Transcription(SttResult result, long detectMillis, long decodeMillis) {}

    private final MethodHandle transcribe;
    private final MethodHandle result;
    private final MethodHandle close;
    private final MemorySegment handle;
    private final int threads;
    private final String systemInfo;
    private boolean closed;

    private WhisperRecognizer(SymbolLookup lib, Path model, boolean useGpu, int threads) throws SttException {
        Linker linker = Linker.nativeLinker();
        MethodHandle apiVersion = bind(linker, lib, "vct_stt_api_version", FunctionDescriptor.of(JAVA_INT));
        MethodHandle info = bind(linker, lib, "vct_stt_system_info", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        MethodHandle open = bind(linker, lib, "vct_whisper_open",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        transcribe = bind(linker, lib, "vct_whisper_transcribe",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        result = bind(linker, lib, "vct_whisper_result", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        close = bind(linker, lib, "vct_whisper_close", FunctionDescriptor.ofVoid(ADDRESS));
        this.threads = threads;

        int v = (int) invoke(apiVersion);
        if (v != API_VERSION) {
            throw new SttException("Speech library has API version " + v + ", this app needs " + API_VERSION
                    + ". Rebuild native/stt.");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(2048);
            invoke(info, buf, 2048);
            systemInfo = buf.getString(0, StandardCharsets.UTF_8).strip();

            MemorySegment out = arena.allocate(ADDRESS);
            MemorySegment error = arena.allocate(8192);
            int rc = (int) invoke(open, arena.allocateFrom(model.toAbsolutePath().toString(), StandardCharsets.UTF_8),
                    useGpu ? 1 : 0, out, error, 8192);
            if (rc != 0) throw new SttException(error.getString(0, StandardCharsets.UTF_8).strip());
            handle = out.get(ADDRESS, 0);
        }
    }

    /**
     * Loads the library from {@code -Dvct.stt.lib}, or from {@code native/stt/build/Release} (Windows)
     * or {@code native/stt/build} (other systems), then the model.
     */
    public static WhisperRecognizer open(Path model, boolean useGpu) throws SttException {
        String configured = System.getProperty(LIBRARY_PROPERTY);
        Path lib = configured != null ? Path.of(configured)
                : System.getProperty("os.name").startsWith("Windows")
                ? AppHome.resolve("native", "stt", "build", "Release", "vct_stt.dll")
                : AppHome.resolve("native", "stt", "build", "vct_stt" + (System.getProperty("os.name").startsWith("Mac") ? ".dylib" : ".so"));
        return open(lib, model, useGpu, defaultThreads());
    }

    public static WhisperRecognizer open(Path library, Path model, boolean useGpu, int threads) throws SttException {
        if (!Files.isRegularFile(library)) {
            throw new SttException("Speech library not found: " + library.toAbsolutePath()
                    + ". Build it (see docs/speech-setup.md) or pass -D" + LIBRARY_PROPERTY + "=<path>.");
        }
        if (!Files.isRegularFile(model)) {
            throw new SttException("Whisper model not found: " + model.toAbsolutePath()
                    + ". Download it (see docs/speech-setup.md).");
        }
        SymbolLookup lib;
        try {
            // Global arena: the library stays loaded for the life of the JVM.
            lib = SymbolLookup.libraryLookup(library.toAbsolutePath(), Arena.global());
        } catch (IllegalArgumentException e) {
            throw new SttException("Could not load " + library.toAbsolutePath() + ": " + e.getMessage()
                    + (library.toString().endsWith(".dll") ? ". If the CUDA Toolkit is not installed, its DLLs are missing." : ""));
        }
        return new WhisperRecognizer(lib, model, useGpu, threads);
    }

    /** Half the cores, at most 8: enough for the CPU parts of GPU inference, leaves room for the game. */
    static int defaultThreads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 8);
    }

    /** whisper.cpp build features, e.g. whether CUDA is compiled in. */
    public String systemInfo() {
        return systemInfo;
    }

    @Override
    public SttResult transcribe(PcmSegment segment, Set<String> allowedLanguages) throws SttException {
        if (segment.sampleRate() != 16_000) throw new IllegalArgumentException("Whisper needs 16 kHz audio");
        return transcribe(segment.samples(), allowedLanguages).result();
    }

    /** Transcribes mono 16 kHz samples. */
    public synchronized Transcription transcribe(float[] samples, Set<String> allowedLanguages) throws SttException {
        if (closed) throw new SttException("Speech recognizer is closed");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pcm = arena.allocateFrom(JAVA_FLOAT, samples);
            MemorySegment size = arena.allocate(JAVA_INT);
            int rc = (int) invoke(transcribe, handle, pcm, samples.length,
                    arena.allocateFrom(languageList(allowedLanguages), StandardCharsets.UTF_8), threads, size);
            if (rc != 0) throw new SttException("Whisper failed to transcribe (code " + rc + ")");
            int n = size.get(JAVA_INT, 0);
            MemorySegment json = arena.allocate(n);
            rc = (int) invoke(result, handle, json, n);
            if (rc != 0) throw new SttException("Could not read the Whisper result (code " + rc + ")");
            return parse(json.getString(0, StandardCharsets.UTF_8));
        }
    }

    /** BCP-47 tags ("ja-JP", "zh-Hans") to Whisper's ISO 639-1 codes, sorted: "en,ja,zh". */
    static String languageList(Set<String> tags) {
        Set<String> codes = new TreeSet<>();
        for (String t : tags) {
            String code = Locale.forLanguageTag(t).getLanguage();
            if (!code.isEmpty()) codes.add(code);
        }
        return String.join(",", codes);
    }

    static Transcription parse(String json) {
        Map<String, Object> m = Json.parseObject(json);
        SttResult r = new SttResult(((String) m.get("text")).strip(), (String) m.get("language"),
                number(m, "language_probability"), number(m, "confidence"), number(m, "no_speech_probability"));
        return new Transcription(r, (long) number(m, "detect_ms"), (long) number(m, "decode_ms"));
    }

    private static double number(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        invoke(close, handle);
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lib, String name, FunctionDescriptor fd) {
        MemorySegment symbol = lib.find(name)
                .orElseThrow(() -> new IllegalArgumentException("missing symbol " + name + " (old library build?)"));
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
