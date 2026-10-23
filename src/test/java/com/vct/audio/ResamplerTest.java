package com.vct.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResamplerTest {

    static float[] sine(int rate, double hz, int samples) {
        float[] s = new float[samples];
        for (int i = 0; i < samples; i++) s[i] = (float) Math.sin(2 * Math.PI * hz * i / rate);
        return s;
    }

    static int zeroCrossings(float[] s) {
        int n = 0;
        for (int i = 1; i < s.length; i++) if ((s[i - 1] < 0) != (s[i] < 0)) n++;
        return n;
    }

    @Test
    void downsamples48kTo16kPreservingFrequency() {
        float[] in = sine(48_000, 440, 48_000);
        float[] out = new Resampler(48_000, 16_000).process(in);
        assertEquals(16_000, out.length, 2);
        // 440 Hz for one second has ~880 zero crossings regardless of sample rate.
        assertEquals(880, zeroCrossings(out), 4);
    }

    @Test
    void chunkingDoesNotChangeOutput() {
        float[] in = sine(44_100, 300, 44_100);
        float[] whole = new Resampler(44_100, 16_000).process(in);

        Resampler r = new Resampler(44_100, 16_000);
        List<Float> chunked = new ArrayList<>();
        int[] sizes = {1, 7, 480, 441, 1000, 3};
        int pos = 0, k = 0;
        while (pos < in.length) {
            int n = Math.min(sizes[k++ % sizes.length], in.length - pos);
            float[] part = new float[n];
            System.arraycopy(in, pos, part, 0, n);
            for (float f : r.process(part)) chunked.add(f);
            pos += n;
        }
        assertEquals(whole.length, chunked.size());
        for (int i = 0; i < whole.length; i++) assertEquals(whole[i], chunked.get(i), 1e-5);
    }

    @Test
    void upsamplesWithInterpolation() {
        float[] out = new Resampler(8_000, 16_000).process(sine(8_000, 200, 8_000));
        assertEquals(16_000, out.length, 2);
        assertEquals(400, zeroCrossings(out), 4);
    }

    @Test
    void downmixAveragesChannels() {
        assertArrayEquals(new float[]{0.5f, 0f}, Resampler.downmix(new float[]{1f, 0f, -1f, 1f}, 2));
    }

    @Test
    void frameAssemblerEmitsFixedFrames() {
        FrameAssembler a = new FrameAssembler(4);
        List<float[]> frames = new ArrayList<>();
        a.push(new float[]{1, 2, 3}, frames::add);
        a.push(new float[]{4, 5, 6, 7, 8, 9}, frames::add);
        assertEquals(2, frames.size());
        assertArrayEquals(new float[]{1, 2, 3, 4}, frames.get(0));
        assertArrayEquals(new float[]{5, 6, 7, 8}, frames.get(1));
    }
}
