package com.vct.session;

import com.vct.audio.AudioClip;
import com.vct.audio.AudioSink;
import com.vct.model.Source;
import com.vct.model.Utterance;
import com.vct.transcript.TranscriptSink;
import com.vct.translate.TranslationMode;
import com.vct.translate.TranslationRequest;
import com.vct.translate.TranslationResult;
import com.vct.translate.TranslationService;
import com.vct.tts.TtsEngine;

import java.io.IOException;
import java.time.Duration;
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
 */
public final class SpeechRelay implements AutoCloseable {

    /** Events for the UI, on worker threads. */
    public interface Listener {
        /** The line will be spoken; {@code r} is the translation (or the original, if already in the target language). */
        default void onRelayTranslation(Utterance u, TranslationResult r) {}

        /** The line was spoken into the voice chat. */
        default void onRelaySent(Utterance u) {}

        /** The line was not sent; {@code reason} is user-readable. */
        default void onRelaySkipped(Utterance u, String reason) {}

        default void onRelayError(Utterance u, String message) {}
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
    private final ExecutorService playback = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("vct-relay-playback").daemon().factory());
    private final ExecutorService work = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger pending = new AtomicInteger();
    private final Deque<String> recent = new ArrayDeque<>();
    private volatile boolean paused;
    private volatile boolean closed;

    public SpeechRelay(Config config, TranslationService translator, TtsEngine tts, AudioSink sink,
                       TranscriptSink transcript, Listener listener) {
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

    /** Offers a recognized line; only the user's own lines (MIC) are relayed. Never blocks. */
    public void submit(Utterance u) {
        if (closed || u.source() != Source.MIC) return;
        String skip = paused ? "paused"
                : u.lowConfidence() ? "unclear, not sent"
                : letters(u.text()) < config.minLetters() ? "too short, not sent"
                : null;
        if (skip == null && pending.get() >= config.maxPending()) skip = "too many lines waiting, not sent";
        if (skip != null) {
            listener.onRelaySkipped(u, skip);
            return;
        }
        pending.incrementAndGet();
        List<String> context = context(u.text());
        CompletableFuture<Prepared> prepared = CompletableFuture.supplyAsync(() -> prepare(u, context), work);
        playback.execute(() -> {
            try {
                speak(u, prepared.join());
            } catch (CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                listener.onRelayError(u, "Could not send \"" + u.text() + "\": " + cause.getMessage());
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    private record Prepared(TranslationResult translation, AudioClip clip) {}

    /** Translates (unless already in the target language) and synthesizes. Runs in parallel. */
    private Prepared prepare(Utterance u, List<String> context) {
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
            listener.onRelayTranslation(u, r);
            return new Prepared(r, tts.synthesize(r.text(), config.targetLanguage(), config.voiceId(), config.speed()));
        } catch (CompletionException e) {
            throw e;
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    private void speak(Utterance u, Prepared p) {
        if (paused || closed) {
            listener.onRelaySkipped(u, "paused");
            return;
        }
        try {
            sink.play(p.clip());
            listener.onRelaySent(u);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
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
        playback.shutdownNow();
        work.shutdownNow();
    }
}
