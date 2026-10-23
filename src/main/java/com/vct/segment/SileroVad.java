package com.vct.segment;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.FloatBuffer;
import java.util.Map;

/**
 * Silero VAD v6 (ONNX, MIT licence) through ONNX Runtime on the CPU. It tells speech from noise,
 * music and game sound far better than {@link EnergyVad}, at about 0.1 ms per 32 ms frame.
 *
 * <p>Model protocol (from the official Python wrapper): each call takes the last 64 samples of the
 * previous frame followed by 512 new samples at 16 kHz, plus a [2, 1, 128] recurrent state, and
 * returns the speech probability and the next state.
 *
 * <p>Not thread-safe: one instance per audio stream. The {@link Model} is shared.
 */
public final class SileroVad implements VoiceActivityDetector {

    public static final int FRAME_SIZE = 512;
    static final int CONTEXT_SIZE = 64;
    private static final int SAMPLE_RATE = 16_000;
    private static final long[] INPUT_SHAPE = {1, CONTEXT_SIZE + FRAME_SIZE};
    private static final long[] STATE_SHAPE = {2, 1, 128};

    /** The loaded network. Thread-safe; load once and share between streams. */
    public static final class Model implements AutoCloseable {
        private final OrtEnvironment env = OrtEnvironment.getEnvironment();
        private final OrtSession session;

        private Model(byte[] onnx) throws OrtException {
            try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
                // The network is tiny: extra threads only add scheduling overhead.
                opts.setIntraOpNumThreads(1);
                opts.setInterOpNumThreads(1);
                session = env.createSession(onnx, opts);
            }
        }

        /** Loads the model bundled with the app. */
        public static Model load() {
            try (InputStream in = SileroVad.class.getResourceAsStream("silero_vad.onnx")) {
                if (in == null) throw new IllegalStateException("silero_vad.onnx missing from the classpath");
                return new Model(in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (OrtException e) {
                throw new IllegalStateException("Could not load Silero VAD: " + e.getMessage(), e);
            }
        }

        @Override
        public void close() throws OrtException {
            session.close();
        }
    }

    private final Model model;
    private final float[] input = new float[CONTEXT_SIZE + FRAME_SIZE];
    private float[] state = new float[2 * 128];

    public SileroVad(Model model) {
        this.model = model;
    }

    @Override
    public int frameSize() {
        return FRAME_SIZE;
    }

    @Override
    public float speechProbability(float[] frame) {
        if (frame.length != FRAME_SIZE) throw new IllegalArgumentException("expected " + FRAME_SIZE + " samples");
        System.arraycopy(frame, 0, input, CONTEXT_SIZE, FRAME_SIZE);
        OrtEnvironment env = model.env;
        try (OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), INPUT_SHAPE);
             OnnxTensor s = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), STATE_SHAPE);
             OnnxTensor sr = OnnxTensor.createTensor(env, (long) SAMPLE_RATE);
             OrtSession.Result r = model.session.run(Map.of("input", x, "state", s, "sr", sr))) {
            float probability = ((float[][]) r.get(0).getValue())[0][0];
            state = flatten((float[][][]) r.get(1).getValue());
            // The last 64 samples of this frame are the next call's context.
            System.arraycopy(input, FRAME_SIZE, input, 0, CONTEXT_SIZE);
            return probability;
        } catch (OrtException e) {
            throw new IllegalStateException("Silero VAD failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(input, 0f);
        state = new float[2 * 128];
    }

    private static float[] flatten(float[][][] s) {
        float[] out = new float[2 * 128];
        System.arraycopy(s[0][0], 0, out, 0, 128);
        System.arraycopy(s[1][0], 0, out, 128, 128);
        return out;
    }
}
