package com.vct.capture;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the FFM binding against native/stub (same C interface as the WASAPI DLL, fake audio).
 * Skipped where the stub could not be built (Windows, or no C compiler).
 */
class WasapiBackendTest {

    private static WasapiBackend backend;

    @BeforeAll
    static void load() throws CaptureException {
        String stub = System.getProperty("vct.audio.stub");
        assumeTrue(stub != null && Files.isRegularFile(Path.of(stub)), "native stub not built");
        backend = WasapiBackend.load(Path.of(stub));
    }

    @Test
    void listsDevicesWithUnicodeAndEscapes() throws CaptureException {
        List<AudioDevice> mics = backend.listDevices(AudioDevice.Flow.CAPTURE);
        assertEquals(new AudioDevice("stub-mic", "Stub Mikrofon ü \"USB\"", true, true), mics.getFirst());
        assertEquals(2, mics.size());
        assertEquals("stub-speaker", backend.listDevices(AudioDevice.Flow.RENDER).getFirst().id());
    }

    @Test
    void readsInterleavedFloatsThroughNativeMemory() throws CaptureException {
        try (CaptureStream s = backend.open(new CaptureTarget.Loopback(null), 48_000, 2)) {
            float[] buf = new float[4800 * 2];
            int frames = s.read(buf, 50);
            assertEquals(480, frames, "stub delivers 10 ms per read");
            float peak = 0;
            for (int i = 0; i < frames; i++) {
                assertEquals(buf[2 * i], buf[2 * i + 1], "both channels carry the same sine");
                peak = Math.max(peak, Math.abs(buf[2 * i]));
            }
            assertTrue(peak > 0.45f && peak <= 0.5f, "0.5 amplitude sine, peak " + peak);
            assertEquals(480, s.stats().framesCaptured());
        }
    }

    @Test
    void mapsNativeFailuresToKinds() {
        CaptureException missing = assertThrows(CaptureException.class,
                () -> backend.open(new CaptureTarget.Device("missing"), 16_000, 1));
        assertEquals(CaptureException.Kind.NOT_FOUND, missing.kind());
        assertTrue(missing.getMessage().startsWith("Opening the microphone failed"), missing.getMessage());

        CaptureException badFormat = assertThrows(CaptureException.class,
                () -> backend.open(new CaptureTarget.Device(null), 16_000, 9));
        assertEquals(CaptureException.Kind.UNSUPPORTED_FORMAT, badFormat.kind());
    }

    @Test
    void passesLargeProcessIdsAsUnsigned() throws CaptureException {
        // Windows PIDs are DWORDs; Java passes them as a signed int with the same bits.
        try (CaptureStream s = backend.open(new CaptureTarget.ProcessTree(0xFFFF_FFF0L,
                CaptureTarget.ProcessTree.Mode.EXCLUDE), 16_000, 1)) {
            assertEquals(1, s.channels());
        }
    }

    @Test
    void captureLoopRecoversFromNativeDeviceLoss() throws Exception {
        List<String> states = new CopyOnWriteArrayList<>();
        AtomicInteger opens = new AtomicInteger();
        CaptureLoop.TargetResolver resolver = () -> new CaptureTarget.Device(opens.getAndIncrement() == 0 ? "stub-die" : "stub-mic");
        List<float[]> got = new CopyOnWriteArrayList<>();
        CaptureLoop loop = new CaptureLoop("stub", backend, resolver, got::add, (s, d) -> states.add(s + ": " + d),
                new CaptureLoop.Config(16_000, Duration.ofMillis(20), Duration.ofMillis(10), Duration.ofMillis(10),
                        Duration.ofMillis(50))).start();
        try {
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (opens.get() < 2 || got.size() < 5) {
                assertTrue(System.nanoTime() < deadline, "no recovery: " + states);
                Thread.sleep(10);
            }
        } finally {
            loop.close();
        }
        assertTrue(states.stream().anyMatch(s -> s.contains("disconnected or changed") && s.contains("0x88890004")),
                states.toString());
        assertTrue(states.getLast().startsWith("STOPPED"), states.toString());
    }
}
