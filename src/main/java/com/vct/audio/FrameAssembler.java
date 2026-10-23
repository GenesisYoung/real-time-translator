package com.vct.audio;

import java.util.function.Consumer;

/** Re-chunks arbitrary-sized sample blocks into fixed-size frames (e.g. 512 samples for Silero VAD). */
public final class FrameAssembler {

    private final float[] frame;
    private int fill;

    public FrameAssembler(int frameSize) {
        this.frame = new float[frameSize];
    }

    public void push(float[] samples, Consumer<float[]> onFrame) {
        int i = 0;
        while (i < samples.length) {
            int n = Math.min(frame.length - fill, samples.length - i);
            System.arraycopy(samples, i, frame, fill, n);
            fill += n;
            i += n;
            if (fill == frame.length) {
                onFrame.accept(frame.clone());
                fill = 0;
            }
        }
    }
}
