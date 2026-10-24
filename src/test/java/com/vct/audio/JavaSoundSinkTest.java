package com.vct.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JavaSoundSinkTest {

    @Test
    void convertsToLittleEndianPcm16WithClipping() {
        assertArrayEquals(new byte[]{0, 0, (byte) 0xFF, 0x7F, 0x01, (byte) 0x80, (byte) 0xFF, 0x7F},
                JavaSoundSink.toPcm16(new float[]{0f, 1f, -1f, 3f}));
    }

    @Test
    void unknownDeviceListsTheAvailableOnes() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new JavaSoundSink("no such headphones"));
        assertTrue(e.getMessage().startsWith("No output device matches \"no such headphones\". Available: "), e.getMessage());
    }

    /** Needs an output device: real hardware, or ALSA's null device on Linux. */
    @Test
    void stopEndsPlaybackEarlyAndTheSinkPlaysAgainAfterwards() throws Exception {
        assumeTrue(!JavaSoundSink.outputDevices().isEmpty(), "no audio output");
        JavaSoundSink sink = new JavaSoundSink();
        AudioClip tenSeconds = new AudioClip(new float[22_050 * 10], 22_050);
        CompletableFuture<Void> playing = CompletableFuture.runAsync(() -> assertDoesNotThrow(() -> sink.play(tenSeconds)));
        Thread.sleep(200);
        long t0 = System.nanoTime();
        sink.stop();
        playing.get(2, TimeUnit.SECONDS);
        assertTrue(System.nanoTime() - t0 < 1_000_000_000L, "stop took too long");

        sink.play(new AudioClip(new float[2_205], 22_050)); // stop() affected only the clip that was playing
        sink.stop(); // nothing playing: no-op
    }
}
