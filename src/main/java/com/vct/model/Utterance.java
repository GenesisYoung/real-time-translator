package com.vct.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One finalized, recognized utterance.
 *
 * @param language           BCP-47 language code reported by recognition, or {@code null} if unknown
 * @param languageConfidence 0..1, or NaN if unknown
 * @param confidence         recognizer confidence 0..1, or NaN if unknown
 * @param lowConfidence      true when the UI should warn (noisy / overlapping / uncertain speech)
 */
public record Utterance(
        String id,
        Source source,
        Instant start,
        Instant end,
        String text,
        String language,
        double languageConfidence,
        double confidence,
        boolean lowConfidence) {

    public Utterance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(text, "text");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end before start");
        }
    }

    public Duration duration() {
        return Duration.between(start, end);
    }
}
