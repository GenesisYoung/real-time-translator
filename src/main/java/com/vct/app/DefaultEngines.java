package com.vct.app;

import com.vct.audio.AudioSink;
import com.vct.audio.JavaSoundSink;
import com.vct.capture.CaptureBackend;
import com.vct.capture.CaptureLoop;
import com.vct.capture.CaptureTarget;
import com.vct.capture.WasapiBackend;
import com.vct.segment.SileroVad;
import com.vct.segment.VoiceActivityDetector;
import com.vct.stt.SpeechRecognizer;
import com.vct.stt.WhisperRecognizer;
import com.vct.translate.OpenAiProvider;
import com.vct.translate.TranslationProvider;
import com.vct.tts.SherpaTts;
import com.vct.tts.TtsEngine;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/** The real engines: WASAPI, Silero, Whisper, sherpa-onnx, Java Sound and OpenAI. */
public final class DefaultEngines implements SessionController.Engines {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private WasapiBackend backend;
    private SileroVad.Model vadModel;

    @Override
    public synchronized CaptureBackend captureBackend() throws Exception {
        if (backend == null) backend = WasapiBackend.load();
        return backend;
    }

    @Override
    public synchronized Supplier<VoiceActivityDetector> vadFactory() {
        if (vadModel == null) vadModel = SileroVad.Model.load();
        SileroVad.Model m = vadModel;
        return () -> new SileroVad(m);
    }

    @Override
    public SpeechRecognizer recognizer(AppSettings s) throws Exception {
        return WhisperRecognizer.open(Path.of(s.whisperModel()), s.whisperGpu());
    }

    @Override
    public TtsEngine tts(AppSettings s) throws Exception {
        return SherpaTts.load(Path.of(s.ttsVoice()));
    }

    @Override
    public AudioSink sink(AppSettings s) {
        return s.outputDevice() == null ? new JavaSoundSink() : new JavaSoundSink(s.outputDevice());
    }

    @Override
    public TranslationProvider translator(AppSettings s, Supplier<String> apiKey) {
        return new OpenAiProvider(http, OpenAiProvider.DEFAULT_BASE_URL, apiKey, s.openAiModel(), Duration.ofSeconds(10), 400);
    }

    @Override
    public CaptureLoop.TargetResolver mic(AppSettings s) {
        return CaptureLoop.TargetResolver.of(new CaptureTarget.Device(s.micDeviceId()));
    }

    @Override
    public CaptureLoop.TargetResolver remote(AppSettings s) {
        return s.remoteSource().resolver();
    }

    @Override
    public Path transcriptDir() {
        return Path.of("transcripts");
    }

    @Override
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Override
    public synchronized void close() {
        if (vadModel != null) {
            try {
                vadModel.close();
            } catch (Exception ignored) {
                // closing at exit; nothing useful to do
            }
        }
        if (backend != null) backend.shutdown();
    }
}
