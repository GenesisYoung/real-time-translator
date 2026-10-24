package com.vct.segment;

/**
 * Loudness-based VAD. A fallback and test double only: it cannot tell speech from game noise.
 * {@link SileroVad} is the production detector.
 */
public final class EnergyVad implements VoiceActivityDetector {

    private final int frameSize;
    private final double thresholdDbfs;

    public EnergyVad(int frameSize, double thresholdDbfs) {
        this.frameSize = frameSize;
        this.thresholdDbfs = thresholdDbfs;
    }

    @Override
    public int frameSize() {
        return frameSize;
    }

    @Override
    public float speechProbability(float[] frame) {
        double sumSq = 0;
        for (float s : frame) sumSq += s * s;
        double rms = Math.sqrt(sumSq / frame.length);
        double db = 20 * Math.log10(Math.max(rms, 1e-9));
        // Logistic curve centred on the threshold, ~6 dB from 0.12 to 0.88.
        return (float) (1 / (1 + Math.exp(-(db - thresholdDbfs) / 1.5)));
    }
}
