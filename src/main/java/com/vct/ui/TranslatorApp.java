package com.vct.ui;

import com.vct.app.AppSettings;
import com.vct.app.DefaultEngines;
import com.vct.app.SessionController;
import com.vct.audio.JavaSoundSink;
import com.vct.capture.AudioDevice;
import com.vct.hotkey.GlobalHotkeys;
import com.vct.hotkey.Hotkey;
import com.vct.model.Utterance;
import com.vct.secret.SecretException;
import com.vct.secret.SecretStore;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The desktop app: main window, settings, tray icon and global hotkeys around a
 * {@link SessionController}. Closing the window keeps the app running in the tray; Quit ends it.
 */
public final class TranslatorApp extends Application implements MainWindow.Actions {

    private final Path settingsFile = AppSettings.defaultFile();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private AppSettings settings;
    private SecretStore secrets;
    private SessionController controller;
    private MainWindow window;
    private SubtitleOverlay overlay;
    private GlobalHotkeys hotkeys;
    private Tray tray;
    private boolean trayHintShown;
    private boolean quitting;

    @Override
    public void start(Stage stage) {
        Platform.setImplicitExit(false); // the tray keeps the app alive while the window is hidden
        List<String> notices = new ArrayList<>();
        try {
            AppSettings.Loaded loaded = AppSettings.load(settingsFile);
            settings = loaded.settings();
            notices.addAll(loaded.warnings());
        } catch (IOException e) {
            settings = AppSettings.defaults();
            notices.add("Could not read " + settingsFile + ": " + e.getMessage() + ". Using defaults.");
        }
        secrets = SecretStore.forThisSystem();
        try {
            apiKey.set(secrets.get(AppSettings.API_KEY_SECRET).orElse(null));
        } catch (SecretException e) {
            notices.add(e.getMessage());
        }
        if (apiKey.get() == null && System.getenv("OPENAI_API_KEY") != null) {
            apiKey.set(System.getenv("OPENAI_API_KEY")); // from the console tests; this run only
            notices.add("Using OPENAI_API_KEY from the environment for this run. Save the key in Settings to keep it.");
        } else if (apiKey.get() == null) {
            notices.add("Add your OpenAI API key in Settings to translate.");
        }

        window = new MainWindow(stage, this);
        overlay = new SubtitleOverlay(overlayStyle(), this::subtitlesMoved);
        window.setOverlay(overlay);
        overlay.open();
        controller = new SessionController(new DefaultEngines(), apiKey::get, window);
        applySettingsToUi();
        notices.addAll(registerHotkeys());

        tray = Tray.install(() -> Platform.runLater(this::showWindow), controller::translateLatest,
                () -> Platform.runLater(this::quit));
        stage.setOnCloseRequest(e -> {
            if (tray == null) {
                quit();
                return;
            }
            e.consume();
            stage.hide();
            if (!trayHintShown) {
                tray.notify("Still running", "Translation hotkeys keep working. Right-click the tray icon to quit.");
                trayHintShown = true;
            }
        });
        if (!notices.isEmpty()) window.showNotice(String.join("\n", notices), false);
        stage.show();
    }

    // ------------------------------------------------------------------ actions

    @Override
    public void startStop() {
        if (controller.status() == SessionController.Status.RUNNING) controller.stop();
        else controller.start(settings);
    }

    @Override
    public void translateLatest() {
        controller.translateLatest();
    }

    @Override
    public void replay() {
        controller.replay();
    }

    @Override
    public void cancel() {
        controller.cancel();
    }

    @Override
    public void toggleRelay() {
        controller.toggleRelayPaused();
    }

    @Override
    public void toggleSubtitles() {
        controller.toggleSubtitles();
    }

    @Override
    public void moveSubtitles() {
        overlay.setMoving(!overlay.isMoving());
        window.setMovingSubtitles(overlay.isMoving());
    }

    /** The user dragged the subtitles somewhere else: keep that place. */
    private void subtitlesMoved(double x, double y) {
        settings = settings.withSubtitles(settings.subtitles().at(x, y));
        try {
            settings.save(settingsFile);
        } catch (IOException e) {
            window.showNotice("Could not save the subtitles' position: " + e.getMessage(), true);
        }
    }

    private SubtitleOverlay.Style overlayStyle() {
        AppSettings.SubtitleSettings st = settings.subtitles();
        return new SubtitleOverlay.Style(settings.nativeLanguage(), st.fontSize(), st.showOriginal(), st.holdSeconds(),
                st.x(), st.y());
    }

