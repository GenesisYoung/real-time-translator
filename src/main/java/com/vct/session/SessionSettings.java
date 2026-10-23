package com.vct.session;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Per-session behaviour.
 *
 * @param nativeLanguage       language the user wants to hear translations in (BCP-47)
 * @param recognizedLanguages  languages STT may detect; empty = any
 * @param protectedTerms       names kept verbatim in translations
 * @param contextLines         previous lines sent as context (0 = only the selected utterance)
 * @param pendingGrace         how long a trigger waits for a still-in-progress remote utterance
 * @param ttsVoiceId           voice id, or null for the language default
 * @param ttsSpeed             playback speed multiplier
 */
public record SessionSettings(
        String nativeLanguage,
        Set<String> recognizedLanguages,
        List<String> protectedTerms,
        int contextLines,
        Duration pendingGrace,
        String ttsVoiceId,
        float ttsSpeed) {

    public SessionSettings {
        recognizedLanguages = Set.copyOf(recognizedLanguages);
        protectedTerms = List.copyOf(protectedTerms);
    }

    public static SessionSettings defaults(String nativeLanguage) {
        return new SessionSettings(nativeLanguage, Set.of(), List.of(), 0, Duration.ofMillis(1500), null, 1.0f);
    }
}
