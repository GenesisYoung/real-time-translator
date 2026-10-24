package com.vct.hotkey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Presses a hotkey through the real Windows input system. Ctrl+Alt+Shift+F24 collides with nothing. */
@EnabledOnOs(OS.WINDOWS)
@SuppressWarnings("restricted")
class GlobalHotkeysTest {

    private static final Hotkey KEY = Hotkey.parse("Ctrl+Alt+Shift+F24");

    @Test
    void firesWhenTheKeyIsPressedAndReportsKeysInUse() throws Throwable {
        CountDownLatch fired = new CountDownLatch(1);
        try (GlobalHotkeys hotkeys = new GlobalHotkeys(Map.of(KEY, fired::countDown))) {
            assertEquals(List.of(), hotkeys.problems());
            press(0x11, 0x12, 0x10, 0x87); // Ctrl, Alt, Shift, F24
            assertTrue(fired.await(3, TimeUnit.SECONDS), "hotkey action did not run");

            try (GlobalHotkeys second = new GlobalHotkeys(Map.of(KEY, () -> {}))) {
                assertEquals(1, second.problems().size());
                assertTrue(second.problems().getFirst().contains("already used by another app"), second.problems().toString());
            }
        }
    }

    @Test
    void closeReturnsPromptly() throws Exception {
        GlobalHotkeys hotkeys = new GlobalHotkeys(Map.of(KEY, () -> {}));
        long t0 = System.nanoTime();
        hotkeys.close();
        assertTrue(System.nanoTime() - t0 < 1_000_000_000L);
    }

    /** keybd_event: presses the keys in order, then releases them in reverse. */
    private static void press(int... vks) throws Throwable {
        MethodHandle keybd = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("User32", Arena.global()).find("keybd_event").orElseThrow(),
                FunctionDescriptor.ofVoid(JAVA_BYTE, JAVA_BYTE, JAVA_INT, JAVA_LONG));
        for (int vk : vks) keybd.invokeExact((byte) vk, (byte) 0, 0, 0L);
        for (int i = vks.length - 1; i >= 0; i--) keybd.invokeExact((byte) vks[i], (byte) 0, 2, 0L); // KEYEVENTF_KEYUP
    }
}
