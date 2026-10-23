package com.vct.capture;

import com.vct.audio.Resampler;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongPredicate;

/**
 * Keeps one capture source running and delivers mono float PCM at {@link Config#sampleRate()}
 * (16 kHz) to a sink, typically {@code samples -> session.onAudio(Source.MIC, samples)}.
 *
 * <p>Owns a platform thread that reads the stream. It survives failures. On an error (device
 * unplugged, Discord not running, microphone blocked by privacy settings) it reports the state and
 * retries with backoff. It reopens the stream when the Windows default device changes (if the target
 * follows the default) and when a captured process exits (the resolver then finds the new one).
 *
 * <p>Format: asks Windows for 16 kHz mono. If the device refuses, it tries 48 kHz and 44.1 kHz
 * stereo and converts here.
 */
public final class CaptureLoop implements AutoCloseable {

    public enum State { STARTING, RUNNING, RECONNECTING, STOPPED }

    /** Produces the target on every (re)open, so a restarted Discord is found again. */
    @FunctionalInterface
    public interface TargetResolver {
        CaptureTarget resolve() throws CaptureException;

        static TargetResolver of(CaptureTarget target) {
            return () -> target;
        }
    }

    /** State changes, called on the capture thread. {@code detail} is user-readable text, or null. */
    @FunctionalInterface
    public interface Listener {
        void onState(State state, String detail);
    }

    /**
     * @param sampleRate       rate delivered to the sink
     * @param readTimeout      longest a read waits; also bounds how quickly {@link #close()} returns
     * @param minBackoff       first retry delay after a failure
     * @param maxBackoff       retry delay cap
     * @param checkInterval    how often to check for default-device changes and exited processes
     */
    public record Config(int sampleRate, Duration readTimeout, Duration minBackoff, Duration maxBackoff,
                         Duration checkInterval) {
        public static Config defaults() {
            return new Config(16_000, Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofSeconds(5),
                    Duration.ofSeconds(1));
        }
    }

    private static final int[][] FALLBACK_FORMATS = {{48_000, 2}, {44_100, 2}};

    private final String name;
    private final CaptureBackend backend;
    private final TargetResolver resolver;
    private final Consumer<float[]> sink;
    private final Listener listener;
    private final Config config;
    private final LongPredicate processAlive;

    private volatile boolean running = true;
    private volatile State state = State.STARTING;
    private volatile String detail;
    private volatile CaptureStream.Stats stats = new CaptureStream.Stats(0, 0, 0);
    private Thread thread;

