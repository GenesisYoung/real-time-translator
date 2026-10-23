package com.vct.capture;

/** A running capture stream. Use from one thread; {@link #close()} must not overlap {@link #read}. */
public interface CaptureStream extends AutoCloseable {

    /** Counters since open. {@code framesDropped} grows when the reader falls more than 2 s behind. */
    record Stats(long framesCaptured, long framesDropped, int discontinuities) {}

    int sampleRate();

    int channels();

    /**
     * Fills {@code dst} with interleaved samples, waiting up to {@code timeoutMillis} for data.
     *
     * @return frames read (samples / channels); 0 on timeout
     * @throws CaptureException if the stream failed, e.g. the device was unplugged
     */
    int read(float[] dst, int timeoutMillis) throws CaptureException;

    Stats stats();

    @Override
    void close();
}
