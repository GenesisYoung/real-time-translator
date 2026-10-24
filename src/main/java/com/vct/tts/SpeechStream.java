package com.vct.tts;

import java.io.IOException;
import java.io.InputStream;

/**
 * Synthesized speech that may still be arriving: signed 16-bit little-endian mono PCM.
 * Close it if it is not played.
 */
public record SpeechStream(InputStream pcm16, int sampleRate) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        pcm16.close();
    }
}