    public CaptureLoop(String name, CaptureBackend backend, TargetResolver resolver, Consumer<float[]> sink,
                       Listener listener, Config config) {
        this(name, backend, resolver, sink, listener, config,
                pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    CaptureLoop(String name, CaptureBackend backend, TargetResolver resolver, Consumer<float[]> sink,
                Listener listener, Config config, LongPredicate processAlive) {
        this.name = name;
        this.backend = backend;
        this.resolver = resolver;
        this.sink = sink;
        this.listener = listener;
        this.config = config;
        this.processAlive = processAlive;
    }

    public synchronized CaptureLoop start() {
        if (thread != null) throw new IllegalStateException("already started");
        thread = Thread.ofPlatform().name("vct-capture-" + name).daemon().start(this::run);
        return this;
    }

    public State state() {
        return state;
    }

    /** User-readable detail of the current state (what is captured, or why it is reconnecting). */
    public String detail() {
        return detail;
    }

    /** Counters of the current stream, refreshed every {@link Config#checkInterval()}. */
    public CaptureStream.Stats stats() {
        return stats;
    }

    /** Stops the thread and closes the stream. Returns within about {@link Config#readTimeout()}. */
    @Override
    public void close() {
        running = false;
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t == null || t == Thread.currentThread()) return;
        t.interrupt(); // wakes a backoff sleep; a native read returns on its own timeout
        try {
            t.join(config.readTimeout().toMillis() + 2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        setState(State.STARTING, null);
        long backoff = config.minBackoff().toMillis();
        while (running) {
            CaptureStream stream = null;
            try {
                CaptureTarget target = resolver.resolve();
                stream = openAnyFormat(target);
                backoff = config.minBackoff().toMillis();
                setState(State.RUNNING, describe(target, stream));
                String reopenReason = pump(target, stream);
                if (reopenReason != null) setState(State.RECONNECTING, reopenReason);
            } catch (CaptureException | RuntimeException e) {
                if (!running) break;
                setState(State.RECONNECTING, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    break; // only close() interrupts
                }
                backoff = Math.min(backoff * 2, config.maxBackoff().toMillis());
            } finally {
                if (stream != null) stream.close();
            }
        }
        setState(State.STOPPED, null);
    }

    private CaptureStream openAnyFormat(CaptureTarget target) throws CaptureException {
        try {
            return backend.open(target, config.sampleRate(), 1);
        } catch (CaptureException e) {
            if (e.kind() != CaptureException.Kind.UNSUPPORTED_FORMAT) throw e;
            CaptureException last = e;
            for (int[] f : FALLBACK_FORMATS) {
                try {
                    return backend.open(target, f[0], f[1]);
                } catch (CaptureException e2) {
                    if (e2.kind() != CaptureException.Kind.UNSUPPORTED_FORMAT) throw e2;
                    last = e2;
                }
            }
            throw last;
        }
    }

    /**
     * Reads until close, failure (thrown), or a reason to reopen.
     *
     * @return why the stream must be reopened, or null when stopping
     */
    private String pump(CaptureTarget target, CaptureStream s) throws CaptureException {
        int ch = s.channels();
        float[] buf = new float[Math.max(1, s.sampleRate() / 10) * ch];
        Resampler resampler = s.sampleRate() == config.sampleRate() ? null : new Resampler(s.sampleRate(), config.sampleRate());
        AudioDevice.Flow defaultFlow = defaultFlow(target);
        String defaultAtOpen = defaultFlow == null ? null : defaultDevice(defaultFlow).map(AudioDevice::id).orElse(null);
        long changes = backend.deviceChangeCount();
        long checkNanos = config.checkInterval().toNanos();
        long nextCheck = System.nanoTime() + checkNanos;
        int timeout = (int) config.readTimeout().toMillis();

        while (running) {
            int frames = s.read(buf, timeout);
            if (frames > 0) {
                float[] x = Arrays.copyOf(buf, frames * ch);
                if (ch > 1) x = Resampler.downmix(x, ch);
                if (resampler != null) x = resampler.process(x);
                if (x.length > 0) sink.accept(x);
            }
            if (System.nanoTime() - nextCheck < 0) continue;
            nextCheck = System.nanoTime() + checkNanos;
            stats = s.stats();
            if (target instanceof CaptureTarget.ProcessTree p && p.mode() == CaptureTarget.ProcessTree.Mode.INCLUDE
                    && !processAlive.test(p.pid())) {
                throw new CaptureException(CaptureException.Kind.NOT_FOUND,
                        "The captured app (process " + p.pid() + ") has exited.");
            }
            long now = backend.deviceChangeCount();
            if (now != changes && defaultFlow != null) {
                changes = now;
                Optional<AudioDevice> current = defaultDevice(defaultFlow);
                if (current.isPresent() && !Objects.equals(current.get().id(), defaultAtOpen)) {
                    return "Windows default device changed to " + current.get().name() + ". Switching.";
                }
            }
        }
        return null;
    }

    private static AudioDevice.Flow defaultFlow(CaptureTarget t) {
        return switch (t) {
            case CaptureTarget.Device d -> d.followsDefault() ? AudioDevice.Flow.CAPTURE : null;
            case CaptureTarget.Loopback l -> l.followsDefault() ? AudioDevice.Flow.RENDER : null;
            case CaptureTarget.ProcessTree p -> null;
        };
    }

    private Optional<AudioDevice> defaultDevice(AudioDevice.Flow flow) {
        try {
            return backend.listDevices(flow).stream().filter(AudioDevice::isDefault).findFirst();
        } catch (CaptureException e) {
            return Optional.empty(); // unknown: keep the current stream
        }
    }

    private static String describe(CaptureTarget t, CaptureStream s) {
        String what = switch (t) {
            case CaptureTarget.Device d -> d.followsDefault() ? "Default microphone" : "Microphone " + d.deviceId();
            case CaptureTarget.Loopback l -> l.followsDefault() ? "Default output (all apps)" : "Output " + l.deviceId();
            case CaptureTarget.ProcessTree p -> p.mode() == CaptureTarget.ProcessTree.Mode.INCLUDE
                    ? "App audio of process " + p.pid()
                    : "All audio except this app";
        };
        return s.channels() == 1 ? what
                : what + " (device format " + s.sampleRate() + " Hz, " + s.channels() + " ch, converted)";
    }

    private void setState(State s, String d) {
        if (s == state && Objects.equals(d, detail)) return;
        state = s;
        detail = d;
        try {
            listener.onState(s, d);
        } catch (RuntimeException ignored) {
            // a faulty listener must not stop capture
        }
    }
}
