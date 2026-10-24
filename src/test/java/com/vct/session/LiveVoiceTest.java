package com.vct.session;

import com.vct.audio.AudioClip;
import com.vct.audio.AudioSink;
import com.vct.audio.AudioStream;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LiveVoiceTest {

    private static final int RATE = 16_000, BLOCK = 160; // 10 ms blocks

    /** A clock the test moves by hand. */
    private static final class ManualClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-24T12:00:00Z");

        void advance(long ms) { now = now.plusMillis(ms); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    private final ManualClock clock = new ManualClock();
    private final AtomicInteger written = new AtomicInteger();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private volatile boolean cableBroken;

    private final AudioSink cable = new AudioSink() {
        @Override public void play(AudioClip clip) {}
        @Override public void stop() {}

        @Override
        public AudioStream openStream(int sampleRate) {
            if (cableBroken) throw new IllegalStateException("Cannot play on CABLE Input: device lost");
            events.add("open " + sampleRate);
            return new AudioStream() {
                @Override public void write(float[] samples) { written.addAndGet(samples.length); }
                @Override public void close() { events.add("close"); }
            };
        }
    };

    private final LiveVoice live = new LiveVoice(cable, RATE, Duration.ofMillis(150), Duration.ofMillis(300), clock);

    /** Feeds {@code ms} of microphone; returns true if the live time ended meanwhile. */
    private boolean speak(int ms) {
        boolean ended = false;
        for (int i = 0; i < ms / 10; i++) {
            clock.advance(10);
            ended |= live.onAudio(new float[BLOCK]);
        }
        return ended;
    }

    @Test
    void sendsTheMomentBeforeThePressThenEverythingUntilTheTailIsOver() throws Exception {
        speak(1_000);
        assertEquals(0, written.get(), "nothing is sent before the key");
        assertTrue(live.press());
        assertEquals(List.of("open 16000"), events);
        assertEquals(15 * BLOCK, written.get(), "the last 150 ms before the press");

        speak(500);
        assertEquals(65 * BLOCK, written.get());
        live.release();
        assertFalse(speak(200), "the tail keeps the last word");
        assertTrue(live.isLive());
        assertTrue(speak(100), "then it ends");
        assertFalse(live.isLive());
        int sent = written.get();
        speak(500);
        assertEquals(sent, written.get(), "nothing after the end");
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!events.contains("close")) {
            if (System.nanoTime() > deadline) fail("stream not closed: " + events);
            Thread.sleep(5);
        }
    }

    @Test
    void knowsWhichLinesWereSpokenLive() {
        Instant start = clock.instant();
        speak(1_000);
        live.press();
        speak(2_000);
        live.release();
        speak(400);
        Instant pressed = start.plusMillis(1_000);

        assertTrue(live.wasLive(pressed.plusMillis(200), pressed.plusMillis(1_800)), "said while holding the key");
        assertFalse(live.wasLive(start, start.plusMillis(800)), "said before the key");
        assertFalse(live.wasLive(start, pressed.plusMillis(300)), "mostly before the key");
        assertTrue(live.wasLive(pressed.plusMillis(1_500), pressed.plusMillis(2_300)), "the tail counts as live");
    }

    @Test
    void pressingAgainDuringTheTailKeepsItLive() {
        live.press();
        speak(100);
        live.release();
        speak(200);
        assertFalse(live.press(), "already live");
        assertFalse(speak(1_000), "the earlier release no longer counts");
        assertTrue(live.isLive());
    }

    @Test
    void aMissingCableIsReportedAndNothingStaysLive() {
        cableBroken = true;
        IllegalStateException e = assertThrows(IllegalStateException.class, live::press);
        assertTrue(e.getMessage().contains("device lost"));
        assertFalse(live.isLive());
        cableBroken = false;
        assertTrue(live.press(), "works again once the device is back");
    }
}
