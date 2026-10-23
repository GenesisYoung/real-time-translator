package com.vct.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Writes mono float samples as a 16-bit PCM WAV file. Sizes in the header are filled in on close. */
public final class WavWriter implements AutoCloseable {

    private static final int HEADER = 44;

    private final FileChannel out;
    private final int sampleRate;
    private long dataBytes;

    public WavWriter(Path file, int sampleRate) throws IOException {
        this.out = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        this.sampleRate = sampleRate;
        out.write(header(0), 0);
        out.position(HEADER);
    }

    public synchronized void write(float[] samples) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float s : samples) {
            float c = Math.max(-1f, Math.min(1f, s));
            b.putShort((short) Math.round(c * 32767));
        }
        b.flip();
        while (b.hasRemaining()) out.write(b);
        dataBytes += samples.length * 2L;
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            out.write(header(dataBytes), 0);
        } finally {
            out.close();
        }
    }

    private ByteBuffer header(long data) {
        ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int) (36 + data));
        h.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16);
        h.putShort((short) 1).putShort((short) 1); // PCM, mono
        h.putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        h.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int) data);
        return h.flip();
    }
}
