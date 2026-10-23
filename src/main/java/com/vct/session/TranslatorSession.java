package com.vct.session;

import com.vct.audio.AudioClip;
import com.vct.audio.AudioSink;
import com.vct.audio.FrameAssembler;
import com.vct.audio.PcmSegment;
import com.vct.buffer.ConversationBuffer;
import com.vct.feedback.FeedbackGuard;
import com.vct.model.Source;
import com.vct.model.Utterance;
import com.vct.segment.UtteranceSegmenter;
import com.vct.segment.VoiceActivityDetector;
import com.vct.stt.HallucinationFilter;
import com.vct.stt.SpeechRecognizer;
import com.vct.stt.SttResult;
import com.vct.transcript.TranscriptSink;
import com.vct.translate.TranslationMode;
import com.vct.translate.TranslationRequest;
import com.vct.translate.TranslationResult;
import com.vct.translate.TranslationService;
import com.vct.tts.TtsEngine;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Orchestrates one conversation session: audio frames in, utterances buffered and transcribed,
 * on-demand translation of the latest remote utterance, and private TTS playback.
 *
 * <p>Threading: {@link #onAudio} is called from capture threads and only does cheap work (VAD and
 * segmentation). Recognition runs on one bounded worker; translation on virtual threads; synthesis
 * and playback on one dedicated thread. Nothing here blocks the caller except {@link #close()}.
 */
public final class TranslatorSession implements AutoCloseable {

    /** Everything the session depends on, so engines can be swapped and faked in tests. */
    public record Components(
            Supplier<VoiceActivityDetector> vadFactory,
            Map<Source, UtteranceSegmenter.Config> segmenterConfigs,
            SpeechRecognizer recognizer,
            HallucinationFilter hallucinationFilter,
            TtsEngine tts,
            AudioSink privateSink,
            TranslationService translator,
            TranscriptSink transcript,
            ConversationBuffer buffer,
            FeedbackGuard feedbackGuard,
            Clock clock) {}

    public record Outcome(Utterance utterance, TranslationResult result) {}

    /** Thrown (wrapped) when a trigger finds no remote utterance to translate. */
    public static final class NothingToTranslateException extends Exception {
        NothingToTranslateException() {
            super("Nothing to translate yet: no recent speech from other participants.");
        }
    }

    private record Playback(Utterance utterance, String text, String language, AudioClip clip) {}

    private final class Stream {
        final Source source;
        final FrameAssembler assembler;
        final VoiceActivityDetector vad;
        final UtteranceSegmenter segmenter;
        final AtomicInteger pending = new AtomicInteger();
        final AtomicInteger counter = new AtomicInteger();
        final int sampleRate;
        long totalSamples; // audio thread only
        /** Wall-clock time of sample 0, re-anchored on every block (see onAudio). */
        volatile Instant origin;

        Stream(Source source, UtteranceSegmenter.Config cfg) {
            this.source = source;
            this.sampleRate = cfg.sampleRate();
            this.vad = c.vadFactory().get();
            if (vad.frameSize() != cfg.frameSize()) {
                throw new IllegalArgumentException("VAD frame size " + vad.frameSize() + " != segmenter " + cfg.frameSize());
            }
            this.assembler = new FrameAssembler(cfg.frameSize());
            this.segmenter = new UtteranceSegmenter(cfg, this::onSegment);
        }

        Instant at(long sample) {
            return origin.plusNanos(sample * 1_000_000_000L / sampleRate);
        }

        void onSegment(PcmSegment seg) {
            pending.incrementAndGet();
            sttWorker.submit(new SttTask(this, seg));
        }
    }

    private final class SttTask implements Runnable {
        final Stream stream;
        final PcmSegment segment;

        SttTask(Stream stream, PcmSegment segment) {
            this.stream = stream;
            this.segment = segment;
        }

        @Override
        public void run() {
            try {
                recognize(stream, segment);
            } finally {
                stream.pending.decrementAndGet();
            }
        }
    }

    private final Components c;
    private final SessionSettings settings;
    private final SessionListener listener;
    private final Map<Source, Stream> streams = new EnumMap<>(Source.class);
    private final DropOldestExecutor sttWorker;
    private final ExecutorService ttsWorker =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("vct-tts").daemon().factory());
    private final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicReference<CompletableFuture<TranslationResult>> current = new AtomicReference<>();
    private final AtomicReference<Playback> lastPlayback = new AtomicReference<>();
    private volatile boolean paused;
    private volatile boolean closed;

    public TranslatorSession(Components components, SessionSettings settings, SessionListener listener) {
        this.c = components;
        this.settings = settings;
        this.listener = listener;
        this.sttWorker = new DropOldestExecutor("vct-stt", 6, dropped -> {
            if (dropped instanceof SttTask t) t.stream.pending.decrementAndGet();
            listener.onSegmentDropped();
        });
        for (Source s : Source.values()) {
            UtteranceSegmenter.Config cfg = components.segmenterConfigs().get(s);
            if (cfg != null) streams.put(s, new Stream(s, cfg));
        }
    }

    // ---------------------------------------------------------------- audio input

    /**
     * Feeds mono float PCM at the segmenter's sample rate (16 kHz), as soon as it is captured.
     * Call from the capture thread.
     * Audio is dropped while paused or after close.
     */
    public void onAudio(Source source, float[] samples) {
        if (paused || closed) return;
        Stream s = streams.get(source);
        if (s == null) return;
        // Re-anchor so the newest sample maps to "now". Sample counts alone drift from wall time:
        // WASAPI loopback delivers no packets while nothing plays, and device clocks are not exact.
        s.totalSamples += samples.length;
        s.origin = c.clock().instant().minusNanos(s.totalSamples * 1_000_000_000L / s.sampleRate);
        s.assembler.push(samples, frame -> s.segmenter.accept(frame, s.vad.speechProbability(frame)));
    }

    /** Pauses processing immediately; any utterance in progress is finalized. */
    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) streams.values().forEach(s -> s.segmenter.flush());
    }

    public boolean isPaused() {
        return paused;
    }

    private void recognize(Stream s, PcmSegment seg) {
        try {
            SttResult r = c.recognizer().transcribe(seg, settings.recognizedLanguages());
            if (c.hallucinationFilter().reject(r)) return;
            Instant start = s.at(seg.startSample());
            Instant end = s.at(seg.endSample());
            if (c.feedbackGuard().isEcho(r.text(), start, end)) return;
            boolean low = (!Double.isNaN(r.confidence()) && r.confidence() < 0.6)
                    || (!Double.isNaN(r.languageConfidence()) && r.languageConfidence() < 0.5);
            String id = (s.source == Source.REMOTE ? "r-" : "m-") + String.format("%04d", s.counter.incrementAndGet());
            Utterance u = new Utterance(id, s.source, start, end, r.text().strip(), r.language(),
                    r.languageConfidence(), r.confidence(), low);
            c.buffer().add(u);
            try {
                c.transcript().writeUtterance(u);
            } catch (IOException e) {
                listener.onError("Could not write transcript", e);
            }
            listener.onUtterance(u);
        } catch (Exception e) {
            listener.onError("Speech recognition failed", e);
        }
    }

    // ---------------------------------------------------------------- Mode A: translate others

    /**
     * Translates the latest remote utterance that began before {@code triggerAt}. If a remote
     * utterance is still being spoken or recognized, waits up to {@code pendingGrace} for it.
     * A previously translated utterance is not re-sent unless {@code force} (user "Retry").
     */
    public CompletableFuture<Outcome> translateLatestRemote(Instant triggerAt, boolean force) {
        return CompletableFuture.supplyAsync(() -> awaitLatestRemote(triggerAt), async)
                .thenCompose(u -> u.map(x -> translate(x, force))
                        .orElseGet(() -> CompletableFuture.failedFuture(new NothingToTranslateException())));
    }

    /** Translates a specific utterance (e.g. one the user picked from history). */
    public CompletableFuture<Outcome> translate(Utterance u, boolean force) {
        String target = settings.nativeLanguage();
        TranslationMode mode = u.language() != null && sameLanguage(u.language(), target)
                ? TranslationMode.CLARIFY : TranslationMode.TRANSLATE;
        List<String> context = settings.contextLines() <= 0 ? List.of()
                : c.buffer().contextBefore(u.id(), settings.contextLines()).stream().map(Utterance::text).toList();
        TranslationRequest req = new TranslationRequest(u.id(), u.text(), u.language(), target, mode,
                context, settings.protectedTerms());
        listener.onTranslationStarted(u);
        CompletableFuture<TranslationResult> cf = c.translator().translate(req, force);
        current.set(cf);
        return cf.thenApply(r -> {
            if (!r.fromCache()) {
                try {
                    c.transcript().writeTranslation(u.id(), target, r);
                } catch (IOException e) {
                    listener.onError("Could not write transcript", e);
                }
            }
            listener.onTranslation(u, r);
            speak(new Playback(u, r.text(), target, null));
            return new Outcome(u, r);
        }).whenComplete((o, e) -> {
            current.compareAndSet(cf, null);
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if (cause != null && !(cause instanceof CancellationException)) {
                listener.onError(cause.getMessage(), cause);
            }
        });
    }

    private Optional<Utterance> awaitLatestRemote(Instant triggerAt) {
        Stream r = streams.get(Source.REMOTE);
        if (r == null) return Optional.empty();
        long deadline = System.nanoTime() + settings.pendingGrace().toNanos();
        while (System.nanoTime() < deadline) {
            boolean speaking = r.origin != null && r.segmenter.isInSpeech()
                    && r.at(r.segmenter.currentStartSample()).isBefore(triggerAt);
            if (!speaking && r.pending.get() == 0) break;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return c.buffer().latestStartedBefore(Source.REMOTE, triggerAt);
    }

    // ---------------------------------------------------------------- playback

    private void speak(Playback p) {
        c.privateSink().stop(); // a new translation interrupts the previous one
        ttsWorker.submit(() -> {
            try {
                AudioClip clip = p.clip() != null ? p.clip()
                        : c.tts().synthesize(p.text(), p.language(), settings.ttsVoiceId(), settings.ttsSpeed());
                Playback played = new Playback(p.utterance(), p.text(), p.language(), clip);
                lastPlayback.set(played);
                Instant now = c.clock().instant();
                c.feedbackGuard().register(p.text(), now, now.plus(clip.duration()));
                listener.onPlaybackStarted(p.utterance());
                c.privateSink().play(clip);
                listener.onPlaybackFinished(p.utterance());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                listener.onError("Speech synthesis/playback failed", e);
            }
        });
    }

    /** Replays the last translation without a new API call. Returns false if there is none. */
    public boolean replayLast() {
        Playback p = lastPlayback.get();
        if (p == null || closed) return false;
        speak(p);
        return true;
    }

    /** Cancels the in-flight translation (if any) and stops playback. */
    public void cancel() {
        CompletableFuture<TranslationResult> cf = current.getAndSet(null);
        if (cf != null) cf.cancel(true);
        c.privateSink().stop();
    }

    // ---------------------------------------------------------------- lifecycle

    /** Finalizes pending speech, finishes queued recognition (bounded wait) and closes the transcript. */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        streams.values().forEach(s -> s.segmenter.flush());
        try {
            sttWorker.shutdown(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cancel();
        ttsWorker.shutdownNow();
        async.shutdownNow();
        c.transcript().close();
    }

    static boolean sameLanguage(String a, String b) {
        return Locale.forLanguageTag(a).getLanguage().equals(Locale.forLanguageTag(b).getLanguage());
    }
}
