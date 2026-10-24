package com.vct.tools;

import com.vct.audio.Resampler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a 16-bit PCM WAV file (any rate, mono or stereo) as mono float samples at 16 kHz. */
public final class WavReader {

    private WavReader() {}

    public static float[] read16kMono(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 12 || !tag(bytes, 0).equals("RIFF") || !tag(bytes, 8).equals("WAVE")) {
            throw new IOException(file + " is not a WAV file");
        }
        int channels = 0, rate = 0;
        // Walk the chunks: metadata such as LIST may sit between "fmt " and "data".
        for (int pos = 12; pos + 8 <= bytes.length; ) {
            String id = tag(bytes, pos);
            int size = b.getInt(pos + 4);
            int body = pos + 8;
            if (id.equals("fmt ")) {
                int format = b.getShort(body) & 0xFFFF;
                channels = b.getShort(body + 2);
                rate = b.getInt(body + 4);
                int bits = b.getShort(body + 14);
                // 0xFFFE (extensible) is accepted when it carries plain 16-bit PCM.
                if ((format != 1 && format != 0xFFFE) || bits != 16) {
                    throw new IOException(file + ": only 16-bit PCM WAV is supported (format " + format + ", " + bits + " bit)");
                }
            } else if (id.equals("data")) {
                if (channels == 0) throw new IOException(file + ": data before fmt chunk");
                int n = Math.min(size, bytes.length - body) / 2;
                float[] interleaved = new float[n];
                for (int i = 0; i < n; i++) interleaved[i] = b.getShort(body + 2 * i) / 32768f;
                float[] mono = Resampler.downmix(interleaved, channels);
                return rate == 16_000 ? mono : new Resampler(rate, 16_000).process(mono);
            }
            pos = body + size + (size & 1);
        }
        throw new IOException(file + " has no audio data");
    }

    private static String tag(byte[] bytes, int pos) {
        return new String(bytes, pos, 4, StandardCharsets.US_ASCII);
    }
}
