package com.vct.app;

import com.vct.capture.RemoteSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSettingsTest {

    @Test
    void missingFileGivesDefaults(@TempDir Path dir) throws Exception {
        AppSettings.Loaded l = AppSettings.load(dir.resolve("settings.json"));
        assertEquals(AppSettings.defaults(), l.settings());
        assertTrue(l.warnings().isEmpty());
    }

    @Test
    void savesAndLoadsEveryField(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("sub/settings.json");
        AppSettings s = new AppSettings(RemoteSource.DISCORD, "{0.0.1.00000000}.{abc}", "WH-CH520", "zh",
                List.of("en", "ja"), "C:\\models\\w.bin", false, "voices/zh", 1.25, "gpt-4.1-mini",
                "Ctrl+Shift+F9", "Alt+F10", 0,
                new AppSettings.RelaySettings(true, "ja", "CABLE Input (VB-Audio Virtual Cable)", "tts-1", "nova", "Ctrl+Alt+M", "Shift+F9"),
                new AppSettings.SubtitleSettings(false, true, 32.5, false, 12, -1280.5, 900.0, "Ctrl+Alt+F1"), true);
        s.save(f);
        assertEquals(new AppSettings.Loaded(s, List.of()), AppSettings.load(f));
        assertFalse(Files.exists(dir.resolve("sub/settings.json.tmp")), "temporary file removed");
    }

    @Test
    void aBadValueFallsBackAloneAndIsReported(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("settings.json");
        Files.writeString(f, "{\"remoteSource\":\"discordm\",\"ttsSpeed\":9,\"whisperGpu\":null,"
                + "\"translateHotkey\":\"T\",\"micDeviceId\":null,\"openAiModel\":\"gpt-x\"}");
        AppSettings.Loaded l = AppSettings.load(f);
        AppSettings d = AppSettings.defaults();
        assertEquals(d.remoteSource(), l.settings().remoteSource());
        assertEquals(d.ttsSpeed(), l.settings().ttsSpeed());
        assertEquals(d.whisperGpu(), l.settings().whisperGpu());
        assertEquals(d.translateHotkey(), l.settings().translateHotkey());
        assertEquals("gpt-x", l.settings().openAiModel(), "valid values are kept");
        assertEquals(4, l.warnings().size(), l.warnings().toString());
        assertTrue(l.warnings().getFirst().startsWith("Setting \"remoteSource\" = \"discordm\" is invalid"), l.warnings().getFirst());
    }

    @Test
    void relaySettingsFallBackPerValue(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("settings.json"),
                "{\"relay\":{\"enabled\":true,\"targetLanguage\":\"ja\",\"pauseHotkey\":\"M\"}}");
        AppSettings.Loaded l = AppSettings.load(f);
        AppSettings.RelaySettings r = l.settings().relay();
        assertTrue(r.enabled());
        assertEquals("ja", r.targetLanguage());
        assertEquals(AppSettings.RelaySettings.defaults().pauseHotkey(), r.pauseHotkey());
        assertEquals("CABLE Input", r.outputDevice(), "missing values keep their defaults");
        assertEquals(1, l.warnings().size(), l.warnings().toString());
        assertTrue(l.warnings().getFirst().startsWith("Setting \"relay.pauseHotkey\" = \"M\" is invalid"), l.warnings().getFirst());
    }

    @Test
    void olderSettingsFilesWithoutRelayStillLoad(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("settings.json"), "{\"openAiModel\":\"gpt-4.1-mini\"}");
        AppSettings.Loaded l = AppSettings.load(f);
        assertEquals(AppSettings.RelaySettings.defaults(), l.settings().relay());
        assertEquals(AppSettings.SubtitleSettings.defaults(), l.settings().subtitles());
        assertTrue(l.warnings().isEmpty());
    }

    @Test
    void aBadSubtitleValueFallsBackAlone(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("settings.json"),
                "{\"subtitles\":{\"enabled\":false,\"fontSize\":500,\"x\":null,\"y\":10}}");
        AppSettings.Loaded l = AppSettings.load(f);
        AppSettings.SubtitleSettings st = l.settings().subtitles();
        assertFalse(st.enabled());
        assertEquals(AppSettings.SubtitleSettings.defaults().fontSize(), st.fontSize());
        assertEquals(null, st.x());
        assertEquals(10.0, st.y());
        assertEquals(1, l.warnings().size(), l.warnings().toString());
        assertTrue(l.warnings().getFirst().startsWith("Setting \"subtitles.fontSize\" = 500 is invalid"), l.warnings().getFirst());
    }

    @Test
    void damagedFileGivesDefaultsAndAWarning(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("settings.json"), "{\"remoteSource\":");
        AppSettings.Loaded l = AppSettings.load(f);
        assertEquals(AppSettings.defaults(), l.settings());
        assertTrue(l.warnings().getFirst().contains("is damaged"), l.warnings().toString());
    }
}
