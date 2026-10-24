package com.vct.segment;

import com.vct.audio.PcmSegment;
import com.vct.audio.TestWav;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SileroVadTest {

    private static SileroVad.Model model;
    private static float[] jfk;

    @BeforeAll
    static void load() throws IOException {
        model = SileroVad.Model.load();
        jfk = TestWav.read16kMono("/audio/jfk.wav");
    }

    @AfterAll
    static void close() throws Exception {
        model.close();
    }

    @Test
    void matchesTheOfficialPythonWrapperFrameByFrame() throws IOException {
        // jfk.silero-reference.txt: probabilities from the official wrapper's steps in Python ONNX Runtime.
        double[] expected;
        try (InputStream in = SileroVadTest.class.getResourceAsStream("/audio/jfk.silero-reference.txt")) {
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().mapToDouble(Double::parseDouble).toArray();
        }
        float[] actual = probabilities(new SileroVad(model), jfk);
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < actual.length; i++) {
            assertEquals(expected[i], actual[i], 1e-4, "frame " + i);
        }
    }

    @Test
    void ignoresSilenceToneAndLoudNoise() {
        SileroVad vad = new SileroVad(model);
        Random rnd = new Random(42);
        float[] silence = new float[16_000];
        float[] tone = new float[16_000];
        float[] noise = new float[16_000];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (float) (0.3 * Math.sin(2 * Math.PI * 440 * i / 16_000.0));
            noise[i] = (float) (0.3 * rnd.nextGaussian()); // about -10 dBFS: EnergyVad would call this speech
        }
        for (float[] signal : List.of(silence, tone, noise)) {
            vad.reset();
            float[] p = probabilities(vad, signal);
            long speechy = count(p, 0.5f);
            assertTrue(speechy <= 2, "non-speech frames rated as speech: " + speechy + " of " + p.length);
        }
    }

    @Test
    void resetGivesTheSameResultAsAFreshDetector() {
        SileroVad used = new SileroVad(model);
        probabilities(used, jfk);
        used.reset();
        assertArrayEquals(probabilities(new SileroVad(model), jfk), probabilities(used, jfk));
    }

    @Test
    void segmenterCutsJfkIntoSpeechUtterances() {
        List<PcmSegment> segments = new ArrayList<>();
        UtteranceSegmenter seg = new UtteranceSegmenter(UtteranceSegmenter.Config.defaults(), segments::add);
        SileroVad vad = new SileroVad(model);
        for (int i = 0; i + SileroVad.FRAME_SIZE <= jfk.length; i += SileroVad.FRAME_SIZE) {
            float[] frame = Arrays.copyOfRange(jfk, i, i + SileroVad.FRAME_SIZE);
            seg.accept(frame, vad.speechProbability(frame));
        }
        seg.flush();
        long speechMs = segments.stream().mapToLong(PcmSegment::durationMillis).sum();
        // "And so, my fellow Americans: ask not what your country can do for you, ask what you can do
        // for your country." About 8 s of speech in 11 s, with pauses between the phrases.
        assertTrue(segments.size() >= 2 && segments.size() <= 5, "segments: " + segments.size());
        assertTrue(speechMs > 6_000 && speechMs < 10_500, "speech ms: " + speechMs);
    }

    private static float[] probabilities(SileroVad vad, float[] audio) {
        float[] out = new float[audio.length / SileroVad.FRAME_SIZE];
        for (int f = 0; f < out.length; f++) {
            out[f] = vad.speechProbability(Arrays.copyOfRange(audio, f * SileroVad.FRAME_SIZE, (f + 1) * SileroVad.FRAME_SIZE));
        }
        return out;
    }

    private static long count(float[] p, float threshold) {
        long n = 0;
        for (float v : p) if (v > threshold) n++;
        return n;
    }

    private static void assertArrayEquals(float[] a, float[] b) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(a, b, 1e-6f);
    }
}
