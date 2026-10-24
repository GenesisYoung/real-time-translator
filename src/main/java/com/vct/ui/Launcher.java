package com.vct.ui;

import javafx.application.Application;

/**
 * Entry point. A main class that extends Application fails with "JavaFX runtime components are
 * missing" when JavaFX is on the classpath; this one does not.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        // Keep idle connections to OpenAI open for 10 minutes (Java's default is 30 s): a new
        // connection costs a TLS handshake, a few hundred milliseconds on every line after a pause.
        System.setProperty("jdk.httpclient.keepalive.timeout", "600");
        System.setProperty("jdk.httpclient.keepalive.timeout.h2", "600");
        Application.launch(TranslatorApp.class, args);
    }
}
