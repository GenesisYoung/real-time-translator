package com.vct.secret;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Secrets in Windows Credential Manager (generic credentials, visible under Control Panel >
 * Credential Manager > Windows Credentials as "VoiceChatTranslator/..."). Windows encrypts them for
 * the signed-in user. Uses advapi32 through FFM; no native library of our own.
 */
@SuppressWarnings("restricted") // calling Windows APIs is this class's purpose
public final class WindowsCredentialStore implements SecretStore {

    private static final String PREFIX = "VoiceChatTranslator/";
    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    private static final int ERROR_NOT_FOUND = 1168;

    // CREDENTIALW, x64: measured with offsetof() (80 bytes; CredentialBlob at 40, UserName at 72).
    static final long SIZE = 80, TYPE = 4, TARGET_NAME = 8, BLOB_SIZE = 32, BLOB = 40, PERSIST = 48, USER_NAME = 72;

    private final MethodHandle credWrite;
    private final MethodHandle credRead;
    private final MethodHandle credDelete;
    private final MethodHandle credFree;
    private final StructLayout callState = Linker.Option.captureStateLayout();
    private final VarHandle lastError = callState.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));

    public WindowsCredentialStore() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup advapi = SymbolLookup.libraryLookup("Advapi32", Arena.global());
        Linker.Option capture = Linker.Option.captureCallState("GetLastError");
        credWrite = linker.downcallHandle(find(advapi, "CredWriteW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), capture);
        credRead = linker.downcallHandle(find(advapi, "CredReadW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), capture);
        credDelete = linker.downcallHandle(find(advapi, "CredDeleteW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), capture);
        credFree = linker.downcallHandle(find(advapi, "CredFree"), FunctionDescriptor.ofVoid(ADDRESS));
    }

    @Override
    public Optional<String> get(String key) throws SecretException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(callState);
            MemorySegment out = arena.allocate(ADDRESS);
            int ok = (int) invoke(credRead, state, wide(arena, key), CRED_TYPE_GENERIC, 0, out);
            if (ok == 0) {
                int err = (int) lastError.get(state, 0L);
                if (err == ERROR_NOT_FOUND) return Optional.empty();
                throw new SecretException("Could not read \"" + PREFIX + key + "\" from Credential Manager (error " + err + ")");
            }
            MemorySegment cred = out.get(ADDRESS, 0).reinterpret(SIZE);
            try {
                int n = cred.get(JAVA_INT, BLOB_SIZE);
                byte[] bytes = cred.get(ADDRESS, BLOB).reinterpret(n).toArray(JAVA_BYTE);
                return Optional.of(new String(bytes, StandardCharsets.UTF_8));
            } finally {
                invoke(credFree, cred);
            }
        }
    }

    @Override
    public void put(String key, String value) throws SecretException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cred = arena.allocate(SIZE, 8); // zeroed: no comment, alias or attributes
            cred.set(JAVA_INT, TYPE, CRED_TYPE_GENERIC);
            cred.set(ADDRESS, TARGET_NAME, wide(arena, key));
            cred.set(JAVA_INT, BLOB_SIZE, bytes.length);
            cred.set(ADDRESS, BLOB, arena.allocateFrom(JAVA_BYTE, bytes));
            cred.set(JAVA_INT, PERSIST, CRED_PERSIST_LOCAL_MACHINE);
            cred.set(ADDRESS, USER_NAME, arena.allocateFrom("Voice Chat Translator", StandardCharsets.UTF_16LE));
            MemorySegment state = arena.allocate(callState);
            if ((int) invoke(credWrite, state, cred, 0) == 0) {
                throw new SecretException("Could not save \"" + PREFIX + key + "\" in Credential Manager (error "
                        + (int) lastError.get(state, 0L) + ")");
            }
        }
    }

    @Override
    public void delete(String key) throws SecretException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(callState);
            if ((int) invoke(credDelete, state, wide(arena, key), CRED_TYPE_GENERIC, 0) == 0) {
                int err = (int) lastError.get(state, 0L);
                if (err != ERROR_NOT_FOUND) {
                    throw new SecretException("Could not delete \"" + PREFIX + key + "\" from Credential Manager (error " + err + ")");
                }
            }
        }
    }

    private static MemorySegment wide(Arena arena, String key) {
        return arena.allocateFrom(PREFIX + key, StandardCharsets.UTF_16LE); // NUL-terminated UTF-16
    }

    private static MemorySegment find(SymbolLookup lib, String name) {
        return lib.find(name).orElseThrow(() -> new IllegalStateException("advapi32 has no " + name));
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
