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
        int contextLines) {

    public static final String API_KEY_SECRET = "openai-api-key";

    public AppSettings {
        recognizedLanguages = List.copyOf(recognizedLanguages);
    }

    public static AppSettings defaults() {
        return new AppSettings(RemoteSource.OTHERS, null, null, "zh", List.of("en", "ja", "zh"),
                "models/whisper/ggml-large-v3-turbo-q8_0.bin", true, "models/tts/vits-piper-zh_CN-huayan-medium",
                1.0, "gpt-4.1-mini", "Ctrl+Alt+T", "Ctrl+Alt+R", 2);
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
        Reader r = new Reader(m, warnings);
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
                r.get("contextLines", d.contextLines(), v -> (int) range(((Number) v).intValue(), 0, 10)));
        return new Loaded(s, List.copyOf(warnings));
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

    private record Reader(Map<String, Object> m, List<String> warnings) {
        <T> T get(String key, T fallback, Function<Object, T> convert) {
            if (!m.containsKey(key)) return fallback;
            Object v = m.get(key);
            try {
                T out = convert.apply(v);
                // null is only valid where the default is null too (the device fields)
                if (out == null && fallback != null) throw new IllegalArgumentException("a value is required");
                return out;
            } catch (RuntimeException e) {
                warnings.add("Setting \"" + key + "\" = " + Json.write(v) + " is invalid (" + e.getMessage()
                        + "); using " + (fallback == null ? "the default" : Json.write(fallback instanceof RemoteSource rs
                        ? rs.name().toLowerCase(java.util.Locale.ROOT) : fallback)) + ".");
                return fallback;
            }
        }
    }
}
