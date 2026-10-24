package com.vct.tts;

import com.vct.audio.AudioClip;

import java.io.ByteArrayInputStream;

/** Local text-to-speech (Piper / Kokoro via sherpa-onnx in production). */
public interface TtsEngine extends AutoCloseable {

    /**
     * @param language BCP-47 code used to pick a voice when {@code voiceId} is null
     * @param speed    1.0 = normal; values above 1 speak faster
     */
    AudioClip synthesize(String text, String language, String voiceId, float speed) throws Exception;

    /**
     * As {@link #synthesize}, but returns as soon as the first audio is ready, so playback can start
     * while the rest is still being made. This default waits for all of it.
     */
    default SpeechStream stream(String text, String language, String voiceId, float speed) throws Exception {
        AudioClip c = synthesize(text, language, voiceId, speed);
        return new SpeechStream(new ByteArrayInputStream(c.pcm16()), c.sampleRate());
    }

    @Override
    default void close() {}
}
