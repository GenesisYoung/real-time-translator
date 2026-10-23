package com.vct.audio;

/**
 * A contiguous chunk of mono float PCM (range -1..1) believed to contain one utterance.
 *
 * @param startSample index of the first sample, counted from the start of the stream
 * @param forcedSplit true if the segment was cut at the maximum length rather than at a pause
 */
public record PcmSegment(float[] samples, int sampleRate, long startSample, boolean forcedSplit) {

    public long endSample() {
        return startSample + samples.length;
    }

    public long durationMillis() {
        return samples.length * 1000L / sampleRate;
    }
}
