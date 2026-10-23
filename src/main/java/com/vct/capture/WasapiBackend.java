package com.vct.capture;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@link CaptureBackend} over the native vct_audio library (WASAPI) through FFM.
 *
 * <p>The JVM needs {@code --enable-native-access=ALL-UNNAMED}, otherwise Java prints a warning on
 * first use (and future JDKs will refuse).
 */
@SuppressWarnings("restricted") // calling native code is this class's purpose
public final class WasapiBackend implements CaptureBackend {

    /** System property naming the library file; otherwise {@code vct_audio.dll} is searched (see {@link #load()}). */
    public static final String LIBRARY_PROPERTY = "vct.audio.lib";

    static final int API_VERSION = 1;
    private static final int VCT_E_BUFFER_TOO_SMALL = 0x8007007A;

    private final MethodHandle apiVersion;
    private final MethodHandle listDevices;
    private final MethodHandle open;
    private final MethodHandle read;
    private final MethodHandle stats;
    private final MethodHandle close;
    private final MethodHandle deviceChanges;
    private final MethodHandle errorMessage;
    private final MethodHandle shutdown;

    private WasapiBackend(SymbolLookup lib) {
        Linker linker = Linker.nativeLinker();
        apiVersion = bind(linker, lib, "vct_api_version", FunctionDescriptor.of(JAVA_INT));
        listDevices = bind(linker, lib, "vct_list_devices",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        open = bind(linker, lib, "vct_open",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        read = bind(linker, lib, "vct_read",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        stats = bind(linker, lib, "vct_stats",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        close = bind(linker, lib, "vct_close", FunctionDescriptor.ofVoid(ADDRESS));
        deviceChanges = bind(linker, lib, "vct_device_changes", FunctionDescriptor.of(JAVA_LONG));
        errorMessage = bind(linker, lib, "vct_error_message",
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        shutdown = bind(linker, lib, "vct_shutdown", FunctionDescriptor.ofVoid());
    }

    /**
     * Loads the library from {@code -Dvct.audio.lib=<file>}, else {@code native/vct_audio.dll} or
     * {@code vct_audio.dll} in the working directory, else from the system library path.
     */
    public static WasapiBackend load() throws CaptureException {
        String configured = System.getProperty(LIBRARY_PROPERTY);
        if (configured != null) return load(Path.of(configured));
        String file = System.mapLibraryName("vct_audio");
        for (Path p : List.of(Path.of("native", file), Path.of(file))) {
            if (Files.isRegularFile(p)) return load(p);
        }
        try {
            return verify(new WasapiBackend(SymbolLookup.libraryLookup(file, Arena.global())));
        } catch (IllegalArgumentException e) {
            throw new CaptureException(CaptureException.Kind.NOT_FOUND,
                    "Audio library " + file + " not found. Build it (see native/README.md) and pass -D"
                            + LIBRARY_PROPERTY + "=<path>.");
        }
    }

    public static WasapiBackend load(Path library) throws CaptureException {
        if (!Files.isRegularFile(library)) {
            throw new CaptureException(CaptureException.Kind.NOT_FOUND, "Audio library not found: " + library.toAbsolutePath());
        }
        try {
            // Global arena: the library stays loaded for the life of the JVM.
            return verify(new WasapiBackend(SymbolLookup.libraryLookup(library.toAbsolutePath(), Arena.global())));
        } catch (IllegalArgumentException e) {
            throw new CaptureException(CaptureException.Kind.OTHER,
                    "Could not load audio library " + library.toAbsolutePath() + ": " + e.getMessage());
        }
    }

    private static WasapiBackend verify(WasapiBackend b) throws CaptureException {
        int v = (int) invoke(b.apiVersion);
        if (v != API_VERSION) {
            throw new CaptureException(CaptureException.Kind.OTHER,
                    "Audio library has API version " + v + ", this app needs " + API_VERSION + ". Rebuild native/.");
        }
        return b;
    }

    @Override
    public List<AudioDevice> listDevices(AudioDevice.Flow flow) throws CaptureException {
        int capacity = 16 * 1024;
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(capacity);
                MemorySegment required = arena.allocate(JAVA_INT);
                int nativeFlow = flow == AudioDevice.Flow.CAPTURE ? 0 : 1; // VCT_FLOW_CAPTURE / VCT_FLOW_RENDER
                int hr = (int) invoke(listDevices, nativeFlow, buf, capacity, required);
                if (hr == VCT_E_BUFFER_TOO_SMALL) {
                    capacity = required.get(JAVA_INT, 0) + 1024; // devices may appear between calls
                    continue;
                }
                check(hr, "Listing audio devices");
                return parseDevices(buf.getString(0, StandardCharsets.UTF_8));
            }
        }
        throw new CaptureException(CaptureException.Kind.OTHER, "Listing audio devices failed: list kept growing");
    }

    static List<AudioDevice> parseDevices(String json) {
        List<AudioDevice> out = new ArrayList<>();
        for (Object o : (List<?>) Json.parse(json)) {
            Map<?, ?> m = (Map<?, ?>) o;
            out.add(new AudioDevice((String) m.get("id"), (String) m.get("name"),
                    Boolean.TRUE.equals(m.get("default")), Boolean.TRUE.equals(m.get("communications"))));
        }
        return List.copyOf(out);
    }

    @Override
    public CaptureStream open(CaptureTarget target, int sampleRate, int channels) throws CaptureException {
        int source;
        String deviceId = null;
        long pid = 0;
        String what;
        switch (target) {
            case CaptureTarget.Device d -> {
                source = 0;
                deviceId = d.deviceId();
                what = "Opening the microphone";
            }
            case CaptureTarget.Loopback l -> {
                source = 1;
                deviceId = l.deviceId();
                what = "Opening system audio capture";
            }
            case CaptureTarget.ProcessTree p -> {
                source = p.mode() == CaptureTarget.ProcessTree.Mode.INCLUDE ? 2 : 3;
                pid = p.pid();
                what = p.mode() == CaptureTarget.ProcessTree.Mode.INCLUDE
                        ? "Capturing app audio (process " + pid + ")"
                        : "Capturing all audio except this app";
            }
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment id = deviceId == null ? MemorySegment.NULL : arena.allocateFrom(deviceId, StandardCharsets.UTF_8);
            MemorySegment out = arena.allocate(ADDRESS);
            int hr = (int) invoke(open, source, id, (int) pid, sampleRate, channels, out);
            check(hr, what);
            return new Stream(out.get(ADDRESS, 0), sampleRate, channels);
        }
    }

    @Override
    public long deviceChangeCount() {
        return (long) invoke(deviceChanges);
    }

    /** Stops native device watching. Call at app exit, after all streams are closed. */
    public void shutdown() {
        invoke(shutdown);
    }

    private final class Stream implements CaptureStream {
        private final MemorySegment handle;
        private final int sampleRate;
        private final int channels;
        // Shared: the stream may be opened on one thread and read on another.
        private final Arena arena = Arena.ofShared();
        private final MemorySegment framesRead = arena.allocate(JAVA_INT);
        private MemorySegment buffer = MemorySegment.NULL;
        private boolean closed;

        Stream(MemorySegment handle, int sampleRate, int channels) {
            this.handle = handle;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }

        @Override
        public int sampleRate() {
            return sampleRate;
        }

        @Override
        public int channels() {
            return channels;
        }

        @Override
        public int read(float[] dst, int timeoutMillis) throws CaptureException {
            if (closed) throw new CaptureException(CaptureException.Kind.CLOSED, "Capture stream is closed");
            int frames = dst.length / channels;
            if (buffer.byteSize() < (long) frames * channels * Float.BYTES) {
                buffer = arena.allocate(JAVA_FLOAT, (long) frames * channels);
            }
            int hr = (int) invoke(read, handle, buffer, frames, timeoutMillis, framesRead);
            check(hr, "Reading audio");
            int n = framesRead.get(JAVA_INT, 0);
            MemorySegment.copy(buffer, JAVA_FLOAT, 0, dst, 0, n * channels);
            return n;
        }

        @Override
        public Stats stats() {
            if (closed) return new Stats(0, 0, 0);
            try (Arena a = Arena.ofConfined()) {
                MemorySegment captured = a.allocate(JAVA_LONG);
                MemorySegment dropped = a.allocate(JAVA_LONG);
                MemorySegment disc = a.allocate(JAVA_INT);
                invoke(stats, handle, captured, dropped, disc);
                return new Stats(captured.get(JAVA_LONG, 0), dropped.get(JAVA_LONG, 0), disc.get(JAVA_INT, 0));
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            invoke(close, handle);
            arena.close();
        }
    }

    private void check(int hr, String action) throws CaptureException {
        if (hr >= 0) return;
        String text;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(512);
            invoke(errorMessage, hr, buf, 512);
            text = buf.getString(0, StandardCharsets.UTF_8);
        }
        throw CaptureException.fromHresult(action, hr, text);
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
