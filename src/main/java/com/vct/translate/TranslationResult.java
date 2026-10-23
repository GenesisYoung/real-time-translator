package com.vct.translate;

import java.time.Duration;

public record TranslationResult(String text, String provider, String model, Duration latency, boolean fromCache) {

    TranslationResult asCached() {
        return new TranslationResult(text, provider, model, Duration.ZERO, true);
    }
}
