package com.vct.app;

import com.vct.audio.AudioSink;
import com.vct.buffer.ConversationBuffer;
import com.vct.capture.CaptureBackend;
import com.vct.capture.CaptureLoop;
import com.vct.feedback.FeedbackGuard;
import com.vct.model.Source;
import com.vct.model.Utterance;
import com.vct.segment.UtteranceSegmenter;
import com.vct.segment.VoiceActivityDetector;
import com.vct.session.SessionListener;
import com.vct.session.SessionSettings;
import com.vct.session.TranslatorSession;
import com.vct.stt.HallucinationFilter;
import com.vct.stt.SpeechRecognizer;
import com.vct.transcript.JsonlTranscriptWriter;
import com.vct.translate.TranslationProvider;
import com.vct.translate.TranslationResult;
import com.vct.translate.TranslationService;
import com.vct.tts.TtsEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Runs translation sessions for the UI: loads the speech engines (once, reused across sessions),
 * starts and stops the session with its two capture loops, and forwards hotkey and button actions.
 *
 * <p>Start, stop and close run one at a time on the controller's own thread; the returned futures
 * complete when they are done. Events reach the {@link Listener} on worker threads.
 */
public final class SessionController implements AutoCloseable {

    public enum Status { STOPPED, LOADING, RUNNING, STOPPING }

    public interface Listener extends SessionListener {
        default void onStatus(Status status, String detail) {}

        default void onCaptureState(Source source, CaptureLoop.State state, String detail) {}

        /** Information for the user that is not an error, e.g. "nothing to translate yet". */
        default void onNotice(String message) {}
    }

    /** Creates the engines. {@link DefaultEngines} in the app, fakes in tests. */
    public interface Engines extends AutoCloseable {
        CaptureBackend captureBackend() throws Exception;

        Supplier<VoiceActivityDetector> vadFactory() throws Exception;

        SpeechRecognizer recognizer(AppSettings s) throws Exception;

        TtsEngine tts(AppSettings s) throws Exception;

        AudioSink sink(AppSettings s);

        TranslationProvider translator(AppSettings s, Supplier<String> apiKey);

        CaptureLoop.TargetResolver mic(AppSettings s);

        CaptureLoop.TargetResolver remote(AppSettings s);

        Path transcriptDir();

        Clock clock();

        @Override
        default void close() {}
    }

