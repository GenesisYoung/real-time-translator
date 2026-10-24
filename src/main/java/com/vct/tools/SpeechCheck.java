package com.vct.tools;

import com.vct.audio.AudioClip;
import com.vct.audio.JavaSoundSink;
import com.vct.buffer.ConversationBuffer;
import com.vct.capture.CaptureLoop;
import com.vct.capture.CaptureTarget;
import com.vct.capture.DiscordLocator;
import com.vct.capture.WasapiBackend;
import com.vct.feedback.FeedbackGuard;
import com.vct.model.Source;
import com.vct.model.Utterance;
import com.vct.segment.SileroVad;
import com.vct.segment.UtteranceSegmenter;
import com.vct.session.SessionListener;
import com.vct.session.SessionSettings;
import com.vct.session.TranslatorSession;
import com.vct.stt.HallucinationFilter;
import com.vct.stt.SttResult;
import com.vct.stt.WhisperRecognizer;
import com.vct.transcript.JsonlTranscriptWriter;
import com.vct.translate.OpenAiProvider;
import com.vct.translate.TranslationResult;
import com.vct.translate.TranslationService;
import com.vct.tts.SherpaTts;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * Checks the speech engines, one at a time or all together.
 *
 * <pre>
 * gradlew speechCheck --args="transcribe build\capture-check\remote.wav"
 *     Whisper on a WAV file (16-bit PCM). Prints text, language, confidence and timings.
 * gradlew speechCheck --args="say 我们应该往左走。"
 *     Speaks a sentence with the TTS voice on the default output.
 * gradlew --console=plain speechCheck --args="live --openai-model gpt-4.1-mini --remote others"
 *     The whole pipeline: capture, VAD, Whisper, and on Enter: translate the latest remote line
 *     to Chinese with OpenAI (key from OPENAI_API_KEY) and speak it.
 *
 * Options (defaults in brackets):
 *   --whisper FILE      Whisper model [models/whisper/ggml-large-v3-turbo-q8_0.bin]
 *   --cpu               run Whisper on the CPU instead of the GPU
 *   --languages LIST    languages to recognize [en,ja,zh]
 *   --voice DIR         TTS voice folder [models/tts/vits-piper-zh_CN-huayan-medium]
 *   --speaker N         speaker number for voices with several speakers [0]
 *   --speed X           speech speed [1.0]
 *   --output NAME       play on the output whose name contains NAME [Windows default]
 *   --wav FILE          (say) also write the speech to FILE
 *   --remote discord|others|system   (live) what to translate [others]
 *   --openai-model NAME (live) OpenAI model for translation; omit it to list your models
 * </pre>
 */
public final class SpeechCheck {

    private SpeechCheck() {}

    record Options(String mode, String argument, Map<String, String> values, Set<String> flags) {
        String get(String key, String fallback) {
            return values.getOrDefault(key, fallback);
        }

        Set<String> languages() {
            Set<String> out = new LinkedHashSet<>();
            for (String s : get("languages", "en,ja,zh").split(",")) if (!s.isBlank()) out.add(s.strip());
            return out;
        }
    }

