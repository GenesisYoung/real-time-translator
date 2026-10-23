package com.vct.session;

import com.vct.audio.AudioClip;
import com.vct.audio.AudioSink;
import com.vct.audio.PcmSegment;
import com.vct.buffer.ConversationBuffer;
import com.vct.feedback.FeedbackGuard;
import com.vct.model.Source;
import com.vct.model.Utterance;
import com.vct.segment.EnergyVad;
import com.vct.segment.UtteranceSegmenter;
import com.vct.stt.HallucinationFilter;
import com.vct.stt.SpeechRecognizer;
import com.vct.stt.SttResult;
import com.vct.transcript.JsonlTranscriptWriter;
import com.vct.translate.TranslationException;
import com.vct.translate.TranslationMode;
import com.vct.translate.TranslationProvider;
import com.vct.translate.TranslationRequest;
import com.vct.translate.TranslationResult;
import com.vct.translate.TranslationService;
import com.vct.tts.TtsEngine;
import com.vct.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end pipeline with synthetic audio: tone bursts stand in for speech, a scripted recognizer
 * stands in for Whisper, and a fake provider/TTS/sink replace the network and audio devices.
 */
class TranslatorSessionTest {

    static final int RATE = 16_000;

    @TempDir
    Path dir;

    final Clock clock = Clock.systemUTC();
    final List<Utterance> heard = new CopyOnWriteArrayList<>();
    final List<String> errors = new CopyOnWriteArrayList<>();
    final LinkedBlockingQueue<AudioClip> played = new LinkedBlockingQueue<>();
    final ArrayDeque<String> sttScript = new ArrayDeque<>();
    final List<TranslationRequest> sent = new CopyOnWriteArrayList<>();
    TranslatorSession session;
    JsonlTranscriptWriter transcript;

    final SpeechRecognizer stt = new SpeechRecognizer() {
        @Override
        public synchronized SttResult transcribe(PcmSegment seg, Set<String> langs) {
            String text = sttScript.isEmpty() ? "" : sttScript.poll();
            String lang = text.startsWith("[en]") ? "en" : "de";
            return new SttResult(text.replace("[en]", ""), lang, 0.95, 0.9, 0.01);
        }
    };

    final TranslationProvider provider = new TranslationProvider() {
        @Override public String id() { return "fake"; }
        @Override public String model() { return "m"; }
        @Override public List<String> listModels() { return List.of("m"); }
        @Override
        public TranslationResult translate(TranslationRequest r) throws TranslationException {
            sent.add(r);
            String out = r.mode() == TranslationMode.CLARIFY ? "plain:" + r.text() : "EN:" + r.text();
            return new TranslationResult(out, "fake", "m", Duration.ofMillis(5), false);
        }
    };

    final TtsEngine tts = (text, lang, voice, speed) -> new AudioClip(new float[RATE / 10], RATE);

    final AudioSink sink = new AudioSink() {
        @Override public void play(AudioClip clip) { played.add(clip); }
        @Override public void stop() {}
    };

