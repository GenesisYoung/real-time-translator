package com.vct.ui;

import javafx.application.Application;

import java.io.File;

/**
 * Development aid, not a test: shows the main window and the settings dialog with sample data and
 * saves them as PNG files. {@code gradlew uiPreview} (on Linux, under xvfb-run).
 * Like {@link Launcher}, this main class must not extend Application.
 */
public final class UiPreview {

    private UiPreview() {}

    public static void main(String[] args) {
        UiPreviewApp.outDir = new File(args.length > 0 ? args[0] : "build/ui-preview");
        UiPreviewApp.outDir.mkdirs();
        Application.launch(UiPreviewApp.class, args);
    }
}
