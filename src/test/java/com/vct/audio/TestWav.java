package com.vct.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Reads 16-bit PCM mono 16 kHz WAV test resources. */
public final class TestWav {

    private TestWav() {}

    public static float[] read16kMono(String resource) throws IOException {
        byte[] bytes;
        try (InputStream in = TestWav.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing test resource " + resource);
            bytes = in.readAllBytes();
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (!"RIFF".equals(tag(bytes, 0)) || !"WAVE".equals(tag(bytes, 8))) throw new IOException("not a WAV file");
        // Walk the chunks: files often carry metadata (e.g. LIST) between "fmt " and "data".
        for (int pos = 12; pos + 8 <= bytes.length; ) {
            String id = tag(bytes, pos);
            int size = b.getInt(pos + 4);
            int body = pos + 8;
            if (id.equals("fmt ")) {
                if (b.getShort(body) != 1 || b.getShort(body + 2) != 1 || b.getInt(body + 4) != 16_000
                        || b.getShort(body + 14) != 16) {
                    throw new IOException(resource + " must be 16-bit PCM, mono, 16 kHz");
                }
            } else if (id.equals("data")) {
                float[] out = new float[size / 2];
                for (int i = 0; i < out.length; i++) out[i] = b.getShort(body + 2 * i) / 32768f;
                return out;
            }
            pos = body + size + (size & 1);
        }
        throw new IOException(resource + " has no data chunk");
    }

    private static String tag(byte[] bytes, int pos) {
        return new String(bytes, pos, 4, StandardCharsets.US_ASCII);
    }
}
