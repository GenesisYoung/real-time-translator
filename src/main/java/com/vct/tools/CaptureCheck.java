package com.vct.tools;

import com.vct.capture.AudioDevice;
import com.vct.capture.CaptureBackend;
import com.vct.capture.CaptureException;
import com.vct.capture.CaptureLoop;
import com.vct.capture.CaptureTarget;
import com.vct.capture.DiscordLocator;
import com.vct.capture.WasapiBackend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks audio capture on this PC: lists devices, records the microphone and the remote source for a
 * few seconds while showing input levels, and saves {@code mic.wav} and {@code remote.wav}.
 *
 * <pre>
 * gradlew captureCheck --args="--seconds 15 --remote discord"
 *   --list                   only list devices
 *   --seconds N              recording length (default 10)
 *   --mic default|none|ID    microphone to record (default: Windows default)
 *   --remote discord|others|system|none
 *                            discord = Discord only; others = everything except this app;
 *                            system = everything the default output plays (default: discord)
 *   --out DIR                where to write the WAV files (default: build/capture-check)
 * </pre>
 */
public final class CaptureCheck {

    private static final int RATE = 16_000;

    private CaptureCheck() {}

    public static void main(String[] args) throws Exception {
        boolean listOnly = false;
        int seconds = 10;
        String mic = "default";
        String remote = "discord";
        Path outDir = Path.of("build", "capture-check");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--list" -> listOnly = true;
                case "--seconds" -> seconds = Integer.parseInt(args[++i]);
                case "--mic" -> mic = args[++i];
                case "--remote" -> remote = args[++i].toLowerCase(Locale.ROOT);
                case "--out" -> outDir = Path.of(args[++i]);
                default -> {
                    System.err.println("Unknown option " + args[i] + ". See the class comment for usage.");
                    System.exit(2);
                }
            }
        }

        if (!List.of("discord", "others", "system", "none").contains(remote)) {
            System.err.println("Unknown --remote value \"" + remote + "\". Use discord, others, system or none.");
            System.exit(2);
        }

        WasapiBackend backend;
        try {
            backend = WasapiBackend.load();
        } catch (CaptureException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        printDevices(backend);
        if (listOnly) {
            backend.shutdown();
            return;
        }

        Files.createDirectories(outDir);
        List<Channel> channels = new ArrayList<>();
        if (!mic.equals("none")) {
            CaptureTarget t = new CaptureTarget.Device(mic.equals("default") ? null : mic);
            channels.add(new Channel("mic", backend, CaptureLoop.TargetResolver.of(t), outDir.resolve("mic.wav")));
        }
        CaptureLoop.TargetResolver remoteResolver = switch (remote) {
            case "none" -> null;
            case "discord" -> DiscordLocator.resolver();
            case "others" -> CaptureLoop.TargetResolver.of(
                    new CaptureTarget.ProcessTree(ProcessHandle.current().pid(), CaptureTarget.ProcessTree.Mode.EXCLUDE));
            case "system" -> CaptureLoop.TargetResolver.of(new CaptureTarget.Loopback(null));
            default -> throw new IllegalArgumentException("--remote must be discord, others, system or none");
        };
        if (remoteResolver != null) channels.add(new Channel("remote", backend, remoteResolver, outDir.resolve("remote.wav")));

        System.out.printf("%nRecording %d s. Speak into the microphone and play voice chat audio.%n%n", seconds);
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < end) {
            Thread.sleep(500);
            StringBuilder line = new StringBuilder();
            for (Channel c : channels) line.append(c.meterLine()).append("   ");
            System.out.println(line.toString().stripTrailing());
        }

        System.out.println();
        boolean ok = true;
        for (Channel c : channels) {
            try {
                c.finish();
            } catch (IOException e) {
                ok = false;
                System.err.println("Could not finish " + c.label + ".wav: " + e);
            }
        }
        backend.shutdown();
        if (!ok) System.exit(1);
        System.out.println("\nWAV files are in " + outDir.toAbsolutePath()
                + ". Listen to them: speech should be clear, with no gaps or crackling.");
    }

    private static void printDevices(CaptureBackend backend) throws CaptureException {
        for (AudioDevice.Flow flow : AudioDevice.Flow.values()) {
            System.out.println(flow == AudioDevice.Flow.CAPTURE ? "Microphones:" : "Outputs:");
            for (AudioDevice d : backend.listDevices(flow)) {
                String mark = d.isDefault() ? " [default]" : "";
                if (d.communications()) mark += " [communications]";
                System.out.printf("  %s%s%n      id: %s%n", d.name(), mark, d.id());
            }
        }
        DiscordLocator.findRootPid().ifPresentOrElse(
                pid -> System.out.println("Discord: running, root process " + pid),
                () -> System.out.println("Discord: not running"));
    }

    /** One recorded source: capture loop, WAV file and a peak meter. */
    private static final class Channel {
        final String label;
        final WavWriter wav;
        final CaptureLoop loop;
        final AtomicReference<Float> peak = new AtomicReference<>(0f);
        volatile long samples;

        Channel(String label, CaptureBackend backend, CaptureLoop.TargetResolver resolver, Path file) throws IOException {
            this.label = label;
            this.wav = new WavWriter(file, RATE);
            this.loop = new CaptureLoop(label, backend, resolver, this::onAudio,
                    (state, detail) -> System.out.printf("[%s] %s%s%n", label, state, detail == null ? "" : ": " + detail),
                    CaptureLoop.Config.defaults()).start();
        }

        void onAudio(float[] x) {
            float p = 0;
            for (float v : x) p = Math.max(p, Math.abs(v));
            peak.accumulateAndGet(p, Math::max);
            samples += x.length;
            try {
                wav.write(x);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String meterLine() {
            float p = peak.getAndSet(0f);
            double db = p <= 0 ? -99 : 20 * Math.log10(p);
            int bars = (int) Math.max(0, Math.min(20, (db + 60) / 3)); // -60 dBFS .. 0 dBFS
            return String.format(Locale.ROOT, "%-6s %-20s %5.1f dBFS", label, "#".repeat(bars), db);
        }

        void finish() throws IOException {
            String what = loop.detail(); // read before close: a stopped loop has no detail
            var s = loop.stats();
            loop.close();
            wav.close();
            System.out.printf(Locale.ROOT, "%-6s %s: %.1f s recorded, %d frames dropped, %d discontinuities%n",
                    label, what, samples / (double) RATE,
                    s.framesDropped(), s.discontinuities());
        }
    }
}