    private final Engines engines;
    private final Supplier<String> apiKey;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("vct-controller").daemon().factory());

    // Owned by the worker thread.
    private CaptureBackend backend;
    private Supplier<VoiceActivityDetector> vad;
    private SpeechRecognizer recognizer;
    private Object recognizerKey;
    private TtsEngine tts;
    private Object ttsKey;
    private TranslationService translation;
    private CaptureLoop micLoop;
    private CaptureLoop remoteLoop;

    private volatile TranslatorSession session;
    private volatile Status status = Status.STOPPED;
    private volatile Path transcriptFile;

    /** @param apiKey read on every translation, so a key entered in Settings works at once */
    public SessionController(Engines engines, Supplier<String> apiKey, Listener listener) {
        this.engines = engines;
        this.apiKey = apiKey;
        this.listener = listener;
    }

    public Status status() {
        return status;
    }

    /** The transcript of the running (or last) session, or null. */
    public Path transcriptFile() {
        return transcriptFile;
    }

    /** Starts a session with these settings; restarts it if one is running. */
    public CompletableFuture<Void> start(AppSettings settings) {
        return CompletableFuture.runAsync(() -> {
            stopNow();
            setStatus(Status.LOADING, "Loading speech engines...");
            try {
                startNow(settings);
                setStatus(Status.RUNNING, "Listening (" + settings.remoteSource().label() + ")");
            } catch (Exception e) {
                stopNow();
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                setStatus(Status.STOPPED, "Could not start: " + message);
                listener.onError(message, e);
                throw new CompletionException(e);
            }
        }, worker);
    }

    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(this::stopNow, worker);
    }

    private void startNow(AppSettings s) throws Exception {
        if (backend == null) backend = engines.captureBackend();
        if (vad == null) vad = engines.vadFactory();
        Object rKey = List.of(s.whisperModel(), s.whisperGpu());
        if (!rKey.equals(recognizerKey)) {
            if (recognizer != null) recognizer.close();
            recognizer = null;
            recognizer = engines.recognizer(s);
            recognizerKey = rKey;
        }
        if (!Objects.equals(s.ttsVoice(), ttsKey)) {
            if (tts != null) tts.close();
            tts = null;
            tts = engines.tts(s);
            ttsKey = s.ttsVoice();
        }
        TranslationProvider provider = engines.translator(s, apiKey);
        if (translation == null) {
            translation = new TranslationService(provider, TranslationService.RetryPolicy.defaults());
        } else {
            translation.setProvider(provider); // keeps the cache of earlier translations
        }

        Clock clock = engines.clock();
        Path dir = engines.transcriptDir();
        Files.createDirectories(dir);
        JsonlTranscriptWriter.recover(dir); // compacts files left by a crash
        // The file name has one-second resolution: a unique id keeps a quick restart from colliding.
        String id = java.util.UUID.randomUUID().toString().substring(0, 8);
        JsonlTranscriptWriter transcript = JsonlTranscriptWriter.create(dir, id,
                Map.of("app", "voice-chat-translator", "remote", s.remoteSource().name()), clock);
        transcriptFile = transcript.file();

        UtteranceSegmenter.Config seg = UtteranceSegmenter.Config.defaults();
        TranslatorSession.Components c = new TranslatorSession.Components(vad,
                Map.of(Source.REMOTE, seg, Source.MIC, seg), recognizer, HallucinationFilter.defaults(), tts,
                engines.sink(s), translation, transcript, new ConversationBuffer(50, Duration.ofMinutes(15), clock),
                FeedbackGuard.defaults(clock), clock);
        SessionSettings settings = new SessionSettings(s.nativeLanguage(), new LinkedHashSet<>(s.recognizedLanguages()),
                List.of(), s.contextLines(), Duration.ofMillis(1500), null, (float) s.ttsSpeed());
        TranslatorSession started = new TranslatorSession(c, settings, listener);
        session = started;

        micLoop = new CaptureLoop("mic", backend, engines.mic(s), x -> started.onAudio(Source.MIC, x),
                (state, detail) -> listener.onCaptureState(Source.MIC, state, detail), CaptureLoop.Config.defaults()).start();
        remoteLoop = new CaptureLoop("remote", backend, engines.remote(s), x -> started.onAudio(Source.REMOTE, x),
                (state, detail) -> listener.onCaptureState(Source.REMOTE, state, detail), CaptureLoop.Config.defaults()).start();
    }

    private void stopNow() {
        TranslatorSession s = session;
        if (s == null && micLoop == null && remoteLoop == null) return;
        setStatus(Status.STOPPING, "Stopping...");
        if (micLoop != null) micLoop.close();
        if (remoteLoop != null) remoteLoop.close();
        micLoop = null;
        remoteLoop = null;
        session = null;
        if (s != null) {
            try {
                s.close(); // finishes pending recognition, closes the transcript
            } catch (Exception e) {
                listener.onError("Could not close the session cleanly: " + e.getMessage(), e);
            }
        }
        setStatus(Status.STOPPED, "Stopped");
    }

    /** Translates the latest line from the other participants (the hotkey's action). */
    public void translateLatest() {
        TranslatorSession s = runningSession();
        if (s == null || !hasApiKey()) return;
        s.translateLatestRemote(Instant.now(), false).whenComplete((out, e) -> {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TranslatorSession.NothingToTranslateException) listener.onNotice(cause.getMessage());
        });
    }

    /** Translates a line picked from the history; {@code force} asks the provider again (Retry). */
    public CompletableFuture<TranslatorSession.Outcome> translate(Utterance u, boolean force) {
        TranslatorSession s = runningSession();
        if (s == null || !hasApiKey()) return CompletableFuture.failedFuture(new IllegalStateException("not ready"));
        return s.translate(u, force);
    }

    public void replay() {
        TranslatorSession s = runningSession();
        if (s != null && !s.replayLast()) listener.onNotice("Nothing to replay yet.");
    }

    public void cancel() {
        TranslatorSession s = session;
        if (s != null) s.cancel();
    }

    /** Lists the models the API key can use (blocking: call off the UI thread). */
    public List<String> listModels(AppSettings s) throws Exception {
        return engines.translator(s, apiKey).listModels();
    }

    private TranslatorSession runningSession() {
        TranslatorSession s = session;
        if (s == null || status != Status.RUNNING) {
            listener.onNotice("Start a session first.");
            return null;
        }
        return s;
    }

    private boolean hasApiKey() {
        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            listener.onNotice("Add your OpenAI API key in Settings to translate.");
            return false;
        }
        return true;
    }

    private void setStatus(Status s, String detail) {
        status = s;
        listener.onStatus(s, detail);
    }

    /** Stops the session and frees the engines (GPU memory, native libraries). */
    @Override
    public void close() {
        CompletableFuture<Void> done = CompletableFuture.runAsync(() -> {
            stopNow();
            if (recognizer != null) recognizer.close();
            if (tts != null) tts.close();
            if (translation != null) translation.close();
            engines.close();
        }, worker);
        try {
            done.join();
        } finally {
            worker.shutdown();
        }
    }
}
