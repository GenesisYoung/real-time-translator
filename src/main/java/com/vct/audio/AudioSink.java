package com.vct.audio;

import java.io.IOException;
import java.io.InputStream;

/** A playback destination (private headphones now; virtual microphone in Phase 2). */
public interface AudioSink {

    /** Plays the clip and blocks until playback finishes or {@link #stop()} is called. */
    void play(AudioClip clip) throws InterruptedException;

    /** Stops the clip currently playing (not later ones). Safe to call from any thread. */
    void stop();

    /**
     * Plays signed 16-bit little-endian mono PCM while it is still arriving (e.g. from the network),
     * so playback starts with the first part. Blocks like {@link #play}; {@link #stop()} ends it.
     * This default waits for all of it first.
     */
    default void playStream(InputStream pcm16, int sampleRate) throws InterruptedException, IOException {
        play(AudioClip.fromPcm16(pcm16.readAllBytes(), sampleRate));
    }

    /**
     * Opens a live stream to the same device, used alongside {@link #play}.
     *
     * @throws IllegalStateException if the device cannot be opened
     */
    default AudioStream openStream(int sampleRate) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot stream live audio");
    }
}
