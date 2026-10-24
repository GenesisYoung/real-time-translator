package com.vct.hotkey;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotkeyTest {

    @Test
    void parsesModifiersAndKeysCaseInsensitively() {
        Hotkey k = Hotkey.parse("ctrl + alt + t");
        assertEquals(Hotkey.MOD_CONTROL | Hotkey.MOD_ALT, k.modifiers());
        assertEquals('T', k.virtualKey());
        assertEquals("Ctrl+Alt+T", k.toString());
        assertEquals(new Hotkey(Hotkey.MOD_SHIFT, 0x78), Hotkey.parse("Shift+F9"));
        assertEquals("Ctrl+PageDown", Hotkey.parse("CONTROL+pagedown").toString());
        assertEquals("Win+Num5", Hotkey.parse("win+num5").toString());
    }

    @Test
    void toStringParsesBackToTheSameKey() {
        for (String s : new String[]{"Ctrl+Alt+R", "Alt+Shift+F12", "Ctrl+Win+Space", "Pause", "F24"}) {
            assertEquals(Hotkey.parse(s), Hotkey.parse(Hotkey.parse(s).toString()), s);
        }
    }

    @Test
    void refusesKeysThatWouldBreakOtherApps() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Hotkey.parse("T"));
        assertTrue(e.getMessage().contains("needs Ctrl, Alt, Shift or Win"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Hotkey.parse("F9"));
        Hotkey.parse("F13"); // rarely used keys may stand alone
    }

    @Test
    void explainsTypos() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Hotkey.parse("Ctrl+Atl+T"))
                .getMessage().startsWith("Unknown key \"Atl\""));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Hotkey.parse("Ctrl+Alt"))
                .getMessage().startsWith("No key"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Hotkey.parse("Ctrl+A+B"))
                .getMessage().startsWith("Only one non-modifier key"));
    }
}
