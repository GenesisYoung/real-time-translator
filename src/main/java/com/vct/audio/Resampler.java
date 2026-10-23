package com.vct.audio;

import java.util.Arrays;

/**
 * Streaming mono resampler for speech pipelines (e.g. 48 kHz device audio to 16 kHz for STT/VAD).
 * Downsampling averages a window of input samples per output sample (a box low-pass, adequate for
 * speech recognition, not for music); upsampling interpolates linearly. Chunk boundaries do not
 * affect the output. Not thread-safe: use one instance per stream.
 */
public final class Resampler {

    private final int inRate;
    private final int outRate;
    private final int window;
    /** Outputs produced so far; output k sits at input position k * inRate / outRate (exact). */
    private long outCount;
    /** Input samples already discarded from the front of {@link #carry}. */
    private long dropped;
    private float[] carry = new float[0];

    public Resampler(int inRate, int outRate) {
        if (inRate <= 0 || outRate <= 0) throw new IllegalArgumentException("rates must be positive");
        this.inRate = inRate;
        this.outRate = outRate;
        this.window = inRate > outRate ? (int) Math.round((double) inRate / outRate) : 1;
    }

    public float[] process(float[] in) {
        float[] x = new float[carry.length + in.length];
        System.arraycopy(carry, 0, x, 0, carry.length);
        System.arraycopy(in, 0, x, carry.length, in.length);

        float[] out = new float[(int) ((long) x.length * outRate / inRate) + 2];
        int n = 0;
        while (true) {
            long num = outCount * inRate;
            int i = (int) (num / outRate - dropped);
            if (window > 1) {
                if (i + window > x.length) break;
                float sum = 0;
                for (int k = 0; k < window; k++) sum += x[i + k];
                out[n++] = sum / window;
            } else {
                if (i + 1 >= x.length) break;
                float frac = (float) (num % outRate) / outRate;
                out[n++] = x[i] + (x[i + 1] - x[i]) * frac;
            }
            outCount++;
        }
        int next = (int) (outCount * inRate / outRate - dropped);
        int consumed = Math.min(Math.max(next, 0), x.length);
        carry = Arrays.copyOfRange(x, consumed, x.length);
        dropped += consumed;
        return Arrays.copyOf(out, n);
    }

    /** Averages interleaved channels into mono. */
    public static float[] downmix(float[] interleaved, int channels) {
        if (channels == 1) return interleaved;
        float[] mono = new float[interleaved.length / channels];
        for (int i = 0; i < mono.length; i++) {
            float sum = 0;
            for (int c = 0; c < channels; c++) sum += interleaved[i * channels + c];
            mono[i] = sum / channels;
        }
        return mono;
    }
}
