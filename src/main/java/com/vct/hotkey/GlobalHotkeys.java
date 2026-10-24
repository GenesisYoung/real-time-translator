package com.vct.hotkey;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * System-wide hotkeys through Windows RegisterHotKey, which works while a game has focus and, unlike
 * a keyboard hook, does not look like a key logger to anti-cheat software.
 *
 * <p>Windows delivers WM_HOTKEY to the message queue of the thread that registered the key, so a
 * dedicated thread registers them and runs a message loop. Actions run on another thread, so a slow
 * action never delays the next key press.
 */
@SuppressWarnings("restricted") // calling Windows APIs is this class's purpose
public final class GlobalHotkeys implements AutoCloseable {

    private static final int MOD_NOREPEAT = 0x4000; // holding the key down fires once
    private static final int WM_HOTKEY = 0x0312, WM_QUIT = 0x0012, WM_USER = 0x0400, PM_NOREMOVE = 0;
    private static final int ERROR_HOTKEY_ALREADY_REGISTERED = 1409;
    // MSG, x64: measured with offsetof() (48 bytes; message at 8, wParam at 16).
    private static final long MSG_SIZE = 48, MSG_MESSAGE = 8, MSG_WPARAM = 16;

    private final Map<Hotkey, Runnable> bindings;
    private final List<String> problems = new ArrayList<>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private final ExecutorService actions = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("vct-hotkey-action").daemon().factory());
    private final Thread thread;
    private volatile int threadId;

    private final MethodHandle register, unregister, getMessage, peekMessage, postThreadMessage, currentThreadId;
    private final StructLayout callState = Linker.Option.captureStateLayout();
    private final VarHandle lastError = callState.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));

    /**
     * Registers the hotkeys and starts listening. Keys another app already uses are skipped and
     * reported by {@link #problems()}.
     */
    public GlobalHotkeys(Map<Hotkey, Runnable> bindings) throws InterruptedException {
        this.bindings = new LinkedHashMap<>(bindings);
        Linker linker = Linker.nativeLinker();
        SymbolLookup user32 = SymbolLookup.libraryLookup("User32", Arena.global());
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("Kernel32", Arena.global());
        Linker.Option capture = Linker.Option.captureCallState("GetLastError");
        register = linker.downcallHandle(find(user32, "RegisterHotKey"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT), capture);
        unregister = linker.downcallHandle(find(user32, "UnregisterHotKey"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        getMessage = linker.downcallHandle(find(user32, "GetMessageW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        peekMessage = linker.downcallHandle(find(user32, "PeekMessageW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
        postThreadMessage = linker.downcallHandle(find(user32, "PostThreadMessageW"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG));
        currentThreadId = linker.downcallHandle(find(kernel32, "GetCurrentThreadId"), FunctionDescriptor.of(JAVA_INT));

        thread = Thread.ofPlatform().name("vct-hotkeys").daemon().start(this::loop);
        if (!ready.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("hotkey thread did not start");
    }

    /** User-readable reasons why hotkeys could not be registered; empty when all work. */
    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    private void loop() {
        List<Hotkey> keys = new ArrayList<>(bindings.keySet());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment msg = arena.allocate(MSG_SIZE, 8);
            MemorySegment state = arena.allocate(callState);
            threadId = (int) invoke(currentThreadId);
            // Creates this thread's message queue now, so close() can always post WM_QUIT to it.
            invoke(peekMessage, msg, MemorySegment.NULL, WM_USER, WM_USER, PM_NOREMOVE);
            for (int i = 0; i < keys.size(); i++) {
                Hotkey k = keys.get(i);
                if ((int) invoke(register, state, MemorySegment.NULL, i + 1, k.modifiers() | MOD_NOREPEAT, k.virtualKey()) == 0) {
                    int err = (int) lastError.get(state, 0L);
                    synchronized (this) {
                        problems.add(err == ERROR_HOTKEY_ALREADY_REGISTERED
                                ? k + " is already used by another app. Choose a different key in Settings."
                                : k + " could not be registered (error " + err + ").");
                    }
                }
            }
            ready.countDown();
            // GetMessageW returns 0 on WM_QUIT and -1 on error.
            while ((int) invoke(getMessage, msg, MemorySegment.NULL, 0, 0) > 0) {
                if (msg.get(JAVA_INT, MSG_MESSAGE) != WM_HOTKEY) continue;
                int id = (int) msg.get(JAVA_LONG, MSG_WPARAM);
                if (id >= 1 && id <= keys.size()) {
                    Runnable action = bindings.get(keys.get(id - 1));
                    actions.execute(() -> {
                        try {
                            action.run();
                        } catch (RuntimeException e) {
                            System.err.println("Hotkey action failed: " + e);
                        }
                    });
                }
            }
            for (int i = 0; i < keys.size(); i++) invoke(unregister, MemorySegment.NULL, i + 1);
        } finally {
            ready.countDown();
        }
    }

    @Override
    public void close() {
        invoke(postThreadMessage, threadId, WM_QUIT, 0L, 0L);
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        actions.shutdown();
    }

    private static MemorySegment find(SymbolLookup lib, String name) {
        return lib.find(name).orElseThrow(() -> new IllegalStateException("missing Windows function " + name));
    }

    private static Object invoke(MethodHandle h, Object... args) {
        try {
            return h.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
