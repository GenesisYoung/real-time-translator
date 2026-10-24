package com.vct.ui;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Windows styles that make the subtitle window behave like a game overlay: mouse clicks go through
 * it to the game, it never takes the keyboard focus, and it is not listed in Alt+Tab. JavaFX has no
 * API for these, so they are set on the native window (found by its unique title) through user32.
 * Elsewhere this does nothing.
 */
@SuppressWarnings("restricted") // calling Windows APIs is this class's purpose
final class OverlayWindowStyle {

    private static final int GWL_EXSTYLE = -20;
    private static final long WS_EX_TRANSPARENT = 0x20, WS_EX_TOOLWINDOW = 0x80, WS_EX_LAYERED = 0x80000,
            WS_EX_NOACTIVATE = 0x08000000L;
    private static final int SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOZORDER = 0x4, SWP_NOACTIVATE = 0x10,
            SWP_FRAMECHANGED = 0x20;

    private static final MethodHandle FIND_WINDOW, GET_LONG, SET_LONG, SET_POS;

    static {
        MethodHandle find = null, get = null, set = null, pos = null;
        if (System.getProperty("os.name").startsWith("Windows")) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("User32", Arena.global());
            find = linker.downcallHandle(user32.find("FindWindowW").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            get = linker.downcallHandle(user32.find("GetWindowLongPtrW").orElseThrow(),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
            set = linker.downcallHandle(user32.find("SetWindowLongPtrW").orElseThrow(),
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG));
            pos = linker.downcallHandle(user32.find("SetWindowPos").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        }
        FIND_WINDOW = find;
        GET_LONG = get;
        SET_LONG = set;
        SET_POS = pos;
    }

    private OverlayWindowStyle() {}

    /**
     * Applies the overlay styles to the shown window with this title.
     *
     * @param clickThrough true: clicks reach the window below (the game); false: the window can be dragged
     * @return false if not on Windows or the window was not found
     */
    static boolean apply(String windowTitle, boolean clickThrough) {
        if (FIND_WINDOW == null) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment hwnd = (MemorySegment) FIND_WINDOW.invokeExact(MemorySegment.NULL,
                    arena.allocateFrom(windowTitle, StandardCharsets.UTF_16LE));
            if (hwnd.address() == 0) return false;
            long style = (long) GET_LONG.invokeExact(hwnd, GWL_EXSTYLE);
            style |= WS_EX_LAYERED | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE;
            style = clickThrough ? style | WS_EX_TRANSPARENT : style & ~WS_EX_TRANSPARENT;
            long ignored = (long) SET_LONG.invokeExact(hwnd, GWL_EXSTYLE, style);
            // Windows applies changed styles only after SetWindowPos with SWP_FRAMECHANGED.
            int ok = (int) SET_POS.invokeExact(hwnd, MemorySegment.NULL, 0, 0, 0, 0,
                    SWP_NOSIZE | SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
            return ok != 0;
        } catch (Throwable t) {
            System.err.println("Could not set the subtitle window style: " + t);
            return false;
        }
    }
}
