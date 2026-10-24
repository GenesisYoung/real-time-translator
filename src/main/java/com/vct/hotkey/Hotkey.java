package com.vct.hotkey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A global key combination such as {@code Ctrl+Alt+T}, as Windows RegisterHotKey expects it.
 *
 * @param modifiers MOD_* flags (Alt 1, Ctrl 2, Shift 4, Win 8)
 * @param virtualKey Windows virtual-key code
 */
public record Hotkey(int modifiers, int virtualKey) {

    public static final int MOD_ALT = 0x1, MOD_CONTROL = 0x2, MOD_SHIFT = 0x4, MOD_WIN = 0x8;

    /** Display name to virtual-key code; parsing ignores case. */
    private static final Map<String, Integer> KEYS = new LinkedHashMap<>();
    private static final Map<String, Integer> BY_UPPER_NAME = new HashMap<>();
    private static final Map<Integer, String> NAMES = new HashMap<>();

    static {
        for (char c = 'A'; c <= 'Z'; c++) KEYS.put(String.valueOf(c), (int) c);
        for (char c = '0'; c <= '9'; c++) KEYS.put(String.valueOf(c), (int) c);
        for (int i = 1; i <= 24; i++) KEYS.put("F" + i, 0x6F + i);
        for (int i = 0; i <= 9; i++) KEYS.put("Num" + i, 0x60 + i);
        KEYS.put("Space", 0x20);
        KEYS.put("PageUp", 0x21);
        KEYS.put("PageDown", 0x22);
        KEYS.put("End", 0x23);
        KEYS.put("Home", 0x24);
        KEYS.put("Left", 0x25);
        KEYS.put("Up", 0x26);
        KEYS.put("Right", 0x27);
        KEYS.put("Down", 0x28);
        KEYS.put("Insert", 0x2D);
        KEYS.put("Delete", 0x2E);
        KEYS.put("Pause", 0x13);
        KEYS.forEach((name, vk) -> {
            BY_UPPER_NAME.put(name.toUpperCase(Locale.ROOT), vk);
            NAMES.put(vk, name);
        });
    }

    /**
     * Parses text like {@code "Ctrl+Alt+T"} or {@code "Shift + F9"} (case-insensitive).
     * A modifier is required, except for Pause and F13-F24: a global hotkey takes the key away from
     * every other app, so a plain letter would stop working in the game.
     */
    public static Hotkey parse(String text) {
        int mods = 0;
        Integer key = null;
        for (String raw : text.split("\\+")) {
            String part = raw.strip().toUpperCase(Locale.ROOT);
            switch (part) {
                case "CTRL", "CONTROL" -> mods |= MOD_CONTROL;
                case "ALT" -> mods |= MOD_ALT;
                case "SHIFT" -> mods |= MOD_SHIFT;
                case "WIN", "WINDOWS" -> mods |= MOD_WIN;
                default -> {
                    Integer vk = BY_UPPER_NAME.get(part);
                    if (vk == null) throw new IllegalArgumentException("Unknown key \"" + raw.strip() + "\" in \"" + text + "\"");
                    if (key != null) throw new IllegalArgumentException("Only one non-modifier key allowed in \"" + text + "\"");
                    key = vk;
                }
            }
        }
        if (key == null) throw new IllegalArgumentException("No key in \"" + text + "\", e.g. Ctrl+Alt+T");
        boolean standalone = key == 0x13 || (key >= 0x7C && key <= 0x87); // Pause, F13-F24
        if (mods == 0 && !standalone) {
            throw new IllegalArgumentException("\"" + text + "\" needs Ctrl, Alt, Shift or Win: on its own the key would stop working in other apps");
        }
        return new Hotkey(mods, key);
    }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        if ((modifiers & MOD_CONTROL) != 0) parts.add("Ctrl");
        if ((modifiers & MOD_ALT) != 0) parts.add("Alt");
        if ((modifiers & MOD_SHIFT) != 0) parts.add("Shift");
        if ((modifiers & MOD_WIN) != 0) parts.add("Win");
        parts.add(NAMES.getOrDefault(virtualKey, String.format(Locale.ROOT, "0x%02X", virtualKey)));
        return String.join("+", parts);
    }
}
