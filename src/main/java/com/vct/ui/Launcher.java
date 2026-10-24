package com.vct.ui;

import javafx.application.Application;

/**
 * Entry point. A main class that extends Application fails with "JavaFX runtime components are
 * missing" when JavaFX is on the classpath; this one does not.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        Application.launch(TranslatorApp.class, args);
    }
}
