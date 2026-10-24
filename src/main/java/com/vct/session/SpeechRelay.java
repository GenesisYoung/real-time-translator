package com.vct.session;

import com.vct.audio.AudioSink;
import com.vct.feedback.FeedbackGuard;
import com.vct.model.Source;
import com.vct.model.Utterance;
import com.vct.transcript.TranscriptSink;
import com.vct.translate.TranslationMode;
import com.vct.translate.TranslationRequest;
import com.vct.translate.TranslationResult;
import com.vct.translate.TranslationService;
import com.vct.tts.SpeechStream;
import com.vct.tts.TtsEngine;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "Translate my speech": every line the user says is translated into the session's target language
 * and spoken into the virtual microphone (the voice chat's input), automatically.
 *
 * <p>Translation and synthesis start at once for each line, in parallel; playback keeps the order in
 * which the lines were said. Unclear and very short lines are not sent, and the relay can be paused
 * for side comments.
 *
 * <p>While the user holds the "talk live" key, their real voice goes into the voice chat instead
 * ({@link LiveVoice}): the translation playing is cut, later ones wait, and the lines said live are
 * not translated.
 */
public final class SpeechRelay implements AutoCloseable {

    /** Events for the UI, on worker threads. */
    public interface Listener {
        /** The line will be spoken; {@code r} is the translation (or the original, if already in the target language). */
        default void onRelayTranslation(Utterance u, TranslationResult r) {}

        /** The line was spoken into the voice chat; {@code delay} is how long after the user stopped it began. */
        default void onRelaySent(Utterance u, Delay delay) {}

        /** The line was not sent; {@code reason} is user-readable. */
        default void onRelaySkipped(Utterance u, String reason) {}

        default void onRelayError(Utterance u, String message) {}

        /** The user's real voice started or stopped going into the voice chat. */
        default void onRelayLive(boolean live) {}
    }

    /**
     * When the translation of a line began to play, counted from the end of the user's speech.
     *
     * @param recognition waiting for the pause that ends the line, plus Whisper
     * @param translation the translation request
     * @param voice       until the voice began: the speech request, and any earlier line still playing
     */
    public record Delay(Duration recognition, Duration translation, Duration voice) {
        public Duration total() {
            return recognition.plus(translation).plus(voice);
        }

        /** e.g. "2.1 s (recognition 0.8 + translation 0.7 + voice 0.6)" */
        @Override
        public String toString() {
            return seconds(total()) + " s (recognition " + seconds(recognition) + " + translation " + seconds(translation)
                    + " + voice " + seconds(voice) + ")";
        }

        private static String seconds(Duration d) {
            return String.format(java.util.Locale.ROOT, "%.1f", Math.max(0, d.toMillis()) / 1000.0);
        }
    }

    /**
     * @param targetLanguage what the others hear, e.g. "en" or "ja"
     * @param minLetters     lines with fewer letters are not sent (coughs, "嗯")
     * @param maxPending     lines waiting to be spoken before new ones are dropped
     * @param contextLines   previous lines of the user sent as context for better translations
     */
    public record Config(String targetLanguage, String voiceId, float speed, int minLetters, int maxPending,
                         int contextLines) {
        public static Config defaults(String targetLanguage) {
            return new Config(targetLanguage, null, 1.0f, 2, 4, 2);
        }
    }

    private final Config config;
    private final TranslationService translator;
    private final TtsEngine tts;
    private final AudioSink sink;
    private final TranscriptSink transcript;
    private final Listener listener;
    private final FeedbackGuard echoGuard;
    private final LiveVoice live;
    private final ExecutorService playback = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("vct-relay-playback").daemon().factory());
    private final ExecutorService work = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    private final Deque<String> recent = new ArrayDeque<>();
    private volatile boolean paused;
    private volatile boolean closed;

    /**
     * @param echoGuard told what is being spoken, so that if the translation ever comes back in
     *                  through a microphone, recognition drops it instead of translating it again
     * @param live      the "talk live" stream into the same cable, or null without one
     */
    public SpeechRelay(Config config, TranslationService translator, TtsEngine tts, AudioSink sink,
                       TranscriptSink transcript, FeedbackGuard echoGuard, LiveVoice live, Listener listener) {
        this.echoGuard = echoGuard;
        this.live = live;
        this.config = config;
        this.translator = translator;
        this.tts = tts;
        this.sink = sink;
        this.transcript = transcript;
        this.listener = listener;
    }

    public String targetLanguage() {
        return config.targetLanguage();
    }

    /** Pausing also stops the line being spoken. */
    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) sink.stop();
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * Starts or stops sending the user's real voice (the "talk live" key). Starting cuts the
     * translation being spoken; translations ready meanwhile wait until the user is done.
     *
     * @throws IllegalStateException if the cable cannot be opened
     */
    public void setLive(boolean on) {
        if (live == null || closed) return;
        if (on) {
            sink.stop();
            if (live.press()) listener.onRelayLive(true);
        } else {
            live.release(); // ends after a short tail; onMicAudio reports it
        }
    }

    public boolean isLive() {
        return live != null && live.isLive();
    }

    /** The microphone as it is captured, for "talk live". Call from the capture thread. */
    public void onMicAudio(float[] samples) {
        if (live != null && live.onAudio(samples)) listener.onRelayLive(false);
    }

    /** Offers a recognized line; only the user's own lines (MIC) are relayed. Never blocks. */
    public void submit(Utterance u) {
        if (closed || u.source() != Source.MIC) return;
        String skip = live != null && live.wasLive(u.start(), u.end()) ? "spoken live, not translated"
                : paused ? "paused"
                : u.lowConfidence() ? "unclear, not sent"
                : letters(u.text()) < config.minLetters() ? "too short, not sent"
                : null;
        if (skip == null && pending.get() >= config.maxPending()) skip = "too many lines waiting, not sent";
        if (skip != null) {
            listener.onRelaySkipped(u, skip);
            return;
        }
        pending.incrementAndGet();
        Instant submitted = Instant.now();
        List<String> context = context(u.text());
        CompletableFuture<Prepared> prepared = CompletableFuture.supplyAsync(() -> prepare(u, context, submitted), work);
        playback.execute(() -> {
            try {
                speak(u, prepared.join());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                listener.onRelayError(u, "Could not send \"" + u.text() + "\": " + cause.getMessage());
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    private record Prepared(TranslationResult translation, SpeechStream speech, Instant submitted, Instant translated) {}

    /**
     * Translates (unless already in the target language) and starts the voice. Runs in parallel;
     * the voice keeps arriving while this line waits for its turn.
     */
    private Prepared prepare(Utterance u, List<String> context, Instant submitted) {
        try {
            TranslationResult r;
            if (u.language() != null && TranslatorSession.sameLanguage(u.language(), config.targetLanguage())) {
                r = new TranslationResult(u.text(), "none", "spoken as said", Duration.ZERO, false);
            } else {
                TranslationRequest req = new TranslationRequest(u.id(), u.text(), u.language(), config.targetLanguage(),
                        TranslationMode.TRANSLATE, context, List.of());
                r = translator.translate(req, false).join();
                try {
                    transcript.writeTranslation(u.id(), config.targetLanguage(), r);
                } catch (IOException e) {
                    listener.onRelayError(u, "Could not write transcript: " + e.getMessage());
                }
            }
            Instant translated = Instant.now();
            listener.onRelayTranslation(u, r);
            return new Prepared(r, tts.stream(r.text(), config.targetLanguage(), config.voiceId(), config.speed()),
                    submitted, translated);
        } catch (CompletionException e) {
            throw e;
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    private void speak(Utterance u, Prepared p) throws InterruptedException {
        try (SpeechStream speech = p.speech()) {
            while (isLive() && !closed) Thread.sleep(20); // never over the user's own voice
            if (paused || closed) {
                listener.onRelaySkipped(u, "paused");
                return;
            }
            Instant start = Instant.now();
            String text = p.translation().text();
            // The length is known only at the end: assume a slow voice until then.
            echoGuard.register(text, start, start.plusSeconds(1).plusMillis(150L * text.length()));
            Delay delay = new Delay(Duration.between(u.end(), p.submitted()), Duration.between(p.submitted(), p.translated()),
                    Duration.between(p.translated(), start));
            sink.playStream(speech.pcm16(), speech.sampleRate());
            echoGuard.register(text, start, Instant.now());
            listener.onRelaySent(u, delay);
        } catch (IOException | RuntimeException e) {
            listener.onRelayError(u, "Could not play into the voice chat: " + e.getMessage());
        }
    }

    /** The user's previous lines (oldest first), then remembers this one. */
    private synchronized List<String> context(String text) {
        List<String> out = recent.stream().skip(Math.max(0, recent.size() - config.contextLines())).toList();
        recent.addLast(text);
        while (recent.size() > Math.max(config.contextLines(), 1)) recent.removeFirst();
        return config.contextLines() <= 0 ? List.of() : out;
    }

    static int letters(String text) {
        return (int) text.codePoints().filter(Character::isLetterOrDigit).count();
    }

    /** Stops speaking and drops lines that are still waiting. */
    @Override
    public void close() {
        closed = true;
        sink.stop();
        if (live != null) live.close();
        playback.shutdownNow();
        work.shutdownNow();
    }
}
