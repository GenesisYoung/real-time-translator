package com.vct.audio;

import java.time.Duration;

/** Synthesized or decoded mono float PCM ready for playback. */
public record AudioClip(float[] samples, int sampleRate) {

    public Duration duration() {
        return Duration.ofMillis(samples.length * 1000L / sampleRate);
    }

    /** Signed 16-bit little-endian PCM, as sound devices and OpenAI's "pcm" format use it. */
    public byte[] pcm16() {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int v = Math.round(Math.max(-1f, Math.min(1f, samples[i])) * 32767);
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return out;
    }

    /** The reverse of {@link #pcm16()}; an odd last byte is ignored. */
    public static AudioClip fromPcm16(byte[] pcm, int sampleRate) {
        float[] out = new float[pcm.length / 2];
        for (int i = 0; i < out.length; i++) out[i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8)) / 32768f;
        return new AudioClip(out, sampleRate);
    }
}
