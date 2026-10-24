package com.vct.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plays clips on an output device through Java Sound (DirectSound on Windows).
 *
 * <p>Playback comes from this app's own process, so capturing "all audio except this app" never
 * records the translation back.
 */
public final class JavaSoundSink implements AudioSink {

    /** Java Sound's line buffer: small, so {@link #stop()} silences quickly. */
    private static final int BUFFER_MS = 100;

    private final Mixer.Info device;
    private final AtomicReference<Playing> current = new AtomicReference<>();

    private static final class Playing {
        final SourceDataLine line;
        volatile boolean stopped;
        volatile InputStream source; // closed by stop(), so a read waiting for the network ends

        Playing(SourceDataLine line) {
            this.line = line;
        }
    }

    /** Plays on the system default output. */
    public JavaSoundSink() {
        this.device = null;
    }

    /** Plays on the first output whose name contains {@code nameContains} (case-insensitive). */
    public JavaSoundSink(String nameContains) {
        String want = nameContains.toLowerCase(Locale.ROOT);
        Mixer.Info found = null;
        for (Mixer.Info info : outputMixers()) {
            if (info.getName().toLowerCase(Locale.ROOT).contains(want)) {
                found = info;
                break;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("No output device matches \"" + nameContains + "\". Available: "
                    + outputDevices());
        }
        this.device = found;
    }

    /** Names of the outputs Java Sound can play on. */
    public static List<String> outputDevices() {
        return outputMixers().stream().map(Mixer.Info::getName).toList();
    }

    private static List<Mixer.Info> outputMixers() {
        List<Mixer.Info> out = new ArrayList<>();
        DataLine.Info wanted = new DataLine.Info(SourceDataLine.class, new AudioFormat(48_000, 16, 1, true, false));
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(wanted)) out.add(info);
        }
        return out;
    }

    @Override
    public void play(AudioClip clip) throws InterruptedException {
        if (clip.samples().length == 0) return;
        AudioFormat format = new AudioFormat(clip.sampleRate(), 16, 1, true, false);
        byte[] pcm = toPcm16(clip.samples());
        int chunk = Math.max(2, clip.sampleRate() / 20 * 2); // 50 ms
        SourceDataLine line = openLine(format, Math.max(chunk, clip.sampleRate() * 2 * BUFFER_MS / 1000));
        Playing p = new Playing(line);
        current.set(p);
        try {
            line.start();
            for (int off = 0; off < pcm.length && !p.stopped; ) {
                if (Thread.interrupted()) throw new InterruptedException();
                off += line.write(pcm, off, Math.min(chunk, pcm.length - off)); // returns early once stopped
            }
            if (!p.stopped) line.drain();
        } finally {
            current.compareAndSet(p, null);
            line.close();
        }
    }

    /** Audio collected before a stream starts playing, so a slow network does not stutter at once. */
    private static final int STREAM_START_MS = 120;
    /** Line buffer for streams: holds the start-up audio, so writing it never blocks. */
    private static final int STREAM_LINE_MS = 300;

    @Override
    public void playStream(InputStream pcm16, int sampleRate) throws InterruptedException, IOException {
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        SourceDataLine line = openLine(format, sampleRate * 2 * STREAM_LINE_MS / 1000);
        Playing p = new Playing(line);
        p.source = pcm16;
        current.set(p);
        int startBytes = sampleRate * 2 * STREAM_START_MS / 1000;
        byte[] buf = new byte[Math.max(2, sampleRate / 20 * 2)]; // 50 ms
        int have = 0;
        long written = 0;
        boolean started = false;
        try {
            while (!p.stopped) {
                if (Thread.interrupted()) throw new InterruptedException();
                int n;
                try {
                    n = pcm16.read(buf, have, buf.length - have);
                } catch (IOException e) {
                    if (p.stopped) break; // stop() closed the stream
                    throw e;
                }
                if (n < 0) break;
                have += n;
                int whole = have & ~1; // whole 16-bit samples only
                for (int off = 0; off < whole && !p.stopped; ) off += line.write(buf, off, whole - off);
                written += whole;
                if (have > whole) buf[0] = buf[whole];
                have -= whole;
                if (!started && written >= startBytes) {
                    line.start();
                    started = true;
                }
            }
            if (!p.stopped) {
                if (!started) line.start(); // a clip shorter than the start-up amount
                line.drain();
            }
        } finally {
            current.compareAndSet(p, null);
            line.close();
            pcm16.close();
        }
    }

    private SourceDataLine openLine(AudioFormat format, int bufferBytes) {
        try {
            SourceDataLine line = device == null ? AudioSystem.getSourceDataLine(format) : AudioSystem.getSourceDataLine(format, device);
            line.open(format, bufferBytes);
            return line;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot play on " + (device == null ? "the default output" : device.getName())
                    + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        Playing p = current.get();
        if (p == null) return;
        p.stopped = true;
        p.line.stop();
        p.line.flush();
        InputStream in = p.source;
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // stopping anyway
            }
        }
    }

    /** Live line buffer: room for capture blocks arriving late, short enough to keep the delay low. */
    private static final int STREAM_BUFFER_MS = 200;

    @Override
    public AudioStream openStream(int sampleRate) {
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        SourceDataLine line = openLine(format, sampleRate * 2 * STREAM_BUFFER_MS / 1000);
        line.start();
        return new AudioStream() {
            @Override
            public synchronized void write(float[] samples) {
                byte[] pcm = toPcm16(samples);
                int n = Math.min(pcm.length, line.available() & ~1); // write() would block when the buffer is full
                if (n > 0) line.write(pcm, 0, n);
            }

            @Override
            public synchronized void close() {
                line.drain();
                line.close();
            }
        };
    }

    static byte[] toPcm16(float[] samples) {
        return new AudioClip(samples, 1).pcm16();
    }
}