    @Override
    public void translate(Utterance u, boolean force) {
        controller.translate(u, force); // problems arrive as notices and errors
    }

    @Override
    public void openSettings() {
        CompletableFuture<List<AudioDevice>> mics = CompletableFuture.supplyAsync(() -> {
            try {
                return controller.listDevices(AudioDevice.Flow.CAPTURE);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        AppSettings current = settings;
        SettingsDialog.show(window.stage(), current, apiKey.get() != null, mics, JavaSoundSink.outputDevices(),
                typedKey -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return typedKey != null ? controller.listModels(current, typedKey) : controller.listModels(current);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                })).ifPresent(this::applySettings);
    }

    private void applySettings(SettingsDialog.Result r) {
        List<String> notices = new ArrayList<>();
        settings = r.settings();
        try {
            settings.save(settingsFile);
        } catch (IOException e) {
            notices.add("Could not save settings: " + e.getMessage());
        }
        try {
            if (r.newApiKey().isPresent()) {
                secrets.put(AppSettings.API_KEY_SECRET, r.newApiKey().get());
                apiKey.set(r.newApiKey().get());
                notices.add("API key saved in Windows Credential Manager.");
            } else if (r.removeKey()) {
                secrets.delete(AppSettings.API_KEY_SECRET);
                apiKey.set(null);
                notices.add("API key removed.");
            }
        } catch (SecretException e) {
            notices.add(e.getMessage());
        }
        applySettingsToUi();
        notices.addAll(registerHotkeys());
        if (controller.status() == SessionController.Status.RUNNING) {
            controller.start(settings); // restart with the new settings
            notices.add("Restarting the session with the new settings.");
        }
        if (!notices.isEmpty()) window.showNotice(String.join("\n", notices), false);
    }

    private void applySettingsToUi() {
        window.setHotkeyHints(settings.translateHotkey(), settings.replayHotkey(), settings.relay().pauseHotkey(),
                settings.relay().liveHotkey());
        window.setSubtitlesHotkey(settings.subtitles().hotkey());
        window.setRemoteLabel(settings.remoteSource().label());
        overlay.setStyle(overlayStyle());
        controller.setSubtitles(settings.subtitles().enabled(), settings.subtitles().speak());
    }

    /** (Re)registers the hotkeys; returns problems to show. Global hotkeys exist on Windows only. */
    private List<String> registerHotkeys() {
        if (hotkeys != null) hotkeys.close();
        hotkeys = null;
        if (!System.getProperty("os.name").startsWith("Windows")) return List.of();
        Map<Hotkey, Runnable> bindings = new LinkedHashMap<>();
        bindings.put(Hotkey.parse(settings.translateHotkey()), controller::translateLatest);
        bindings.put(Hotkey.parse(settings.replayHotkey()), controller::replay);
        bindings.put(Hotkey.parse(settings.relay().pauseHotkey()), controller::toggleRelayPaused);
        bindings.put(Hotkey.parse(settings.subtitles().hotkey()), controller::toggleSubtitles);
        Map<Hotkey, GlobalHotkeys.Hold> holds = Map.of(Hotkey.parse(settings.relay().liveHotkey()),
                new GlobalHotkeys.Hold(() -> controller.setLive(true), () -> controller.setLive(false)));
        try {
            hotkeys = new GlobalHotkeys(bindings, holds);
            return hotkeys.problems();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of("Hotkeys were not registered.");
        } catch (RuntimeException e) {
            return List.of("Hotkeys are not available: " + e.getMessage());
        }
    }

    private void showWindow() {
        Stage s = window.stage();
        s.show();
        s.setIconified(false);
        s.toFront();
    }

    private void quit() {
        if (quitting) return;
        quitting = true;
        window.showNotice("Closing...", false);
        // Stopping finishes recognition and closes the transcript; keep it off the UI thread.
        Thread.ofPlatform().name("vct-quit").start(() -> {
            shutdown();
            Platform.exit();
            System.exit(0); // ends the AWT tray thread as well
        });
    }

    private synchronized void shutdown() {
        if (hotkeys != null) hotkeys.close();
        hotkeys = null;
        if (controller != null) controller.close();
        controller = null;
        if (tray != null) tray.remove();
        tray = null;
    }

    @Override
    public void stop() {
        shutdown();
    }
}
