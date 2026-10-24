package com.vct.segment;

import com.vct.audio.PcmSegment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UtteranceSegmenterTest {

    // 512 samples @ 16 kHz = 32 ms per frame.
    static final int FRAME = 512;
    final List<PcmSegment> out = new ArrayList<>();

    void feed(UtteranceSegmenter s, float prob, int frames) {
        for (int i = 0; i < frames; i++) s.accept(new float[FRAME], prob);
    }

    @Test
    void emitsOneSegmentPerUtteranceWithPreRollAndTrimmedSilence() {
        var s = new UtteranceSegmenter(UtteranceSegmenter.Config.defaults(), out::add);
        feed(s, 0.0f, 20);   // 640 ms silence
        feed(s, 0.9f, 31);   // ~1 s speech
        feed(s, 0.1f, 25);   // 800 ms silence (> 700 ms end threshold)
        assertEquals(1, out.size());
        PcmSegment seg = out.getFirst();
        // pre-roll of 200 ms rounds up to 7 frames before speech start at frame 20
        assertEquals((20 - 7) * FRAME, seg.startSample());
        assertFalse(seg.forcedSplit());
        // 7 pre-roll + 31 speech frames + 150 ms pad (2400 samples) of the trailing silence
        assertEquals(38 * FRAME + 2400, seg.samples().length);
        assertFalse(s.isInSpeech());
    }

    @Test
    void shortBlipsAreDiscarded() {
        var s = new UtteranceSegmenter(UtteranceSegmenter.Config.defaults(), out::add);
        feed(s, 0.9f, 3);    // 96 ms < 250 ms minimum
        feed(s, 0.0f, 30);
        assertTrue(out.isEmpty());
    }

    @Test
    void hysteresisKeepsSpeechThroughMidProbabilities() {
        var s = new UtteranceSegmenter(UtteranceSegmenter.Config.defaults(), out::add);
        feed(s, 0.9f, 10);
        feed(s, 0.4f, 40);   // between end (0.35) and start (0.5) thresholds: still speech
        assertTrue(s.isInSpeech());
        assertTrue(out.isEmpty());
    }

    @Test
    void briefPauseDoesNotSplitUtterance() {
        var s = new UtteranceSegmenter(UtteranceSegmenter.Config.defaults(), out::add);
        feed(s, 0.9f, 20);
        feed(s, 0.1f, 15);   // 480 ms pause < 700 ms
        feed(s, 0.9f, 20);
        feed(s, 0.1f, 25);
        assertEquals(1, out.size());
    }

    @Test
    void longUtteranceEndsAtAShorterPause() {
        // Fluent speakers rarely pause 700 ms; found on Windows: every line ran to the 15 s maximum.
        var s = new UtteranceSegmenter(UtteranceSegmenter.Config.defaults(), out::add);
        feed(s, 0.9f, 200);  // 6.4 s speech: past the 6 s soft limit
        feed(s, 0.1f, 10);   // 320 ms pause >= 300 ms
        assertEquals(1, out.size());
        assertFalse(out.getFirst().forcedSplit(), "cut at the pause, not forced");
        assertFalse(s.isInSpeech());
    }

    @Test
    void shortUtteranceKeepsTheLongPauseRule() {
        var s = new UtteranceSegmenter(UtteranceSegmenter.Config.defaults(), out::add);
        feed(s, 0.9f, 100);  // 3.2 s: below the soft limit
        feed(s, 0.1f, 10);   // 320 ms pause is just a breath here
        feed(s, 0.9f, 20);
        assertTrue(out.isEmpty());
        assertTrue(s.isInSpeech());
    }

    @Test
    void longMonologueIsForceSplitAndContinues() {
        var cfg = UtteranceSegmenter.Config.defaults().withMaxUtteranceMs(2_000);
        var s = new UtteranceSegmenter(cfg, out::add);
        feed(s, 0.9f, 150);  // 4.8 s continuous speech
        assertEquals(2, out.size());
        assertTrue(out.get(0).forcedSplit());
        assertEquals(out.get(0).endSample(), out.get(1).startSample(), "segments must be contiguous");
        assertTrue(s.isInSpeech());
        s.flush();
        assertEquals(3, out.size());
        assertFalse(out.get(2).forcedSplit());
    }

    @Test
    void flushEmitsInProgressUtterance() {
        var s = new UtteranceSegmenter(UtteranceSegmenter.Config.defaults(), out::add);
        feed(s, 0.9f, 20);
        s.flush();
        assertEquals(1, out.size());
        assertFalse(s.isInSpeech());
    }

    @Test
    void energyVadSeparatesLoudFromQuiet() {
        var vad = new EnergyVad(FRAME, -40);
        float[] quiet = new float[FRAME];
        float[] loud = new float[FRAME];
        for (int i = 0; i < FRAME; i++) {
            quiet[i] = (float) (0.001 * Math.sin(i));
            loud[i] = (float) (0.3 * Math.sin(i * 0.2));
        }
        assertTrue(vad.speechProbability(quiet) < 0.05);
        assertTrue(vad.speechProbability(loud) > 0.95);
    }
}
