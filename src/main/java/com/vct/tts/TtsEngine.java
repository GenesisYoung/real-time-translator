package com.vct.tts;

import com.vct.audio.AudioClip;

/** Local text-to-speech (Piper / Kokoro via sherpa-onnx in production). */
public interface TtsEngine extends AutoCloseable {

    /**
     * @param language BCP-47 code used to pick a voice when {@code voiceId} is null
     * @param speed    1.0 = normal; values above 1 speak faster
     */
    AudioClip synthesize(String text, String language, String voiceId, float speed) throws Exception;

    @Override
    default void close() {}
}
