package com.vct.segment;

import com.vct.audio.PcmSegment;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Turns a stream of fixed-size frames plus VAD probabilities into utterance segments.
 *
 * <p>Speech starts when probability reaches {@code startThreshold}; it ends after
 * {@code endSilenceMs} of frames below {@code endThreshold} (hysteresis avoids flapping). Speech
 * shorter than {@code minSpeechMs} is discarded as a click/cough, and segments longer than
 * {@code maxUtteranceMs} are force-split so a monologue still reaches the buffer incrementally.
 * Fluent speakers rarely pause as long as {@code endSilenceMs}, so once an utterance is longer than
 * {@code softMaxUtteranceMs}, a pause of {@code softEndSilenceMs} already ends it.
 *
 * <p>Driven from a single audio thread. {@link #isInSpeech()} and {@link #currentStartSample()}
 * may be read from other threads.
 */
public final class UtteranceSegmenter {

    public record Config(
            int sampleRate,
            int frameSize,
            float startThreshold,
            float endThreshold,
            int minSpeechMs,
            int endSilenceMs,
            int maxUtteranceMs,
            int preRollMs,
            int trailingPadMs,
            int softMaxUtteranceMs,
            int softEndSilenceMs) {

        public static Config defaults() {
            return new Config(16_000, 512, 0.5f, 0.35f, 250, 700, 15_000, 200, 150, 6_000, 300);
        }

        public Config withEndSilenceMs(int ms) {
            return new Config(sampleRate, frameSize, startThreshold, endThreshold, minSpeechMs, ms,
                    maxUtteranceMs, preRollMs, trailingPadMs, softMaxUtteranceMs, softEndSilenceMs);
        }

        public Config withMaxUtteranceMs(int ms) {
            return new Config(sampleRate, frameSize, startThreshold, endThreshold, minSpeechMs,
                    endSilenceMs, ms, preRollMs, trailingPadMs, softMaxUtteranceMs, softEndSilenceMs);
        }
    }

    private final Config cfg;
    private final Consumer<PcmSegment> out;
    private final ArrayDeque<float[]> preRoll = new ArrayDeque<>();
    private final int preRollFrames;
    private final int maxSamples;
    private final int softMaxSamples;
    private final int padSamples;

    private float[] buf;
    private int len;
    private long samplePos;
    private int speechFrames;
    private int silenceFrames;
    private volatile boolean inSpeech;
    private volatile long segStartSample;

    public UtteranceSegmenter(Config cfg, Consumer<PcmSegment> out) {
        this.cfg = cfg;
        this.out = out;
        this.preRollFrames = msToFrames(cfg.preRollMs());
        this.maxSamples = (int) ((long) cfg.maxUtteranceMs() * cfg.sampleRate() / 1000);
        this.softMaxSamples = (int) ((long) cfg.softMaxUtteranceMs() * cfg.sampleRate() / 1000);
        this.padSamples = (int) ((long) cfg.trailingPadMs() * cfg.sampleRate() / 1000);
        this.buf = new float[cfg.sampleRate() * 4];
    }

    public void accept(float[] frame, float speechProbability) {
        if (frame.length != cfg.frameSize()) {
            throw new IllegalArgumentException("expected frame of " + cfg.frameSize() + " samples");
        }
        if (!inSpeech) {
            if (speechProbability >= cfg.startThreshold()) {
                len = 0;
                segStartSample = samplePos - (long) preRoll.size() * cfg.frameSize();
                for (float[] f : preRoll) append(f);
                preRoll.clear();
                append(frame);
                speechFrames = 1;
                silenceFrames = 0;
                inSpeech = true;
            } else if (preRollFrames > 0) {
                preRoll.addLast(frame.clone());
                if (preRoll.size() > preRollFrames) preRoll.removeFirst();
            }
        } else {
            append(frame);
            if (speechProbability < cfg.endThreshold()) {
                silenceFrames++;
            } else {
                silenceFrames = 0;
                speechFrames++;
            }
            long nextStart = samplePos + frame.length;
            int endSilenceMs = len >= softMaxSamples ? cfg.softEndSilenceMs() : cfg.endSilenceMs();
            if (silenceFrames * frameMs() >= endSilenceMs) {
                finish(false, nextStart);
            } else if (len >= maxSamples) {
                finish(true, nextStart);
            }
        }
        samplePos += frame.length;
    }

    /** Emits any utterance in progress (call when the session stops). */
    public void flush() {
        if (inSpeech) finish(false, samplePos);
    }

    public boolean isInSpeech() {
        return inSpeech;
    }

    /** Start sample of the utterance in progress; meaningful only while {@link #isInSpeech()}. */
    public long currentStartSample() {
        return segStartSample;
    }

    private void finish(boolean forced, long nextStart) {
        int keep = len;
        if (!forced) {
            int trailing = silenceFrames * cfg.frameSize();
            keep = len - trailing + Math.min(trailing, padSamples);
        }
        if (speechFrames * frameMs() >= cfg.minSpeechMs()) {
            out.accept(new PcmSegment(Arrays.copyOf(buf, keep), cfg.sampleRate(), segStartSample, forced));
        }
        len = 0;
        speechFrames = 0;
        silenceFrames = 0;
        if (forced) {
            // Speaker is still talking: continue straight into the next segment.
            segStartSample = nextStart;
        } else {
            inSpeech = false;
        }
    }

    private void append(float[] f) {
        if (len + f.length > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + f.length));
        }
        System.arraycopy(f, 0, buf, len, f.length);
        len += f.length;
    }

    private int frameMs() {
        return cfg.frameSize() * 1000 / cfg.sampleRate();
    }

    private int msToFrames(int ms) {
        return (int) Math.ceil((double) ms * cfg.sampleRate() / 1000 / cfg.frameSize());
    }
}
