package com.vct.session;

import com.vct.audio.AudioClip;
import com.vct.audio.AudioSink;
import com.vct.audio.AudioStream;
import com.vct.feedback.FeedbackGuard;
import com.vct.model.Source;
import com.vct.model.Utterance;
import com.vct.transcript.TranscriptSink;
import com.vct.translate.TranslationException;
import com.vct.translate.TranslationProvider;
import com.vct.translate.TranslationRequest;
import com.vct.translate.TranslationResult;
import com.vct.translate.TranslationService;
import com.vct.tts.TtsEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SpeechRelayTest {

    /** Translations per input; "slow" ones take 300 ms, so a later line can finish first. */
    private static final Map<String, String> ENGLISH = Map.of(
            "我们从左边绕过去。", "Let's go around the left.",
            "好的。", "Okay.",
            "等一下，我在换弹。", "Wait, I'm reloading.");

    private final List<TranslationRequest> requests = new CopyOnWriteArrayList<>();
    private final List<String> spoken = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final Map<String, String> transcript = new ConcurrentHashMap<>();
    private volatile boolean providerFails;
    private SpeechRelay relay;

    private final TranslationProvider provider = new TranslationProvider() {
        @Override public String id() { return "fake"; }
        @Override public String model() { return "m"; }
        @Override public List<String> listModels() { return List.of(); }

        @Override
        public TranslationResult translate(TranslationRequest r) throws TranslationException {
            requests.add(r);
            if (providerFails) throw new TranslationException(TranslationException.Kind.AUTH, "HTTP 401");
            if (r.text().startsWith("我们")) sleep(300);
            return new TranslationResult(ENGLISH.getOrDefault(r.text(), "?"), "fake", "m", Duration.ofMillis(1), false);
        }
    };

    /** "Plays" by recording the text the fake TTS put into the clip's length. */
    private final Map<Integer, String> clipText = new ConcurrentHashMap<>();
    private final AtomicInteger clipSeq = new AtomicInteger(); // synthesis runs in parallel
    private final TtsEngine tts = (text, lang, voice, speed) -> {
        AudioClip c = new AudioClip(new float[100 + clipSeq.incrementAndGet()], 24_000);
        clipText.put(c.samples().length, text);
        return c;
    };
    private final AtomicInteger liveSamples = new AtomicInteger();
    private final AudioSink cable = new AudioSink() {
        @Override public void play(AudioClip clip) { spoken.add(clipText.get(clip.samples().length)); }
        @Override public void stop() { events.add("stop"); }

        @Override
        public AudioStream openStream(int sampleRate) {
            return new AudioStream() {
                @Override public void write(float[] samples) { liveSamples.addAndGet(samples.length); }
                @Override public void close() {}
            };
        }
    };
    private final TranscriptSink transcriptSink = new TranscriptSink() {
        @Override public void writeUtterance(Utterance u) {}
        @Override public void writeTranslation(String id, String lang, TranslationResult r) { transcript.put(id + "|" + lang, r.text()); }
        @Override public void close() {}
    };

    private final FeedbackGuard echoGuard = FeedbackGuard.defaults(Clock.systemUTC());
    private final LiveVoice live = new LiveVoice(cable, 16_000, Duration.ofMillis(150), Duration.ZERO, Clock.systemUTC());

    private SpeechRelay relay(String target) {
        relay = new SpeechRelay(SpeechRelay.Config.defaults(target),
                new TranslationService(provider, new TranslationService.RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofSeconds(5))),
                tts, cable, transcriptSink, echoGuard, live, new SpeechRelay.Listener() {
                    @Override public void onRelaySkipped(Utterance u, String reason) { events.add("skip " + u.id() + ": " + reason); }
                    @Override public void onRelaySent(Utterance u, com.vct.session.SpeechRelay.Delay d) { events.add("sent " + u.id()); }
                    @Override public void onRelayError(Utterance u, String m) { events.add("error " + u.id() + ": " + m); }
                    @Override public void onRelayLive(boolean on) { events.add("live " + on); }
                });
        return relay;
    }

    @AfterEach
    void tearDown() {
        if (relay != null) relay.close();
    }

    private static int n;

    private static Utterance mine(String text, String lang, boolean unclear) {
        Instant t = Instant.now();
        return new Utterance("m-" + (++n), Source.MIC, t, t.plusSeconds(2), text, lang, 0.9, unclear ? 0.4 : 0.9, unclear);
    }

    private static Utterance mine(String text, String lang, Instant start, Instant end) {
        return new Utterance("m-" + (++n), Source.MIC, start, end, text, lang, 0.9, 0.9, false);
    }

    private static void await(String what, BooleanSupplier c) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            Thread.sleep(5);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void speaksTranslationsInTheOrderTheyWereSaid() throws Exception {
        SpeechRelay r = relay("en");
        Utterance first = mine("我们从左边绕过去。", "zh", false); // slow translation
        Utterance second = mine("好的。", "zh", false);          // fast translation
        r.submit(first);
        r.submit(second);
        await("both spoken", () -> spoken.size() == 2);
        assertEquals(List.of("Let's go around the left.", "Okay."), spoken);
        assertEquals("en", requests.getFirst().targetLanguage());
        assertEquals("Let's go around the left.", transcript.get(first.id() + "|en"));
        assertTrue(events.contains("sent " + first.id()) && events.contains("sent " + second.id()), events.toString());
        // What was spoken is known to the echo guard: hearing it back through a microphone is dropped.
        Instant now = Instant.now();
        assertTrue(echoGuard.isEcho("Let's go around the left.", now.minusSeconds(1), now));
    }

    @Test
    void sendsTheUsersPreviousLinesAsContext() throws Exception {
        SpeechRelay r = relay("en");
        r.submit(mine("我们从左边绕过去。", "zh", false));
        r.submit(mine("好的。", "zh", false));
        r.submit(mine("等一下，我在换弹。", "zh", false));
        await("three spoken", () -> spoken.size() == 3);
        TranslationRequest third = requests.stream().filter(q -> q.text().startsWith("等一下")).findFirst().orElseThrow();
        assertEquals(List.of("我们从左边绕过去。", "好的。"), third.context());
    }

    @Test
    void doesNotSendUnclearShortOrOthersLines() throws Exception {
        SpeechRelay r = relay("en");
        Utterance unclear = mine("我们从左边绕过去。", "zh", true);
        Utterance cough = mine("嗯。", "zh", false);
        Instant t = Instant.now();
        r.submit(unclear);
        r.submit(cough);
        r.submit(new Utterance("r-1", Source.REMOTE, t, t, "Push mid!", "en", 0.9, 0.9, false));
        Thread.sleep(100);
        assertTrue(spoken.isEmpty() && requests.isEmpty());
        assertTrue(events.contains("skip " + unclear.id() + ": unclear, not sent"), events.toString());
        assertTrue(events.contains("skip " + cough.id() + ": too short, not sent"), events.toString());
    }

    @Test
    void pauseSilencesAndSkipsUntilResumed() throws Exception {
        SpeechRelay r = relay("en");
        r.setPaused(true);
        assertTrue(events.contains("stop"), "pausing stops the line being spoken");
        Utterance aside = mine("好的。", "zh", false);
        r.submit(aside);
        assertTrue(events.contains("skip " + aside.id() + ": paused"));
        r.setPaused(false);
        r.submit(mine("好的。", "zh", false));
        await("spoken after resume", () -> spoken.size() == 1);
        assertEquals(1, requests.size());
    }

    @Test
    void lineAlreadyInTheTargetLanguageIsSpokenWithoutTranslating() throws Exception {
        SpeechRelay r = relay("ja");
        r.submit(mine("ありがとう、助かった！", "ja", false));
        await("spoken", () -> spoken.size() == 1);
        assertEquals(List.of("ありがとう、助かった！"), spoken);
        assertTrue(requests.isEmpty(), "no API call");
    }

    @Test
    void aFailedLineIsReportedAndLaterLinesStillGoOut() throws Exception {
        SpeechRelay r = relay("en");
        providerFails = true;
        Utterance bad = mine("好的。", "zh", false);
        r.submit(bad);
        await("error", () -> events.stream().anyMatch(e -> e.startsWith("error " + bad.id())));
        providerFails = false;
        r.submit(mine("等一下，我在换弹。", "zh", false));
        await("next line spoken", () -> spoken.size() == 1);
        assertEquals("Wait, I'm reloading.", spoken.getFirst());
    }

    @Test
    void talkingLiveSendsTheRealVoiceAndTranslationsWait() throws Exception {
        SpeechRelay r = relay("en");
        Instant before = Instant.now().minusSeconds(3);
        r.setLive(true);
        assertTrue(events.contains("stop") && events.contains("live true"), "cuts the translation playing: " + events);
        r.onMicAudio(new float[1_600]);
        assertEquals(1_600, liveSamples.get(), "the microphone goes straight into the cable");

        // Said in English while holding the key: already heard, not translated.
        Thread.sleep(300);
        Utterance english = mine("Push mid now.", "en", Instant.now().minusMillis(250), Instant.now());
        r.submit(english);
        assertTrue(events.contains("skip " + english.id() + ": spoken live, not translated"), events.toString());

        // Said in Chinese before the key: translated, but spoken only once the user is done.
        r.submit(mine("好的。", "zh", before, before.plusSeconds(1)));
        Thread.sleep(200);
        assertTrue(spoken.isEmpty(), "never over the user's own voice");
        r.setLive(false);
        r.onMicAudio(new float[160]); // the (zero) tail is over
        assertTrue(events.contains("live false"), events.toString());
        await("spoken after", () -> spoken.size() == 1);
        assertEquals(List.of("Okay."), spoken);
        assertEquals(1, requests.size(), "only the Chinese line was translated");
    }

    @Test
    void reportsHowLongAfterTheUserStoppedTheTranslationWasHeard() throws Exception {
        List<SpeechRelay.Delay> delays = new CopyOnWriteArrayList<>();
        relay = new SpeechRelay(SpeechRelay.Config.defaults("en"),
                new TranslationService(provider, new TranslationService.RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofSeconds(5))),
                tts, cable, transcriptSink, echoGuard, live, new SpeechRelay.Listener() {
                    @Override public void onRelaySent(Utterance u, SpeechRelay.Delay d) { delays.add(d); }
                });
        Instant stopped = Instant.now().minusMillis(800); // the pause and Whisper took 0.8 s
        relay.submit(mine("我们从左边绕过去。", "zh", stopped.minusSeconds(2), stopped)); // translation takes 0.3 s
        await("sent", () -> delays.size() == 1);
        SpeechRelay.Delay d = delays.getFirst();
        assertTrue(d.recognition().toMillis() >= 800 && d.recognition().toMillis() < 1_500, d.toString());
        assertTrue(d.translation().toMillis() >= 300, d.toString());
        assertEquals(d.recognition().plus(d.translation()).plus(d.voice()), d.total());
        assertEquals("2.1 s (recognition 0.8 + translation 0.7 + voice 0.6)",
                new SpeechRelay.Delay(Duration.ofMillis(800), Duration.ofMillis(700), Duration.ofMillis(600)).toString());
    }

    @Test
    void countsLettersInAnyScript() {
        assertEquals(0, SpeechRelay.letters("…！ "));
        assertEquals(1, SpeechRelay.letters("嗯。"));
        assertEquals(2, SpeechRelay.letters("OK!"));
    }
}
