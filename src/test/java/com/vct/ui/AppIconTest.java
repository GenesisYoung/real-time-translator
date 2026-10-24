package com.vct.ui;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AppIconTest {

    @Test
    void writesAValidIcoWithEverySize() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AppIcon.writeIco(out);
        byte[] ico = out.toByteArray();
        ByteBuffer b = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, b.getShort(0));
        assertEquals(1, b.getShort(2), "type 1: icon");
        int count = b.getShort(4);
        assertEquals(AppIcon.ICO_SIZES.length, count);
        for (int i = 0; i < count; i++) {
            int entry = 6 + 16 * i;
            int width = Byte.toUnsignedInt(ico[entry]);
            int length = b.getInt(entry + 8), offset = b.getInt(entry + 12);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(Arrays.copyOfRange(ico, offset, offset + length)));
            assertNotNull(img, "entry " + i + " is a PNG");
            assertEquals(width == 0 ? 256 : width, img.getWidth());
            assertEquals(AppIcon.ICO_SIZES[i], img.getWidth());
        }
        assertEquals(ico.length, b.getInt(6 + 16 * (count - 1) + 8) + b.getInt(6 + 16 * (count - 1) + 12), "nothing after the last image");
    }
}
