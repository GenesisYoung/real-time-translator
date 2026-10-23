package com.vct.capture;

import com.vct.capture.FakeBackend.FakeStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CaptureLoopTest {

    private static final CaptureLoop.Config FAST = new CaptureLoop.Config(16_000, Duration.ofMillis(10),
            Duration.ofMillis(10), Duration.ofMillis(40), Duration.ofMillis(20));
    private static final CaptureTarget DEFAULT_MIC = new CaptureTarget.Device(null);

    private final FakeBackend backend = new FakeBackend();
    private final List<float[]> received = new CopyOnWriteArrayList<>();
    private final List<String> states = new CopyOnWriteArrayList<>();
    private final Set<Long> deadPids = ConcurrentHashMap.newKeySet();
    private CaptureLoop loop;

    @AfterEach
    void tearDown() {
        if (loop != null) loop.close();
    }

    private CaptureLoop start(CaptureLoop.TargetResolver resolver) {
        loop = new CaptureLoop("test", backend, resolver, received::add,
                (s, d) -> states.add(s + (d == null ? "" : ": " + d)), FAST, pid -> !deadPids.contains(pid));
        return loop.start();
    }

    private static FakeStream stream16k(Object... items) {
        return new FakeStream(16_000, 1).feed(items);
    }

    private int receivedSamples() {
        return received.stream().mapToInt(a -> a.length).sum();
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            Thread.sleep(5);
        }
    }

    @Test
    void deliversMonoAudioUnchanged() throws Exception {
        float[] chunk = {0.1f, -0.2f, 0.3f};
        backend.script(c -> stream16k(chunk));
        start(CaptureLoop.TargetResolver.of(DEFAULT_MIC));
        await("audio", () -> !received.isEmpty());
        assertArrayEquals(chunk, received.getFirst());
        assertEquals(new FakeBackend.OpenCall(DEFAULT_MIC, 16_000, 1), backend.opens.getFirst());
        assertEquals(CaptureLoop.State.RUNNING, loop.state());
        assertEquals("Default microphone", loop.detail());
    }

    @Test
    void fallsBackToStereo48kAndConvertsToMono16k() throws Exception {
        float[] stereo = new float[4800 * 2]; // 100 ms at 48 kHz, left 0.6, right 0.2
        for (int i = 0; i < 4800; i++) {
            stereo[2 * i] = 0.6f;
            stereo[2 * i + 1] = 0.2f;
        }
        backend.script(c -> { throw FakeBackend.error(CaptureException.Kind.UNSUPPORTED_FORMAT); })
                .script(c -> new FakeStream(48_000, 2).feed(stereo));
        start(CaptureLoop.TargetResolver.of(DEFAULT_MIC));
        await("converted audio", () -> receivedSamples() >= 1599);

        assertEquals(List.of(16_000, 48_000), backend.opens.stream().map(FakeBackend.OpenCall::sampleRate).toList());
        assertEquals(2, backend.opens.get(1).channels());
        int total = receivedSamples();
        assertTrue(total >= 1599 && total <= 1600, "100 ms at 16 kHz, got " + total);
        for (float[] a : received) for (float v : a) assertEquals(0.4f, v, 1e-6f);
        assertTrue(loop.detail().contains("48000 Hz, 2 ch, converted"), loop.detail());
    }

    @Test
    void reconnectsAfterDeviceIsLost() throws Exception {
        backend.script(c -> stream16k(new float[]{0.1f}, FakeBackend.error(CaptureException.Kind.DEVICE_LOST)))
                .script(c -> stream16k(new float[]{0.2f}));
        start(CaptureLoop.TargetResolver.of(DEFAULT_MIC));
        await("audio from the second stream", () -> received.size() == 2);

        assertEquals(0.2f, received.get(1)[0]);
        assertTrue(backend.streams.getFirst().closed, "failed stream is closed");
        assertTrue(states.stream().anyMatch(s -> s.startsWith("RECONNECTING: DEVICE_LOST")), states.toString());
        assertEquals(CaptureLoop.State.RUNNING, loop.state());
    }

    @Test
    void keepsRetryingWhileBlockedAndReportsWhy() throws Exception {
        CaptureException denied = CaptureException.fromHresult("Opening the microphone",
                CaptureException.E_ACCESSDENIED, "");
        for (int i = 0; i < 3; i++) backend.script(c -> { throw denied; });
        backend.script(c -> stream16k(new float[]{0.5f}));
        start(CaptureLoop.TargetResolver.of(DEFAULT_MIC));
        await("audio after access is granted", () -> !received.isEmpty());

        assertEquals(4, backend.opens.size());
        assertTrue(states.stream().anyMatch(s -> s.contains("Privacy & security > Microphone")), states.toString());
        // The same failure three times is reported once, not on every retry.
        assertEquals(1, states.stream().filter(s -> s.contains("Privacy")).count());
    }

    @Test
    void switchesWhenWindowsDefaultMicrophoneChanges() throws Exception {
        backend.captureDevices = List.of(new AudioDevice("A", "Headset", true, true), new AudioDevice("B", "Webcam", false, false));
        backend.script(c -> stream16k()).script(c -> stream16k());
        start(CaptureLoop.TargetResolver.of(DEFAULT_MIC));
        await("running", () -> loop.state() == CaptureLoop.State.RUNNING);

        backend.changes.incrementAndGet(); // e.g. a device was added, default unchanged
        Thread.sleep(80);
        assertEquals(1, backend.opens.size(), "no reopen while the default is the same");

        backend.captureDevices = List.of(new AudioDevice("A", "Headset", false, true), new AudioDevice("B", "Webcam", true, false));
        backend.changes.incrementAndGet();
        await("reopen", () -> backend.opens.size() == 2);
        assertTrue(states.stream().anyMatch(s -> s.contains("default device changed to Webcam")), states.toString());
        assertTrue(backend.streams.getFirst().closed);
    }

    @Test
    void explicitDeviceIgnoresDefaultChanges() throws Exception {
        backend.captureDevices = List.of(new AudioDevice("A", "Headset", true, true));
        backend.script(c -> stream16k());
        start(CaptureLoop.TargetResolver.of(new CaptureTarget.Device("A")));
        await("running", () -> loop.state() == CaptureLoop.State.RUNNING);
        backend.captureDevices = List.of(new AudioDevice("A", "Headset", false, true), new AudioDevice("B", "Webcam", true, false));
        backend.changes.incrementAndGet();
        Thread.sleep(80);
        assertEquals(1, backend.opens.size());
    }

    @Test
    void findsAppAgainAfterItRestarts() throws Exception {
        AtomicInteger resolves = new AtomicInteger();
        List<Long> pids = new ArrayList<>(List.of(100L, 200L));
        CaptureLoop.TargetResolver discord = () -> {
            resolves.incrementAndGet();
            if (resolves.get() == 2) throw new CaptureException(CaptureException.Kind.NOT_FOUND, "Discord is not running.");
            return new CaptureTarget.ProcessTree(pids.removeFirst(), CaptureTarget.ProcessTree.Mode.INCLUDE);
        };
        backend.script(c -> stream16k()).script(c -> stream16k());
        start(discord);
        await("running on pid 100", () -> loop.state() == CaptureLoop.State.RUNNING);

        deadPids.add(100L);
        await("reopen on pid 200", () -> backend.opens.size() == 2);
        assertEquals(new CaptureTarget.ProcessTree(200, CaptureTarget.ProcessTree.Mode.INCLUDE), backend.opens.get(1).target());
        assertTrue(states.stream().anyMatch(s -> s.contains("process 100) has exited")), states.toString());
        assertTrue(states.stream().anyMatch(s -> s.contains("Discord is not running")), states.toString());
    }

    @Test
    void closeStopsPromptlyAndClosesTheStream() throws Exception {
        backend.script(c -> stream16k());
        start(CaptureLoop.TargetResolver.of(DEFAULT_MIC));
        await("running", () -> loop.state() == CaptureLoop.State.RUNNING);
        long t0 = System.nanoTime();
        loop.close();
        assertTrue(System.nanoTime() - t0 < 1_000_000_000L, "close took too long");
        assertEquals(CaptureLoop.State.STOPPED, loop.state());
        assertTrue(backend.streams.getFirst().closed);
    }

    @Test
    void closeDuringBackoffDoesNotWaitForTheRetry() throws Exception {
        loop = new CaptureLoop("test", backend, CaptureLoop.TargetResolver.of(DEFAULT_MIC), received::add, (s, d) -> {},
                new CaptureLoop.Config(16_000, Duration.ofMillis(10), Duration.ofSeconds(30), Duration.ofSeconds(30),
                        Duration.ofMillis(20)), pid -> true).start();
        await("first failure", () -> loop.state() == CaptureLoop.State.RECONNECTING); // nothing scripted
        long t0 = System.nanoTime();
        loop.close();
        assertTrue(System.nanoTime() - t0 < 1_000_000_000L, "close waited for the 30 s backoff");
        assertEquals(CaptureLoop.State.STOPPED, loop.state());
    }
}
