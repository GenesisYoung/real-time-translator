package com.vct.ui;

import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The app's icon, drawn rather than loaded: a blue rounded square with "译" (translate). Used by the
 * tray, the windows and, as an .ico file, the installed program.
 */
public final class AppIcon {

    /** Sizes in the .ico file: Windows picks one for the taskbar, Start menu and Explorer. */
    static final int[] ICO_SIZES = {16, 24, 32, 48, 64, 128, 256};

    private AppIcon() {}

    static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x1565C0));
        int arc = Math.round(size * 0.31f);
        g.fillRoundRect(0, 0, size, size, arc, arc);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(size * 0.69f)));
        var fm = g.getFontMetrics();
        String text = g.getFont().canDisplay('译') ? "译" : "T";
        g.drawString(text, (size - fm.stringWidth(text)) / 2, (size - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
        return img;
    }

    /** For JavaFX windows (title bar and taskbar). */
    static List<Image> forWindows() {
        List<Image> out = new ArrayList<>();
        for (int size : new int[] {16, 32, 48, 256}) {
            BufferedImage b = draw(size);
            WritableImage w = new WritableImage(size, size);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) w.getPixelWriter().setArgb(x, y, b.getRGB(x, y));
            out.add(w);
        }
        return out;
    }

    /**
     * Writes a Windows .ico file. Each size is stored as PNG data, which Windows reads since Vista:
     * a 6-byte header, a 16-byte entry per image, then the images.
     */
    public static void writeIco(OutputStream out) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (int size : ICO_SIZES) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(draw(size), "png", png);
            pngs.add(png.toByteArray());
        }
        ByteBuffer head = ByteBuffer.allocate(6 + 16 * pngs.size()).order(ByteOrder.LITTLE_ENDIAN);
        head.putShort((short) 0).putShort((short) 1).putShort((short) pngs.size()); // reserved, type 1 = icon, count
        int offset = head.capacity();
        for (int i = 0; i < pngs.size(); i++) {
            int size = ICO_SIZES[i];
            head.put((byte) (size >= 256 ? 0 : size)).put((byte) (size >= 256 ? 0 : size)); // 0 means 256
            head.put((byte) 0).put((byte) 0);                  // no palette, reserved
            head.putShort((short) 1).putShort((short) 32);     // planes, bits per pixel
            head.putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        out.write(head.array());
        for (byte[] png : pngs) out.write(png);
    }

    /**
     * Writes the icon for the packaged program: {@code gradlew packageApp} runs this. A file ending
     * in .ico gets every size (Windows), one ending in .png a 256-pixel image (Linux).
     */
    public static void main(String[] args) throws IOException {
        java.nio.file.Path file = java.nio.file.Path.of(args[0]);
        java.nio.file.Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream out = java.nio.file.Files.newOutputStream(file)) {
            if (file.toString().endsWith(".png")) javax.imageio.ImageIO.write(draw(256), "png", out);
            else writeIco(out);
        }
    }
}
