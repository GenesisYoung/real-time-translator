package com.vct.translate;

import java.util.List;

/**
 * Common interface for AI translation backends (OpenAI now; Claude and Gemini in Phase 3).
 * Methods block and are called from virtual threads; implementations must respond to interruption.
 */
public interface TranslationProvider {

    /** Stable identifier, e.g. "openai". */
    String id();

    /** Currently selected model id. */
    String model();

    TranslationResult translate(TranslationRequest request) throws TranslationException;

    /** Model ids available to this API key. */
    List<String> listModels() throws TranslationException;

    /** Cheap authenticated call that verifies the key and network path; throws on failure. */
    default void testConnection() throws TranslationException {
        listModels();
    }
}
