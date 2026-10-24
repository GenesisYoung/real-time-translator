package com.vct.secret;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Uses the real Credential Manager, under a unique name it deletes again. */
@EnabledOnOs(OS.WINDOWS)
class WindowsCredentialStoreTest {

    private final SecretStore store = new WindowsCredentialStore();
    private final String key = "test-" + UUID.randomUUID();

    @Test
    void roundTripsUnicodeAndOverwritesAndDeletes() throws SecretException {
        assertEquals(Optional.empty(), store.get(key));
        try {
            store.put(key, "sk-test-密钥-🔑");
            assertEquals(Optional.of("sk-test-密钥-🔑"), store.get(key));
            store.put(key, "second");
            assertEquals(Optional.of("second"), store.get(key));
        } finally {
            store.delete(key);
        }
        assertEquals(Optional.empty(), store.get(key));
        assertDoesNotThrow(() -> store.delete(key), "deleting a missing secret is not an error");
    }
}