    public static void main(String[] args) throws Exception {
        Options o;
        try {
            o = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage() + "\nSee the class comment of com.vct.tools.SpeechCheck for usage.");
            System.exit(2);
            return;
        }
        if (System.getProperty("os.name").startsWith("Windows")) {
            System.out.println("(If Chinese or Japanese text shows as garbage, quit, run \"chcp 65001\" and start again.)");
        }
        int code;
        try {
            code = switch (o.mode()) {
                case "transcribe" -> transcribe(o);
                case "say" -> say(o);
                case "live" -> live(o);
                default -> throw new IllegalStateException();
            };
        } catch (com.vct.stt.SttException | com.vct.tts.TtsException | com.vct.capture.CaptureException
                 | java.io.IOException e) {
            // Setup problems (missing files, wrong paths): the message says what to do.
            System.err.println(e.getMessage());
            code = 1;
        }
        System.exit(code);
    }

    static Options parse(String[] args) {
        if (args.length == 0) throw new IllegalArgumentException("Choose a mode: transcribe, say or live.");
        String mode = args[0];
        if (!List.of("transcribe", "say", "live").contains(mode)) {
            throw new IllegalArgumentException("Unknown mode \"" + mode + "\". Use transcribe, say or live.");
        }
        Map<String, String> values = new HashMap<>();
        Set<String> flags = new LinkedHashSet<>();
        List<String> positional = new ArrayList<>();
        Set<String> withValue = Set.of("whisper", "languages", "voice", "speaker", "speed", "output", "wav", "remote",
                "openai-model");
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                if (withValue.contains(key)) {
                    if (i + 1 >= args.length) throw new IllegalArgumentException(a + " needs a value.");
                    values.put(key, args[++i]);
                } else if (key.equals("cpu")) {
                    flags.add(key);
                } else {
                    throw new IllegalArgumentException("Unknown option " + a + ".");
                }
            } else {
                positional.add(a);
            }
        }
        String argument = String.join(" ", positional);
        if (mode.equals("live") && !argument.isEmpty()) {
            // e.g. a placeholder typed literally: --openai-model <name from the list>
            throw new IllegalArgumentException("Unexpected text \"" + argument + "\". Give option values without"
                    + " < >, for example: --openai-model gpt-4.1-mini");
        }
        if (!mode.equals("live") && argument.isBlank()) {
            throw new IllegalArgumentException(mode.equals("say") ? "Give the text to speak." : "Give the WAV file to transcribe.");
        }
        String remote = values.getOrDefault("remote", "others");
        if (!List.of("discord", "others", "system").contains(remote)) {
            throw new IllegalArgumentException("--remote must be discord, others or system, not \"" + remote + "\".");
        }
        return new Options(mode, argument, values, flags);
    }

    private static WhisperRecognizer openWhisper(Options o) throws Exception {
        Path model = Path.of(o.get("whisper", "models/whisper/ggml-large-v3-turbo-q8_0.bin"));
        boolean gpu = !o.flags().contains("cpu");
        System.out.println("Loading Whisper " + model.getFileName() + (gpu ? " on the GPU" : " on the CPU") + " ...");
        long t0 = System.nanoTime();
        WhisperRecognizer w = WhisperRecognizer.open(model, gpu);
        System.out.printf(Locale.ROOT, "Loaded in %.1f s. %s%n", (System.nanoTime() - t0) / 1e9, w.systemInfo());
        if (gpu && !w.systemInfo().contains("CUDA")) {
            System.out.println("WARNING: this build has no CUDA support, so Whisper runs on the CPU (slow).");
        }
        return w;
    }

    private static SherpaTts openTts(Options o) throws Exception {
        Path voice = Path.of(o.get("voice", "models/tts/vits-piper-zh_CN-huayan-medium"));
        SherpaTts tts = SherpaTts.load(voice);
        System.out.printf("TTS: sherpa-onnx %s, voice %s (%s, %d speaker(s), %d Hz)%n", tts.version(),
                voice.getFileName(), tts.voice().kind(), tts.numSpeakers(), tts.sampleRate());
        return tts;
    }

    private static JavaSoundSink openSink(Options o) {
        String name = o.values().get("output");
        return name == null ? new JavaSoundSink() : new JavaSoundSink(name);
    }

    // ------------------------------------------------------------------ transcribe

    private static int transcribe(Options o) throws Exception {
        float[] pcm = WavReader.read16kMono(Path.of(o.argument()));
        try (WhisperRecognizer w = openWhisper(o)) {
            for (int run = 1; run <= 2; run++) { // the first run includes GPU warm-up
                WhisperRecognizer.Transcription t = w.transcribe(pcm, o.languages());
                SttResult r = t.result();
                System.out.printf(Locale.ROOT, "%nRun %d: %.1f s of audio, detect %d ms, decode %d ms%n", run,
                        pcm.length / 16_000.0, t.detectMillis(), t.decodeMillis());
                System.out.printf(Locale.ROOT, "  language %s (%.2f), confidence %.2f, no-speech %.2f%n",
                        r.language(), r.languageConfidence(), r.confidence(), r.noSpeechProbability());
                System.out.println("  text: " + r.text());
                if (HallucinationFilter.defaults().reject(r)) System.out.println("  (the app would drop this as not real speech)");
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ say

    private static int say(Options o) throws Exception {
        try (SherpaTts tts = openTts(o)) {
            long t0 = System.nanoTime();
            AudioClip clip = tts.synthesize(o.argument(), "zh", o.values().get("speaker"),
                    Float.parseFloat(o.get("speed", "1.0")));
            System.out.printf(Locale.ROOT, "Synthesized %.1f s of speech in %d ms.%n", clip.samples().length
                    / (double) clip.sampleRate(), (System.nanoTime() - t0) / 1_000_000);
            String wav = o.values().get("wav");
            if (wav != null) {
                try (WavWriter out = new WavWriter(Path.of(wav), clip.sampleRate())) {
                    out.write(clip.samples());
                }
                System.out.println("Wrote " + Path.of(wav).toAbsolutePath());
            }
            if (JavaSoundSink.outputDevices().isEmpty()) {
                System.out.println("No audio output found; not playing.");
            } else {
                openSink(o).play(clip);
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ live

    private static int live(Options o) throws Exception {
        String openAiModel = o.values().get("openai-model");
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            System.out.println("OPENAI_API_KEY is not set: speech is recognized but not translated.");
        } else if (openAiModel == null) {
            listOpenAiModels(key);
            return 2;
        }

        WasapiBackend backend = WasapiBackend.load();
        SileroVad.Model vad = SileroVad.Model.load();
        WhisperRecognizer whisper = openWhisper(o);
        SherpaTts tts = openTts(o);
        Clock clock = Clock.systemUTC();
        Path transcripts = Path.of("transcripts");
        Files.createDirectories(transcripts);
        JsonlTranscriptWriter.recover(transcripts);
        JsonlTranscriptWriter transcript = JsonlTranscriptWriter.create(transcripts, "check",
                Map.of("app", "speechCheck"), clock);

        OpenAiProvider openAi = new OpenAiProvider(HttpClient.newHttpClient(), OpenAiProvider.DEFAULT_BASE_URL,
                () -> key, openAiModel == null ? "none" : openAiModel, Duration.ofSeconds(10), 400);
        UtteranceSegmenter.Config seg = UtteranceSegmenter.Config.defaults();
        TranslatorSession.Components c = new TranslatorSession.Components(
                () -> new SileroVad(vad), Map.of(Source.REMOTE, seg, Source.MIC, seg),
                new TimedRecognizer(whisper), HallucinationFilter.defaults(), tts, openSink(o),
                new TranslationService(openAi, TranslationService.RetryPolicy.defaults()), transcript,
                new ConversationBuffer(20, Duration.ofMinutes(10), clock), FeedbackGuard.defaults(clock), clock);
        SessionSettings settings = new SessionSettings("zh", o.languages(), List.of(), 2, Duration.ofMillis(1500),
                o.values().get("speaker"), Float.parseFloat(o.get("speed", "1.0")));
        TranslatorSession session = new TranslatorSession(c, settings, new SessionListener() {
            @Override
            public void onUtterance(Utterance u) {
                System.out.printf(Locale.ROOT, "[%-6s] %s %.2f | %s%s%n", u.source().label(), u.language(),
                        u.confidence(), u.text(), u.lowConfidence() ? "   (low confidence)" : "");
            }

            @Override
            public void onTranslation(Utterance u, TranslationResult r) {
                System.out.printf(Locale.ROOT, "   -> %s   (%s %s, %d ms%s)%n", r.text(), r.provider(), r.model(),
                        r.latency().toMillis(), r.fromCache() ? ", cached" : "");
            }

            @Override
            public void onSegmentDropped() {
                System.out.println("   (speech recognition fell behind; one segment was dropped)");
            }

            @Override
            public void onError(String message, Throwable cause) {
                System.out.println("   ERROR: " + message);
            }
        });

        String remote = o.get("remote", "others");
        CaptureLoop.TargetResolver remoteTarget = switch (remote) {
            case "discord" -> DiscordLocator.resolver();
            case "system" -> CaptureLoop.TargetResolver.of(new CaptureTarget.Loopback(null));
            default -> CaptureLoop.TargetResolver.of(
                    new CaptureTarget.ProcessTree(ProcessHandle.current().pid(), CaptureTarget.ProcessTree.Mode.EXCLUDE));
        };
        CaptureLoop.Listener states = (state, detail) -> System.out.println("   (" + state + (detail == null ? "" : ": " + detail) + ")");
        CaptureLoop mic = new CaptureLoop("mic", backend, CaptureLoop.TargetResolver.of(new CaptureTarget.Device(null)),
                x -> session.onAudio(Source.MIC, x), states, CaptureLoop.Config.defaults()).start();
        CaptureLoop other = new CaptureLoop("remote", backend, remoteTarget,
                x -> session.onAudio(Source.REMOTE, x), states, CaptureLoop.Config.defaults()).start();

        System.out.println("\nListening. Enter = translate the latest remote line, r = replay, q = quit.\n");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                String cmd = line.strip().toLowerCase(Locale.ROOT);
                if (cmd.equals("q")) break;
                if (cmd.equals("r")) {
                    if (!session.replayLast()) System.out.println("   (nothing to replay yet)");
                } else if (key == null || key.isBlank()) {
                    System.out.println("   (set OPENAI_API_KEY to translate)");
                } else {
                    session.translateLatestRemote(Instant.now(), false).whenComplete((out, e) -> {
                        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
                        if (cause instanceof TranslatorSession.NothingToTranslateException) {
                            System.out.println("   " + cause.getMessage());
                        }
                    });
                }
            }
        } finally {
            mic.close();
            other.close();
            session.close();
            whisper.close();
            tts.close();
            vad.close();
            backend.shutdown();
            System.out.println("Transcript: " + transcript.file().toAbsolutePath());
        }
        return 0;
    }

    private static void listOpenAiModels(String key) {
        System.out.println("Choose a fast, non-reasoning model with --openai-model. Models your key can use:");
        try {
            OpenAiProvider p = new OpenAiProvider(HttpClient.newHttpClient(), OpenAiProvider.DEFAULT_BASE_URL,
                    () -> key, "none", Duration.ofSeconds(10), 1);
            p.listModels().stream().sorted().forEach(m -> System.out.println("  " + m));
        } catch (Exception e) {
            System.out.println("  (could not list models: " + e.getMessage() + ")");
        }
    }

    /** Prints Whisper's timing for each utterance, so slow recognition is visible. */
    private record TimedRecognizer(WhisperRecognizer whisper) implements com.vct.stt.SpeechRecognizer {
        @Override
        public SttResult transcribe(com.vct.audio.PcmSegment segment, Set<String> allowedLanguages) throws Exception {
            WhisperRecognizer.Transcription t = whisper.transcribe(segment.samples(), allowedLanguages);
            System.out.printf(Locale.ROOT, "   (whisper: %.1f s of audio in %d ms)%n", segment.durationMillis() / 1000.0,
                    t.detectMillis() + t.decodeMillis());
            return t.result();
        }
    }
}
