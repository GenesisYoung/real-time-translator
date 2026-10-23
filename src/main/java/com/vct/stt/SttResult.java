package com.vct.stt;

/**
 * @param language           detected or forced BCP-47 code, or null
 * @param languageConfidence 0..1 or NaN
 * @param confidence         0..1 (e.g. exp of Whisper's average token log-prob) or NaN
 * @param noSpeechProbability Whisper's no-speech probability 0..1, or NaN if not provided
 */
public record SttResult(
        String text,
        String language,
        double languageConfidence,
        double confidence,
        double noSpeechProbability) {
}
