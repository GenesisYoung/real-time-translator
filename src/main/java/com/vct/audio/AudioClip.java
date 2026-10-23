package com.vct.audio;

import java.time.Duration;

/** Synthesized or decoded mono float PCM ready for playback. */
public record AudioClip(float[] samples, int sampleRate) {

    public Duration duration() {
        return Duration.ofMillis(samples.length * 1000L / sampleRate);
    }
}
