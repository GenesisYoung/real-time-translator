package com.vct.app;

import com.vct.audio.AudioClip;
import com.vct.audio.AudioSink;
import com.vct.capture.AudioDevice;
import com.vct.capture.CaptureBackend;
import com.vct.capture.CaptureException;
import com.vct.capture.CaptureLoop;
import com.vct.capture.CaptureStream;
import com.vct.capture.CaptureTarget;
import com.vct.model.Source;
import com.vct.model.Utterance;
import com.vct.segment.EnergyVad;
import com.vct.segment.VoiceActivityDetector;
import com.vct.stt.SpeechRecognizer;
import com.vct.stt.SttResult;
import com.vct.translate.TranslationProvider;
import com.vct.translate.TranslationRequest;
import com.vct.translate.TranslationResult;
import com.vct.tts.TtsEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SessionControllerTest {

    @TempDir
    Path dir;

    private final AtomicReference<String> apiKey = new AtomicReference<>("sk-test");
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<Utterance> heard = new CopyOnWriteArrayList<>();
    private final List<TranslationResult> translated = new CopyOnWriteArrayList<>();
    private final List<AudioClip> played = new CopyOnWriteArrayList<>();
    private final List<String> sentToVoiceChat = new CopyOnWriteArrayList<>();
    private final AtomicInteger liveSamples = new AtomicInteger();
    private final FakeEngines engines = new FakeEngines();
    private SessionController controller;

    private final SessionController.Listener listener = new SessionController.Listener() {
        @Override public void onStatus(SessionController.Status s, String d) { events.add("status " + s + ": " + d); }
        @Override public void onNotice(String m) { events.add("notice " + m); }
        @Override public void onSubtitlesState(boolean on) { events.add("subtitles " + on); }
        @Override public void onError(String m, Throwable t) { events.add("error " + m); }
        @Override public void onUtterance(Utterance u) { heard.add(u); }
        @Override public void onTranslation(Utterance u, TranslationResult r) { translated.add(r); }
        @Override public void onCaptureState(Source s, CaptureLoop.State st, String d) { events.add(s + " " + st); }
        @Override public void onRelayState(boolean active, boolean paused, String lang) { events.add("relay " + active + " " + paused + " " + lang); }
        @Override public void onRelaySent(Utterance u, com.vct.session.SpeechRelay.Delay d) { events.add("sent " + u.id()); }
        @Override public void onRelayLive(boolean live) { events.add("live " + live); }
    };

    @AfterEach
    void tearDown() {
        if (controller != null) controller.close();
    }

    private SessionController controller() {
        controller = new SessionController(engines, apiKey::get, listener);
        return controller;
    }

    private static void await(String what, BooleanSupplier c) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    @Test
    void hearsRemoteSpeechTranslatesItAndSpeaksIt() throws Exception {
        SessionController c = controller();
        c.start(AppSettings.defaults()).get(5, TimeUnit.SECONDS);
        assertEquals(SessionController.Status.RUNNING, c.status());
        assertTrue(events.contains("status LOADING: Loading speech engines..."), events.toString());

        await("remote utterance", () -> heard.stream().anyMatch(u -> u.source() == Source.REMOTE));
        assertEquals("Go left.", heard.getFirst().text());

        c.translateLatest();
        await("translation", () -> !translated.isEmpty());
        assertEquals("往左走。", translated.getFirst().text());
        await("playback", () -> !played.isEmpty());

        assertTrue(Files.readString(c.transcriptFile()).contains("Go left."), "transcript written");
        c.stop().get(5, TimeUnit.SECONDS);
        assertEquals(SessionController.Status.STOPPED, c.status());
        assertTrue(engines.streams.stream().allMatch(s -> s.closed), "capture streams closed");
    }

    @Test
    void reusesLoadedEnginesAndReloadsOnlyWhatChanged() throws Exception {
        SessionController c = controller();
        AppSettings s = AppSettings.defaults();
        c.start(s).get(5, TimeUnit.SECONDS);
        c.start(s).get(5, TimeUnit.SECONDS); // restart: same models
        assertEquals(1, engines.recognizersOpened.get());
        assertEquals(1, engines.ttsOpened.get());

        AppSettings other = new AppSettings(s.remoteSource(), s.micDeviceId(), s.outputDevice(), s.nativeLanguage(),
                s.recognizedLanguages(), "models/whisper/other.bin", s.whisperGpu(), s.ttsVoice(), s.ttsSpeed(),
                s.openAiModel(), s.translateHotkey(), s.replayHotkey(), s.contextLines(), s.relay(), s.subtitles());
        c.start(other).get(5, TimeUnit.SECONDS);
        assertEquals(2, engines.recognizersOpened.get());
        assertEquals(1, engines.recognizersClosed.get(), "the replaced recognizer was freed");
        assertEquals(1, engines.ttsOpened.get(), "the voice did not change");
    }

    @Test
    void failedStartReportsWhyAndLeavesNothingRunning() throws Exception {
        engines.recognizerFailure = new IllegalStateException("Whisper model not found: models/whisper/x.bin.");
        SessionController c = controller();
        ExecutionException e = assertThrows(ExecutionException.class, () -> c.start(AppSettings.defaults()).get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause().getMessage().contains("Whisper model not found"), e.toString());
        assertEquals(SessionController.Status.STOPPED, c.status());
        assertTrue(events.contains("status STOPPED: Could not start: Whisper model not found: models/whisper/x.bin."), events.toString());
        assertTrue(engines.streams.isEmpty(), "no capture started");
    }

    private static AppSettings withRelay(boolean on) {
        AppSettings d = AppSettings.defaults();
        AppSettings.RelaySettings r = d.relay();
        return new AppSettings(d.remoteSource(), d.micDeviceId(), d.outputDevice(), d.nativeLanguage(),
                d.recognizedLanguages(), d.whisperModel(), d.whisperGpu(), d.ttsVoice(), d.ttsSpeed(), d.openAiModel(),
                d.translateHotkey(), d.replayHotkey(), d.contextLines(),
                new AppSettings.RelaySettings(on, "ja", r.outputDevice(), r.ttsModel(), r.voice(), r.pauseHotkey(), r.liveHotkey()),
                d.subtitles());
    }

    @Test
    void relaysTheUsersOwnSpeechIntoTheVoiceChat() throws Exception {
        engines.micSpeaks = true;
        SessionController c = controller();
        c.start(withRelay(true)).get(5, TimeUnit.SECONDS);
        assertTrue(events.contains("relay true false ja"), events.toString());
        await("own line sent", () -> !sentToVoiceChat.isEmpty());
        assertEquals("往左走。", sentToVoiceChat.getFirst(), "translated, then spoken with the relay voice");
        assertTrue(played.isEmpty(), "nothing on the user's headphones");

        c.toggleRelayPaused();
        assertTrue(events.contains("relay true true ja"), events.toString());
        c.stop().get(5, TimeUnit.SECONDS);
        assertTrue(events.contains("relay false false null"), events.toString());
    }

    @Test
    void holdingTheLiveKeySendsTheMicrophoneIntoTheCable() throws Exception {
        SessionController c = controller();
        c.setLive(true);
        assertTrue(events.contains("notice Talk live needs Translate my speech. Turn it on in Settings, then start a session."),
                events.toString());

        c.start(withRelay(true)).get(5, TimeUnit.SECONDS);
        c.setLive(true);
        assertTrue(events.contains("live true"), events.toString());
        await("microphone sent live", () -> liveSamples.get() > 16_000);
        c.setLive(false);
        await("live ended after the tail", () -> events.contains("live false") && events.contains("live stream closed"));
        int after = liveSamples.get();
        Thread.sleep(100);
        assertEquals(after, liveSamples.get(), "nothing sent once released");
    }

    @Test
    void subtitlesTranslateTheOthersWithoutAHotkeyAndCanBeSwitchedOff() throws Exception {
        SessionController c = controller();
        c.setSubtitles(true, false);
        c.start(AppSettings.defaults()).get(5, TimeUnit.SECONDS);
        await("translated without pressing anything", () -> !translated.isEmpty());
        assertEquals("往左走。", translated.getFirst().text());
        assertTrue(played.isEmpty(), "not read aloud unless asked");

        c.toggleSubtitles();
        assertTrue(events.contains("subtitles false"), events.toString());
        assertFalse(c.subtitlesOn());
    }

    @Test
    void subtitlesWithoutAKeyExplainWhy() throws Exception {
        apiKey.set(null);
        SessionController c = controller();
        c.setSubtitles(true, false);
        c.start(AppSettings.defaults()).get(5, TimeUnit.SECONDS);
        assertTrue(events.contains("notice Add your OpenAI API key in Settings to translate the subtitles."), events.toString());
    }

    @Test
    void missingCableTurnsOffOnlyTranslateMySpeech() throws Exception {
        engines.cableMissing = true;
        SessionController c = controller();
        c.start(withRelay(true)).get(5, TimeUnit.SECONDS);
        assertEquals(SessionController.Status.RUNNING, c.status());
        assertTrue(events.stream().anyMatch(e -> e.startsWith("error Translate my speech is off: No output device matches")),
                events.toString());
        assertTrue(events.contains("relay false false null"), events.toString());
        c.toggleRelayPaused();
        assertTrue(events.contains("notice Translate my speech is off. Turn it on in Settings, then start a session."));
    }

    @Test
    void refusesToRelayWhenTheMicrophoneIsTheCable() throws Exception {
        // Installing VB-CABLE can make "CABLE Output" the default microphone: the app would then hear its
        // own translation as the user's speech and translate it again, forever.
        engines.microphones = List.of(new AudioDevice("{headset}", "Headset (WH-CH520)", false, false),
                new AudioDevice("{cable}", "CABLE Output (VB-Audio Virtual Cable)", true, true));
        SessionController c = controller();
        c.start(withRelay(true)).get(5, TimeUnit.SECONDS);
        assertEquals(SessionController.Status.RUNNING, c.status(), "translating others still works");
        assertTrue(events.stream().anyMatch(e -> e.startsWith(
                "error Translate my speech is off: the microphone is \"CABLE Output (VB-Audio Virtual Cable)\"")), events.toString());
        assertTrue(events.contains("relay false false null"), events.toString());
    }

    @Test
    void explainsWhyItCannotTranslateYet() throws Exception {
        SessionController c = controller();
        c.translateLatest();
        assertTrue(events.contains("notice Start a session first."), events.toString());

        apiKey.set(null);
        c.start(AppSettings.defaults()).get(5, TimeUnit.SECONDS);
        c.translateLatest();
        assertTrue(events.contains("notice Add your OpenAI API key in Settings to translate."), events.toString());

        apiKey.set("sk-later"); // entered in Settings while running: no restart needed
        await("remote utterance", () -> !heard.isEmpty());
        c.translateLatest();
        await("translation", () -> !translated.isEmpty());
        assertEquals("sk-later", engines.lastKeyUsed.get());
        c.replay();
        assertFalse(events.contains("notice Nothing to replay yet."));
    }

    // ------------------------------------------------------------------ fakes

    /** Remote: 1 s of loud noise (speech for EnergyVad), then silence. Mic: silence. */
    private final class FakeEngines implements SessionController.Engines {
        final List<FakeStream> streams = new CopyOnWriteArrayList<>();
        final AtomicInteger recognizersOpened = new AtomicInteger(), recognizersClosed = new AtomicInteger();
        final AtomicInteger ttsOpened = new AtomicInteger();
        final AtomicReference<String> lastKeyUsed = new AtomicReference<>();
        volatile RuntimeException recognizerFailure;
        volatile boolean micSpeaks;
        volatile boolean cableMissing;
        volatile List<AudioDevice> microphones = List.of(new AudioDevice("{headset}", "Headset (WH-CH520)", true, true));

        @Override
        public CaptureBackend captureBackend() {
            return new CaptureBackend() {
                @Override public List<AudioDevice> listDevices(AudioDevice.Flow flow) { return microphones; }
                @Override public long deviceChangeCount() { return 0; }

                @Override
                public CaptureStream open(CaptureTarget target, int rate, int channels) {
                    FakeStream s = new FakeStream(target instanceof CaptureTarget.ProcessTree || micSpeaks);
                    streams.add(s);
                    return s;
                }
            };
        }

        @Override public Supplier<VoiceActivityDetector> vadFactory() { return () -> new EnergyVad(512, -40); }

        @Override
        public SpeechRecognizer recognizer(AppSettings s) {
            if (recognizerFailure != null) throw recognizerFailure;
            recognizersOpened.incrementAndGet();
            return new SpeechRecognizer() {
                @Override
                public SttResult transcribe(com.vct.audio.PcmSegment seg, java.util.Set<String> langs) {
                    return new SttResult("Go left.", "en", 0.95, 0.9, 0.01);
                }

                @Override
                public void close() {
                    recognizersClosed.incrementAndGet();
                }
            };
        }

        @Override
        public TtsEngine tts(AppSettings s) {
            ttsOpened.incrementAndGet();
            return (text, lang, voice, speed) -> new AudioClip(new float[2_205], 22_050);
        }

        @Override
        public AudioSink sink(AppSettings s) {
            return new AudioSink() {
                @Override public void play(AudioClip clip) { played.add(clip); }
                @Override public void stop() {}
            };
        }

        @Override
        public TranslationProvider translator(AppSettings s, Supplier<String> key) {
            return new TranslationProvider() {
                @Override public String id() { return "fake"; }
                @Override public String model() { return s.openAiModel(); }
                @Override public List<String> listModels() { return List.of(s.openAiModel()); }

                @Override
                public TranslationResult translate(TranslationRequest r) {
                    lastKeyUsed.set(key.get());
                    return new TranslationResult("往左走。", "fake", s.openAiModel(), Duration.ofMillis(5), false);
                }
            };
        }

        /** The relay voice remembers its text; the fake cable records it when "played". */
        volatile String lastRelayText;

        @Override
        public TtsEngine relayTts(AppSettings s, Supplier<String> key) {
            return (text, lang, voice, speed) -> {
                lastRelayText = text;
                return new AudioClip(new float[240], 24_000);
            };
        }

        @Override
        public AudioSink relaySink(AppSettings s) {
            if (cableMissing) throw new IllegalArgumentException("No output device matches \"CABLE Input\". Available: []");
            return new AudioSink() {
                @Override public void play(AudioClip clip) { sentToVoiceChat.add(lastRelayText); }
                @Override public void stop() {}

                @Override
                public com.vct.audio.AudioStream openStream(int sampleRate) {
                    return new com.vct.audio.AudioStream() {
                        @Override public void write(float[] samples) { liveSamples.addAndGet(samples.length); }
                        @Override public void close() { events.add("live stream closed"); }
                    };
                }
            };
        }

        @Override public CaptureLoop.TargetResolver mic(AppSettings s) { return CaptureLoop.TargetResolver.of(new CaptureTarget.Device(null)); }
        @Override public CaptureLoop.TargetResolver remote(AppSettings s) {
            return CaptureLoop.TargetResolver.of(new CaptureTarget.ProcessTree(1, CaptureTarget.ProcessTree.Mode.EXCLUDE));
        }
        @Override public Path transcriptDir() { return dir.resolve("transcripts"); }
        @Override public Clock clock() { return Clock.systemUTC(); }
    }

    private static final class FakeStream implements CaptureStream {
        private final Random rnd = new Random(1);
        private int speechLeft;
        volatile boolean closed;

        FakeStream(boolean remote) {
            speechLeft = remote ? 16_000 : 0;
        }

        @Override public int sampleRate() { return 16_000; }
        @Override public int channels() { return 1; }
        @Override public Stats stats() { return new Stats(0, 0, 0); }
        @Override public void close() { closed = true; }

        @Override
        public int read(float[] dst, int timeoutMillis) throws CaptureException {
            try {
                Thread.sleep(5); // faster than real time, but not a busy loop
            } catch (InterruptedException e) {
                throw new CaptureException(CaptureException.Kind.CLOSED, "interrupted");
            }
            int n = Math.min(dst.length, 1_600);
            for (int i = 0; i < n; i++) dst[i] = speechLeft-- > 0 ? (float) (0.3 * rnd.nextGaussian()) : 0f;
            return n;
        }
    }
}
