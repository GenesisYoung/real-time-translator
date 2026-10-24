package com.vct.ui;

import com.vct.app.AppSettings;
import com.vct.util.AppHome;
import javafx.application.Application;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Entry point. A main class that extends Application fails with "JavaFX runtime components are
 * missing" when JavaFX is on the classpath; this one does not.
 */
public final class Launcher {

    private Launcher() {}

    /**
     * The installed app has no console: its messages go to
     * {@code %APPDATA%\VoiceChatTranslator\logs\app.log}. The previous run's log is kept as app.old.log.
     */
    private static void logToFile() {
        try {
            Path dir = AppSettings.defaultFile().getParent().resolve("logs");
            Files.createDirectories(dir);
            Path log = dir.resolve("app.log");
            if (Files.exists(log)) Files.move(log, dir.resolve("app.old.log"), StandardCopyOption.REPLACE_EXISTING);
            PrintStream out = new PrintStream(new FileOutputStream(log.toFile()), true, StandardCharsets.UTF_8);
            out.println("Voice Chat Translator, started " + java.time.LocalDateTime.now() + ", Java " + Runtime.version()
                    + ", app folder " + AppHome.dir());
            System.setOut(out);
            System.setErr(out);
        } catch (IOException e) {
            // keep the default streams
        }
    }

    public static void main(String[] args) {
        // Keep idle connections to OpenAI open for 10 minutes (Java's default is 30 s): a new
        // connection costs a TLS handshake, a few hundred milliseconds on every line after a pause.
        System.setProperty("jdk.httpclient.keepalive.timeout", "600");
        System.setProperty("jdk.httpclient.keepalive.timeout.h2", "600");
        // First, before the log and JavaFX: a second copy only asks the first to show its window.
        try {
            SingleInstance instance = SingleInstance.acquire(AppSettings.defaultFile().getParent(), TranslatorApp::showRunning);
            if (instance == null) return;
            TranslatorApp.instance = instance;
        } catch (IOException e) {
            System.err.println("Single-instance check failed, starting anyway: " + e);
        }
        if (AppHome.installed()) logToFile();
        Application.launch(TranslatorApp.class, args);
    }
}
