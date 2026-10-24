package com.vct.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsDialogTest {

    private static String check(String translate, String replay, String langs) {
        return SettingsDialog.validate(translate, replay, "Ctrl+Alt+M", "Ctrl+Alt+V", langs, "zh", "gpt-4.1-mini", "w.bin", "voice");
    }

    @Test
    void acceptsValidInput() {
        assertNull(check("Ctrl+Alt+T", "Ctrl+Alt+R", "en,ja,zh"));
    }

    @Test
    void explainsWhatIsWrong() {
        assertTrue(check("T", "Ctrl+Alt+R", "en").startsWith("Hotkey: \"T\" needs Ctrl"), check("T", "Ctrl+Alt+R", "en"));
        assertEquals("The hotkeys must all be different.", check("Ctrl+Alt+T", "ctrl+alt+t", "en"));
        assertEquals("The hotkeys must all be different.", check("Ctrl+Alt+M", "Ctrl+Alt+R", "en"), "pause key is Ctrl+Alt+M");
        assertEquals("The hotkeys must all be different.", check("Ctrl+Alt+V", "Ctrl+Alt+R", "en"), "live key is Ctrl+Alt+V");
        assertEquals("Enter at least one language to recognize.", check("Ctrl+Alt+T", "Ctrl+Alt+R", " , "));
        assertEquals("Enter an OpenAI model, e.g. gpt-4.1-mini.",
                SettingsDialog.validate("Ctrl+Alt+T", "Ctrl+Alt+R", "Ctrl+Alt+M", "Ctrl+Alt+V", "en", "zh", " ", "w.bin", "v"));
        assertNull(SettingsDialog.validateRelay(false, "", ""), "nothing to check while switched off");
        assertTrue(SettingsDialog.validateRelay(true, " ", "gpt-4o-mini-tts").startsWith("Choose an OpenAI voice"));
    }

    @Test
    void splitsLanguageLists() {
        assertEquals(List.of("en", "ja", "zh"), SettingsDialog.splitLanguages(" en, ja ,,zh "));
    }
}
