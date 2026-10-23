package com.vct.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Scripted {@link CaptureBackend}: each open() takes the next scripted result. */
final class FakeBackend implements CaptureBackend {

    record OpenCall(CaptureTarget target, int sampleRate, int channels) {}

    /** Answers one open(): either a stream or a failure. */
    interface Script {
        FakeStream open(OpenCall call) throws CaptureException;
    }

    final List<OpenCall> opens = new CopyOnWriteArrayList<>();
    final List<FakeStream> streams = new CopyOnWriteArrayList<>();
    final BlockingQueue<Script> scripts = new LinkedBlockingQueue<>();
    final AtomicLong changes = new AtomicLong();
    volatile List<AudioDevice> captureDevices = List.of();
    volatile List<AudioDevice> renderDevices = List.of();

    FakeBackend script(Script s) {
        scripts.add(s);
        return this;
    }

    @Override
    public List<AudioDevice> listDevices(AudioDevice.Flow flow) {
        return flow == AudioDevice.Flow.CAPTURE ? captureDevices : renderDevices;
    }

    @Override
    public CaptureStream open(CaptureTarget target, int sampleRate, int channels) throws CaptureException {
        OpenCall call = new OpenCall(target, sampleRate, channels);
        opens.add(call);
        Script s = scripts.poll();
        if (s == null) throw new CaptureException(CaptureException.Kind.NOT_FOUND, "no more scripted streams");
        FakeStream stream = s.open(call);
        streams.add(stream);
        return stream;
    }

    @Override
    public long deviceChangeCount() {
        return changes.get();
    }

    static CaptureException error(CaptureException.Kind kind) {
        return new CaptureException(kind, kind + " (scripted)");
    }

    /** A stream fed by the test: float[] chunks, or a CaptureException to fail the next read. */
    static final class FakeStream implements CaptureStream {
        final int sampleRate;
        final int channels;
        final BlockingQueue<Object> items = new LinkedBlockingQueue<>();
        volatile boolean closed;
        private final List<Float> pending = new ArrayList<>();

        FakeStream(int sampleRate, int channels) {
            this.sampleRate = sampleRate;
            this.channels = channels;
        }

        FakeStream feed(Object... chunksOrErrors) {
            items.addAll(List.of(chunksOrErrors));
            return this;
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
            if (pending.isEmpty()) {
                Object item;
                try {
                    item = items.poll(timeoutMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 0;
                }
                if (item == null) return 0;
                if (item instanceof CaptureException e) throw e;
                for (float f : (float[]) item) pending.add(f);
            }
            int n = Math.min(pending.size(), dst.length) / channels * channels;
            for (int i = 0; i < n; i++) dst[i] = pending.get(i);
            pending.subList(0, n).clear();
            return n / channels;
        }

        @Override
        public Stats stats() {
            return new Stats(0, 0, 0);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
