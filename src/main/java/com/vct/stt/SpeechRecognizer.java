package com.vct.stt;

import com.vct.audio.PcmSegment;

import java.util.Set;

/**
 * Local speech-to-text. Implementations (whisper.cpp, sherpa-onnx) are swappable; the session only
 * sees this interface. Calls may take hundreds of milliseconds and are made off the audio thread.
 */
public interface SpeechRecognizer extends AutoCloseable {

    /**
     * @param allowedLanguages languages the user wants recognized; empty means "any". When it holds
     *                         exactly one language, implementations should force that language.
     */
    SttResult transcribe(PcmSegment segment, Set<String> allowedLanguages) throws Exception;

    @Override
    default void close() {}
}
