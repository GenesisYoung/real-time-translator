package com.vct.translate;

import java.util.List;
import java.util.Objects;

/**
 * Everything a provider is allowed to see. Deliberately text-only: no audio, no device names, no
 * session metadata.
 *
 * @param sourceLanguage BCP-47 code, or null to let the model infer it
 * @param context        preceding lines for optional contextual translation (empty by default)
 * @param protectedTerms usernames, game titles and other terms to keep verbatim
 */
public record TranslationRequest(
        String utteranceId,
        String text,
        String sourceLanguage,
        String targetLanguage,
        TranslationMode mode,
        List<String> context,
        List<String> protectedTerms) {

    public TranslationRequest {
        Objects.requireNonNull(utteranceId, "utteranceId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(targetLanguage, "targetLanguage");
        Objects.requireNonNull(mode, "mode");
        context = List.copyOf(context);
        protectedTerms = List.copyOf(protectedTerms);
    }

    /**
     * Key used to avoid re-translating the same utterance into the same target. The text is part of
     * it: utterance ids start again at r-0001 in every session, and the cache outlives sessions.
     */
    String cacheKey() {
        return utteranceId + '|' + targetLanguage + '|' + mode + '|' + text;
    }
}
