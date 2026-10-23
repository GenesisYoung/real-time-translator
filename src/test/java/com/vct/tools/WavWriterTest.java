package com.vct.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WavWriterTest {

    @Test
    void writesHeaderSizesAndClippedSamples(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.wav");
        try (WavWriter w = new WavWriter(f, 16_000)) {
            w.write(new float[]{0f, 1f});
            w.write(new float[]{-1f, 2f}); // 2 is clipped to full scale
        }
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(44 + 8, b.limit());
        assertEquals("RIFF", new String(b.array(), 0, 4, StandardCharsets.US_ASCII));
        assertEquals(36 + 8, b.getInt(4));
        assertEquals(1, b.getShort(22), "mono");
        assertEquals(16_000, b.getInt(24));
        assertEquals(8, b.getInt(40), "data size");
        assertEquals(0, b.getShort(44));
        assertEquals(32767, b.getShort(46));
        assertEquals(-32767, b.getShort(48));
        assertEquals(32767, b.getShort(50));
    }
}
