package com.vct.transcript;

import com.vct.model.Utterance;
import com.vct.translate.TranslationResult;

import java.io.IOException;

/** Receives transcript events. {@link #disabled()} is used when the user turns transcripts off. */
public interface TranscriptSink extends AutoCloseable {

    void writeUtterance(Utterance u) throws IOException;

    void writeTranslation(String utteranceId, String targetLanguage, TranslationResult r) throws IOException;

    @Override
    void close() throws IOException;

    static TranscriptSink disabled() {
        return new TranscriptSink() {
            @Override public void writeUtterance(Utterance u) {}
            @Override public void writeTranslation(String id, String lang, TranslationResult r) {}
            @Override public void close() {}
        };
    }
}