    void startSession(SessionSettings settings) throws Exception {
        transcript = JsonlTranscriptWriter.create(dir, "t", Map.of(), clock);
        var cfg = UtteranceSegmenter.Config.defaults();
        var components = new TranslatorSession.Components(
                () -> new EnergyVad(cfg.frameSize(), -40),
                Map.of(Source.REMOTE, cfg, Source.MIC, cfg),
                stt, HallucinationFilter.defaults(), tts, sink,
                new TranslationService(provider, TranslationService.RetryPolicy.defaults()),
                transcript,
                new ConversationBuffer(50, Duration.ofMinutes(5), clock),
                FeedbackGuard.defaults(clock), clock);
        session = new TranslatorSession(components, settings, new SessionListener() {
            @Override public void onUtterance(Utterance u) { heard.add(u); }
            @Override public void onError(String m, Throwable t) { errors.add(m); }
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (session != null) session.close();
    }

    /** Feeds audio in 10 ms blocks like a capture callback: tone for speechMs, then silence. */
    void speak(Source src, int speechMs, int silenceMs) {
        int block = RATE / 100;
        int total = (speechMs + silenceMs) * RATE / 1000;
        int speechSamples = speechMs * RATE / 1000;
        for (int pos = 0; pos < total; pos += block) {
            float[] b = new float[block];
            for (int i = 0; i < block; i++) {
                int n = pos + i;
                b[i] = n < speechSamples ? (float) (0.3 * Math.sin(2 * Math.PI * 220 * n / RATE)) : 0f;
            }
            session.onAudio(src, b);
        }
    }

    void awaitUtterances(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (heard.size() < n && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(n, heard.size(), "utterances recognized; errors=" + errors);
    }

    @Test
    void translatesLatestRemoteUtteranceAndPlaysIt() throws Exception {
        startSession(SessionSettings.defaults("en"));
        sttScript.add("Erste Zeile.");
        sttScript.add("Wir gehen links.");
        speak(Source.REMOTE, 600, 900);
        speak(Source.REMOTE, 800, 900);
        awaitUtterances(2);

        var outcome = session.translateLatestRemote(clock.instant(), false).get(3, TimeUnit.SECONDS);
        assertEquals("Wir gehen links.", outcome.utterance().text());
        assertEquals("EN:Wir gehen links.", outcome.result().text());
        assertNotNull(played.poll(2, TimeUnit.SECONDS), "translation is spoken");
        assertEquals(1, sent.size());

        // Second trigger on the same utterance: no new API call, but audio is replayed.
        var again = session.translateLatestRemote(clock.instant(), false).get(3, TimeUnit.SECONDS);
        assertTrue(again.result().fromCache());
        assertEquals(1, sent.size());
        assertNotNull(played.poll(2, TimeUnit.SECONDS));

        // Explicit retry does call the provider again.
        session.translateLatestRemote(clock.instant(), true).get(3, TimeUnit.SECONDS);
        assertEquals(2, sent.size());

        assertTrue(session.replayLast());
        assertNotNull(played.poll(2, TimeUnit.SECONDS));
    }

    @Test
    void waitsForUtteranceStillInProgressAtTrigger() throws Exception {
        startSession(SessionSettings.defaults("en"));
        sttScript.add("Noch nicht fertig.");
        speak(Source.REMOTE, 700, 0);              // still speaking when the user triggers
        Instant trigger = clock.instant();
        var future = session.translateLatestRemote(trigger, false);
        speak(Source.REMOTE, 0, 900);              // pause arrives after the trigger
        var outcome = future.get(3, TimeUnit.SECONDS);
        assertEquals("Noch nicht fertig.", outcome.utterance().text());
    }

    @Test
    void micSpeechIsTranscribedButNeverChosenForTranslateOthers() throws Exception {
        startSession(SessionSettings.defaults("en"));
        sttScript.add("[en]this is me talking");
        speak(Source.MIC, 600, 900);
        awaitUtterances(1);
        assertEquals(Source.MIC, heard.getFirst().source());
        var e = assertThrows(ExecutionException.class,
                () -> session.translateLatestRemote(clock.instant(), false).get(3, TimeUnit.SECONDS));
        assertInstanceOf(TranslatorSession.NothingToTranslateException.class, e.getCause());
        assertTrue(sent.isEmpty(), "no API request without a remote utterance");
    }

    @Test
    void sameLanguageUsesClarifyMode() throws Exception {
        startSession(SessionSettings.defaults("en"));
        sttScript.add("[en]That's a piece of cake.");
        speak(Source.REMOTE, 600, 900);
        awaitUtterances(1);
        var outcome = session.translateLatestRemote(clock.instant(), false).get(3, TimeUnit.SECONDS);
        assertEquals(TranslationMode.CLARIFY, sent.getFirst().mode());
        assertTrue(outcome.result().text().startsWith("plain:"));
    }

    @Test
    void ownTranslationLeakingIntoLoopbackIsIgnored() throws Exception {
        startSession(SessionSettings.defaults("en"));
        sttScript.add("Wir gehen links.");
        speak(Source.REMOTE, 600, 900);
        awaitUtterances(1);
        session.translateLatestRemote(clock.instant(), false).get(3, TimeUnit.SECONDS);
        assertNotNull(played.poll(2, TimeUnit.SECONDS));

        // The recognizer "hears" our own TTS output come back through loopback.
        sttScript.add("EN:Wir gehen links.");
        speak(Source.REMOTE, 600, 900);
        Thread.sleep(300);
        assertEquals(1, heard.size(), "echo of our own speech must not become an utterance");
    }

    @Test
    void hallucinationsAndPauseAreFiltered() throws Exception {
        startSession(SessionSettings.defaults("en"));
        sttScript.add("");                     // empty result
        speak(Source.REMOTE, 600, 900);
        session.setPaused(true);
        sttScript.add("should never be recognized");
        speak(Source.REMOTE, 600, 900);
        Thread.sleep(300);
        assertTrue(heard.isEmpty());
    }

    @Test
    void transcriptContainsUtterancesAndTranslationsAfterClose() throws Exception {
        startSession(SessionSettings.defaults("en"));
        sttScript.add("Hallo zusammen.");
        speak(Source.REMOTE, 600, 900);
        awaitUtterances(1);
        session.translateLatestRemote(clock.instant(), false).get(3, TimeUnit.SECONDS);
        session.close();
        session = null;

        List<Map<String, Object>> lines = Files.readAllLines(transcript.file(), StandardCharsets.UTF_8)
                .stream().map(Json::parseObject).toList();
        assertEquals(3, lines.size());
        Map<String, Object> utt = lines.get(1);
        assertEquals("Hallo zusammen.", utt.get("text"));
        assertEquals("remote", utt.get("src"));
        assertEquals("de", utt.get("lang"));
        assertEquals("EN:Hallo zusammen.",
                ((Map<?, ?>) ((List<?>) utt.get("translations")).getFirst()).get("text"));
    }
}
