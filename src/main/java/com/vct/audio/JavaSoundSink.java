package com.vct.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
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
        SourceDataLine line;
        try {
            line = device == null ? AudioSystem.getSourceDataLine(format) : AudioSystem.getSourceDataLine(format, device);
            line.open(format, Math.max(chunk, clip.sampleRate() * 2 * BUFFER_MS / 1000));
        } catch (LineUnavailableException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot play on " + (device == null ? "the default output" : device.getName())
                    + ": " + e.getMessage(), e);
        }
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

    @Override
    public void stop() {
        Playing p = current.get();
        if (p == null) return;
        p.stopped = true;
        p.line.stop();
        p.line.flush();
    }

    static byte[] toPcm16(float[] samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int v = Math.round(Math.max(-1f, Math.min(1f, samples[i])) * 32767);
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }
}
