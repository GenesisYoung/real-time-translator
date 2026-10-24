package com.vct.ui;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * The notification-area icon (AWT: JavaFX has no tray support). Its actions run on the AWT thread;
 * {@link TranslatorApp} hands them to the JavaFX thread.
 */
final class Tray {

    private final TrayIcon icon;

    private Tray(TrayIcon icon) {
        this.icon = icon;
    }

    /** Adds the icon, or returns null where the system has no tray. */
    static Tray install(Runnable show, Runnable translate, Runnable quit) {
        if (!SystemTray.isSupported()) return null;
        PopupMenu menu = new PopupMenu();
        menu.add(item("Show window", show));
        menu.add(item("Translate latest", translate));
        menu.addSeparator();
        menu.add(item("Quit", quit));
        TrayIcon icon = new TrayIcon(image(), "Voice Chat Translator", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> show.run()); // double-click
        try {
            SystemTray.getSystemTray().add(icon);
        } catch (AWTException e) {
            return null;
        }
        return new Tray(icon);
    }

    void notify(String title, String message) {
        icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
    }

    void remove() {
        SystemTray.getSystemTray().remove(icon);
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem m = new MenuItem(label);
        m.addActionListener(e -> action.run());
        return m;
    }

    /** Drawn, not loaded: a blue rounded square with "译" (translate). */
    private static BufferedImage image() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x1565C0));
        g.fillRoundRect(0, 0, 32, 32, 10, 10);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        var fm = g.getFontMetrics();
        String text = g.getFont().canDisplay('译') ? "译" : "T";
        g.drawString(text, (32 - fm.stringWidth(text)) / 2, (32 - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
        return img;
    }
}
