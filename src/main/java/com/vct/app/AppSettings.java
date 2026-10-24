package com.vct.app;

import com.vct.capture.RemoteSource;
import com.vct.hotkey.Hotkey;
import com.vct.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * User settings, saved as JSON. The OpenAI API key is not part of them: it is kept in Windows
 * Credential Manager. Relative paths are resolved against the app's working directory.
 *
 * @param micDeviceId  capture device id, or null for the Windows default microphone
 * @param outputDevice part of the output device name for translations, or null for the default
 * @param relay        "translate my speech": the user's lines, translated into the voice chat
 * @param subtitles    the others' lines, translated automatically and shown over the game
 */
public record AppSettings(
        RemoteSource remoteSource,
        String micDeviceId,
        String outputDevice,
        String nativeLanguage,
        List<String> recognizedLanguages,
        String whisperModel,
        boolean whisperGpu,
        String ttsVoice,
        double ttsSpeed,
        String openAiModel,
        String translateHotkey,
        String replayHotkey,
        int contextLines,
        RelaySettings relay,
        SubtitleSettings subtitles) {

    public static final String API_KEY_SECRET = "openai-api-key";

    /**
     * "Translate my speech". The translation plays on {@code outputDevice}, normally the virtual
     * cable's "CABLE Input", whose other end ("CABLE Output") is the voice chat's microphone.
     *
     * @param targetLanguage what the others hear ("en" or "ja"), chosen per session
     * @param ttsModel       OpenAI speech model, e.g. gpt-4o-mini-tts
     * @param voice          OpenAI voice, e.g. alloy
     * @param liveHotkey     held down, sends the user's real voice instead (for speaking the language directly)
     */
    public record RelaySettings(boolean enabled, String targetLanguage, String outputDevice, String ttsModel,
                                String voice, String pauseHotkey, String liveHotkey) {
        public static RelaySettings defaults() {
            return new RelaySettings(false, "en", "CABLE Input", "gpt-4o-mini-tts", "alloy", "Ctrl+Alt+M", "Ctrl+Alt+V");
        }
    }

    /**
     * Subtitles over the game. While they are on, every line of the others is translated (one
     * request each); while off (the hotkey), nothing is sent.
     *
     * @param speak       also read them aloud on the headphones
     * @param holdSeconds how long a line stays after its translation arrived
     * @param x           screen position of the subtitles' centre, or null for the default place
     * @param y           screen position of their bottom edge, or null
     */
    public record SubtitleSettings(boolean enabled, boolean speak, double fontSize, boolean showOriginal,
                                   int holdSeconds, Double x, Double y, String hotkey) {
        public static SubtitleSettings defaults() {
            return new SubtitleSettings(true, false, 26, true, 8, null, null, "Ctrl+Alt+S");
        }

        public SubtitleSettings at(Double x, Double y) {
            return new SubtitleSettings(enabled, speak, fontSize, showOriginal, holdSeconds, x, y, hotkey);
        }
    }

    public AppSettings withSubtitles(SubtitleSettings s) {
        return new AppSettings(remoteSource, micDeviceId, outputDevice, nativeLanguage, recognizedLanguages, whisperModel,
                whisperGpu, ttsVoice, ttsSpeed, openAiModel, translateHotkey, replayHotkey, contextLines, relay, s);
    }

    public AppSettings {
        recognizedLanguages = List.copyOf(recognizedLanguages);
    }

    public static AppSettings defaults() {
        return new AppSettings(RemoteSource.OTHERS, null, null, "zh", List.of("en", "ja", "zh"),
                "models/whisper/ggml-large-v3-turbo-q8_0.bin", true, "models/tts/vits-piper-zh_CN-huayan-medium",
                1.0, "gpt-4.1-mini", "Ctrl+Alt+T", "Ctrl+Alt+R", 2, RelaySettings.defaults(), SubtitleSettings.defaults());
    }

    /** {@code %APPDATA%\VoiceChatTranslator\settings.json} on Windows, {@code ~/.config/...} elsewhere. */
    public static Path defaultFile() {
        String appData = System.getenv("APPDATA");
        return appData != null
                ? Path.of(appData, "VoiceChatTranslator", "settings.json")
                : Path.of(System.getProperty("user.home"), ".config", "voice-chat-translator", "settings.json");
    }

    /** Settings read from a file, plus warnings about values that were invalid and replaced by defaults. */
    public record Loaded(AppSettings settings, List<String> warnings) {}

    /** Reads settings; a missing file gives the defaults, a broken value its default (with a warning). */
    public static Loaded load(Path file) throws IOException {
        AppSettings d = defaults();
        if (!Files.exists(file)) return new Loaded(d, List.of());
        Map<String, Object> m;
        try {
            m = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return new Loaded(d, List.of("Settings file " + file + " is damaged (" + e.getMessage() + "); using defaults."));
        }
        List<String> warnings = new ArrayList<>();
        Reader r = new Reader(m, warnings, "");
        AppSettings s = new AppSettings(
                r.get("remoteSource", d.remoteSource(), v -> RemoteSource.parse((String) v)),
                r.get("micDeviceId", d.micDeviceId(), v -> (String) v),
                r.get("outputDevice", d.outputDevice(), v -> (String) v),
                r.get("nativeLanguage", d.nativeLanguage(), v -> nonBlank((String) v)),
                r.get("recognizedLanguages", d.recognizedLanguages(), v -> ((List<?>) v).stream().map(x -> nonBlank((String) x)).toList()),
                r.get("whisperModel", d.whisperModel(), v -> nonBlank((String) v)),
                r.get("whisperGpu", d.whisperGpu(), v -> (Boolean) v),
                r.get("ttsVoice", d.ttsVoice(), v -> nonBlank((String) v)),
                r.get("ttsSpeed", d.ttsSpeed(), v -> range(((Number) v).doubleValue(), 0.5, 2.0)),
                r.get("openAiModel", d.openAiModel(), v -> nonBlank((String) v)),
                r.get("translateHotkey", d.translateHotkey(), v -> Hotkey.parse((String) v).toString()),
                r.get("replayHotkey", d.replayHotkey(), v -> Hotkey.parse((String) v).toString()),
                r.get("contextLines", d.contextLines(), v -> (int) range(((Number) v).intValue(), 0, 10)),
                loadRelay(m.get("relay"), warnings),
                loadSubtitles(m.get("subtitles"), warnings));
        return new Loaded(s, List.copyOf(warnings));
    }

    private static SubtitleSettings loadSubtitles(Object value, List<String> warnings) {
        SubtitleSettings d = SubtitleSettings.defaults();
        Map<String, Object> m = nested(value, "subtitles", warnings);
        if (m == null) return d;
        Reader r = new Reader(m, warnings, "subtitles.");
        return new SubtitleSettings(
                r.get("enabled", d.enabled(), v -> (Boolean) v),
                r.get("speak", d.speak(), v -> (Boolean) v),
                r.get("fontSize", d.fontSize(), v -> range(((Number) v).doubleValue(), 12, 72)),
                r.get("showOriginal", d.showOriginal(), v -> (Boolean) v),
                r.get("holdSeconds", d.holdSeconds(), v -> (int) range(((Number) v).intValue(), 2, 60)),
                r.get("x", d.x(), v -> v == null ? null : ((Number) v).doubleValue()),
                r.get("y", d.y(), v -> v == null ? null : ((Number) v).doubleValue()),
                r.get("hotkey", d.hotkey(), v -> Hotkey.parse((String) v).toString()));
    }

    /** A nested settings object as a map, or null (with a warning if it is there but not an object). */
    private static Map<String, Object> nested(Object value, String name, List<String> warnings) {
        if (!(value instanceof Map<?, ?> raw)) {
            if (value != null) warnings.add("Setting \"" + name + "\" is invalid; using the defaults.");
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        raw.forEach((k, v) -> m.put(String.valueOf(k), v));
        return m;
    }

    private static RelaySettings loadRelay(Object value, List<String> warnings) {
        RelaySettings d = RelaySettings.defaults();
        Map<String, Object> m = nested(value, "relay", warnings);
        if (m == null) return d;
        Reader r = new Reader(m, warnings, "relay.");
        return new RelaySettings(
                r.get("enabled", d.enabled(), v -> (Boolean) v),
                r.get("targetLanguage", d.targetLanguage(), v -> nonBlank((String) v)),
                r.get("outputDevice", d.outputDevice(), v -> (String) v),
                r.get("ttsModel", d.ttsModel(), v -> nonBlank((String) v)),
                r.get("voice", d.voice(), v -> nonBlank((String) v)),
                r.get("pauseHotkey", d.pauseHotkey(), v -> Hotkey.parse((String) v).toString()),
                r.get("liveHotkey", d.liveHotkey(), v -> Hotkey.parse((String) v).toString()));
    }

    /** Writes atomically: a crash while saving never leaves a half-written file. */
    public void save(Path file) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("remoteSource", remoteSource.name().toLowerCase(java.util.Locale.ROOT));
        m.put("micDeviceId", micDeviceId);
        m.put("outputDevice", outputDevice);
        m.put("nativeLanguage", nativeLanguage);
        m.put("recognizedLanguages", recognizedLanguages);
        m.put("whisperModel", whisperModel);
        m.put("whisperGpu", whisperGpu);
        m.put("ttsVoice", ttsVoice);
        m.put("ttsSpeed", ttsSpeed);
        m.put("openAiModel", openAiModel);
        m.put("translateHotkey", translateHotkey);
        m.put("replayHotkey", replayHotkey);
        m.put("contextLines", contextLines);
        Map<String, Object> rl = new LinkedHashMap<>();
        rl.put("enabled", relay.enabled());
        rl.put("targetLanguage", relay.targetLanguage());
        rl.put("outputDevice", relay.outputDevice());
        rl.put("ttsModel", relay.ttsModel());
        rl.put("voice", relay.voice());
        rl.put("pauseHotkey", relay.pauseHotkey());
        rl.put("liveHotkey", relay.liveHotkey());
        m.put("relay", rl);
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("enabled", subtitles.enabled());
        st.put("speak", subtitles.speak());
        st.put("fontSize", subtitles.fontSize());
        st.put("showOriginal", subtitles.showOriginal());
        st.put("holdSeconds", subtitles.holdSeconds());
        st.put("x", subtitles.x());
        st.put("y", subtitles.y());
        st.put("hotkey", subtitles.hotkey());
        m.put("subtitles", st);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(m), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String nonBlank(String s) {
        if (s.isBlank()) throw new IllegalArgumentException("empty");
        return s.strip();
    }

    private static double range(double v, double min, double max) {
        if (v < min || v > max) throw new IllegalArgumentException("must be " + min + " to " + max);
        return v;
    }

    /** @param prefix shown before the key in warnings, e.g. "relay." */
    private record Reader(Map<String, Object> m, List<String> warnings, String prefix) {
        <T> T get(String key, T fallback, Function<Object, T> convert) {
            if (!m.containsKey(key)) return fallback;
            Object v = m.get(key);
            try {
                T out = convert.apply(v);
                // null is only valid where the default is null too (the device fields)
                if (out == null && fallback != null) throw new IllegalArgumentException("a value is required");
                return out;
            } catch (RuntimeException e) {
                warnings.add("Setting \"" + prefix + key + "\" = " + Json.write(v) + " is invalid (" + e.getMessage()
                        + "); using " + (fallback == null ? "the default" : Json.write(fallback instanceof RemoteSource rs
                        ? rs.name().toLowerCase(java.util.Locale.ROOT) : fallback)) + ".");
                return fallback;
            }
        }
    }
}
