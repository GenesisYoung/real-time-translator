package com.vct.audio;

/** Live audio to an output device, written piece by piece as it is captured. */
public interface AudioStream extends AutoCloseable {

    /**
     * Queues mono samples for playback. Never blocks: when the device falls behind, the samples
     * that do not fit are dropped, so the delay stays short.
     */
    void write(float[] samples);

    /** Plays what is still queued, then releases the device. */
    @Override
    void close();
}
