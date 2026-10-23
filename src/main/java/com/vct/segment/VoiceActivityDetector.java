package com.vct.segment;

/**
 * Frame-level speech probability. Implementations are stateful (e.g. Silero keeps an RNN state),
 * so use one instance per audio stream.
 */
public interface VoiceActivityDetector {

    /** Samples per frame expected by {@link #speechProbability(float[])} at 16 kHz. */
    int frameSize();

    /** Returns the probability 0..1 that the frame contains speech. */
    float speechProbability(float[] frame);

    default void reset() {}
}
