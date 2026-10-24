package com.vct.secret;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Stores secrets such as the OpenAI API key outside the settings file. */
public interface SecretStore {

    Optional<String> get(String key) throws SecretException;

    void put(String key, String value) throws SecretException;

    /** Removes the secret; does nothing if it does not exist. */
    void delete(String key) throws SecretException;

    /** Windows Credential Manager on Windows; memory only elsewhere (development and tests). */
    static SecretStore forThisSystem() {
        return System.getProperty("os.name").startsWith("Windows") ? new WindowsCredentialStore() : inMemory();
    }

    static SecretStore inMemory() {
        Map<String, String> m = new ConcurrentHashMap<>();
        return new SecretStore() {
            @Override
            public Optional<String> get(String key) {
                return Optional.ofNullable(m.get(key));
            }

            @Override
            public void put(String key, String value) {
                m.put(key, value);
            }

            @Override
            public void delete(String key) {
                m.remove(key);
            }
        };
    }
}
