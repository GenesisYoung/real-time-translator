package com.vct.tts;

import com.vct.audio.AudioClip;
import com.vct.audio.Resampler;
import com.vct.segment.SileroVad;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real sherpa-onnx library with a real voice. Skipped unless both are given:
 * {@code gradlew test -Pvct.tts.lib=<sherpa-onnx-c-api library> -Pvct.tts.testVoice=<voice folder>}.
 */
class SherpaTtsTest {

    private static SherpaTts tts;

    @BeforeAll
    static void load() throws TtsException {
        String lib = System.getProperty(SherpaTts.LIBRARY_PROPERTY);
        String voice = System.getProperty("vct.tts.testVoice");
        assumeTrue(lib != null && voice != null && Files.isRegularFile(Path.of(lib)), "no sherpa-onnx library/voice given");
        tts = SherpaTts.load(Path.of(lib), Path.of(voice), 2);
    }

    @AfterAll
    static void close() {
        if (tts != null) tts.close();
    }

    @Test
    void reportsVersionAndFormat() {
        assertTrue(tts.version().matches("\\d+\\.\\d+\\.\\d+.*"), tts.version());
        assertTrue(tts.sampleRate() >= 16_000, "rate " + tts.sampleRate());
        assertTrue(tts.numSpeakers() >= 1);
    }

    @Test
    void synthesizesSpeechOfPlausibleLengthAndLoudness() throws TtsException {
        AudioClip clip = tts.synthesize("Hello, this is a test of the translator.", "en", null, 1.0f);
        assertEquals(tts.sampleRate(), clip.sampleRate());
        long ms = clip.duration().toMillis();
        assertTrue(ms > 1_000 && ms < 6_000, "duration " + ms + " ms");
        float peak = 0;
        for (float v : clip.samples()) peak = Math.max(peak, Math.abs(v));
        assertTrue(peak > 0.05f && peak <= 1.5f, "peak " + peak);
    }

    @Test
    void outputIsRecognizedAsSpeechBySileroVad() throws Exception {
        // Cannot listen in a test, but the speech detector can: noise or garbage would score low.
        AudioClip clip = tts.synthesize("Hello, this is a test of the translator.", "en", null, 1.0f);
        float[] x = new Resampler(clip.sampleRate(), 16_000).process(clip.samples());
        try (SileroVad.Model model = SileroVad.Model.load()) {
            SileroVad vad = new SileroVad(model);
            int frames = x.length / SileroVad.FRAME_SIZE, speech = 0;
            for (int f = 0; f < frames; f++) {
                float p = vad.speechProbability(Arrays.copyOfRange(x, f * SileroVad.FRAME_SIZE, (f + 1) * SileroVad.FRAME_SIZE));
                if (p > 0.5f) speech++;
            }
            assertTrue(speech > frames * 0.6, speech + " of " + frames + " frames rated as speech");
        }
    }

    @Test
    void fasterSpeedGivesShorterAudio() throws TtsException {
        String text = "We should go left at the next corner.";
        long normal = tts.synthesize(text, "en", null, 1.0f).samples().length;
        long fast = tts.synthesize(text, "en", null, 1.5f).samples().length;
        assertTrue(fast < normal * 0.85, normal + " vs " + fast);
    }

    @Test
    void blankTextAndBadSpeakersAreHandledInJava() throws TtsException {
        assertEquals(0, tts.synthesize("  ", "en", null, 1.0f).samples().length);
        TtsException e = assertThrows(TtsException.class, () -> tts.synthesize("Hi", "en", "99999", 1.0f));
        assertTrue(e.getMessage().startsWith("Speaker 99999 does not exist"), e.getMessage());
        assertThrows(TtsException.class, () -> tts.synthesize("Hi", "en", "first", 1.0f));
    }
}
