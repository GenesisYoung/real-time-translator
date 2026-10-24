package com.vct.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsDialogTest {

    private static String check(String translate, String replay, String langs) {
        return SettingsDialog.validate(translate, replay, langs, "zh", "gpt-4.1-mini", "w.bin", "voice");
    }

    @Test
    void acceptsValidInput() {
        assertNull(check("Ctrl+Alt+T", "Ctrl+Alt+R", "en,ja,zh"));
    }

    @Test
    void explainsWhatIsWrong() {
        assertTrue(check("T", "Ctrl+Alt+R", "en").startsWith("Hotkey: \"T\" needs Ctrl"), check("T", "Ctrl+Alt+R", "en"));
        assertEquals("The two hotkeys must be different.", check("Ctrl+Alt+T", "ctrl+alt+t", "en"));
        assertEquals("Enter at least one language to recognize.", check("Ctrl+Alt+T", "Ctrl+Alt+R", " , "));
        assertEquals("Enter an OpenAI model, e.g. gpt-4.1-mini.",
                SettingsDialog.validate("Ctrl+Alt+T", "Ctrl+Alt+R", "en", "zh", " ", "w.bin", "v"));
    }

    @Test
    void splitsLanguageLists() {
        assertEquals(List.of("en", "ja", "zh"), SettingsDialog.splitLanguages(" en, ja ,,zh "));
    }
}
