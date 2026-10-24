package com.vct.ui;

import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;

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
        TrayIcon icon = new TrayIcon(AppIcon.draw(32), "Voice Chat Translator", menu);
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
}
