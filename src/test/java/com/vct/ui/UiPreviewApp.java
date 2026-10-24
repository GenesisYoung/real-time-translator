package com.vct.ui;

import com.vct.app.AppSettings;
import com.vct.app.SessionController;
import com.vct.capture.AudioDevice;
import com.vct.capture.CaptureLoop;
import com.vct.model.Source;
import com.vct.model.Utterance;
import com.vct.translate.TranslationResult;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The preview itself; started by {@link UiPreview} (see there for why). */
public final class UiPreviewApp extends Application implements MainWindow.Actions {

    static File outDir;

    @Override
    public void start(Stage stage) {
        MainWindow w = new MainWindow(stage, this);
        w.setHotkeyHints("Ctrl+Alt+T", "Ctrl+Alt+R", "Ctrl+Alt+M", "Ctrl+Alt+V");
        w.onRelayState(true, false, "ja");
        w.setRemoteLabel("All audio except this app");
        w.onStatus(SessionController.Status.RUNNING, "Listening (All audio except this app)");
        w.onCaptureState(Source.MIC, CaptureLoop.State.RUNNING, "Default microphone");
        w.onCaptureState(Source.REMOTE, CaptureLoop.State.RUNNING, "All audio except this app");
        Instant t = Instant.parse("2026-10-24T12:03:41Z");
        Utterance a = u("r-0001", Source.REMOTE, t, "We should push mid together, they only have two left.", "en", false);
        Utterance b = u("r-0002", Source.REMOTE, t.plusSeconds(9), "右の建物に敵が三人いるよ、気をつけて。", "ja", false);
        Utterance c = u("m-0001", Source.MIC, t.plusSeconds(15), "好的，我从左边绕过去。", "zh", false);
        Utterance d = u("r-0003", Source.REMOTE, t.plusSeconds(22), "Uh, maybe wait for the, uh...", "en", true);
        w.showNotice("API key saved in Windows Credential Manager.", false);
        stage.show();
        // As in the app: lines and translations arrive one by one after the window is shown.
        List<Runnable> events = List.of(
                () -> w.onUtterance(a),
                () -> w.onTranslation(a, new TranslationResult("我们应该一起推中路，他们只剩两个人了。", "openai", "gpt-4.1-mini", Duration.ofMillis(1376), false)),
                () -> w.onUtterance(b),
                () -> w.onTranslation(b, new TranslationResult("右边的建筑里有三个敌人，小心。", "openai", "gpt-4.1-mini", Duration.ofMillis(1612), false)),
                () -> w.onUtterance(c),
                () -> w.onRelayTranslation(c, new TranslationResult("了解、左から回り込むね。", "openai", "gpt-4.1-mini", Duration.ofMillis(1200), false)),
                () -> w.onRelaySent(c),
                () -> w.onUtterance(d),
                () -> w.onTranslationStarted(d));
        for (int i = 0; i < events.size(); i++) after(100 + 60L * i, events.get(i));

        Platform.runLater(() -> after(1200, () -> {
            save(stage.getScene(), "main-window.png");
            // Then a long history: the list must follow the newest line without blank gaps.
            for (int i = 0; i < 20; i++) {
                int k = i;
                Utterance x = u("r-1" + k, Source.REMOTE, t.plusSeconds(40 + 5L * k), "Line " + (k + 1) + ": enemy spotted near the bridge.", "en", false);
                after(50L * k, () -> w.onUtterance(x));
                after(50L * k + 25, () -> w.onTranslation(x, new TranslationResult("第" + (k + 1) + "句：桥附近发现敌人。", "openai", "gpt-4.1-mini", Duration.ofMillis(900), false)));
            }
        }));
        Platform.runLater(() -> after(2600, () -> {
            save(stage.getScene(), "main-window-long.png");
            Platform.runLater(() -> after(600, () -> {
                // The dialog runs a nested event loop; this snapshot runs inside it.
                Window dialog = Window.getWindows().stream().filter(x -> x != stage).findFirst().orElseThrow();
                save(dialog.getScene(), "settings.png");
                dialog.hide(); // ends the dialog's nested event loop before JavaFX shuts down
                Platform.runLater(Platform::exit);
            }));
            SettingsDialog.show(stage, AppSettings.defaults(), true,
                    CompletableFuture.completedFuture(List.of(new AudioDevice("{mic}", "Headset (WH-CH520)", true, true))),
                    List.of("Headphones (WH-CH520)", "VG27AQML5A (NVIDIA High Definition Audio)"),
                    key -> CompletableFuture.completedFuture(List.of("gpt-4.1-mini")));
        }));
    }

    private static Utterance u(String id, Source s, Instant t, String text, String lang, boolean low) {
        return new Utterance(id, s, t, t.plusSeconds(3), text, lang, 0.95, low ? 0.4 : 0.9, low);
    }

    private static void after(long ms, Runnable r) {
        new Thread(() -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
                return;
            }
            Platform.runLater(r);
        }).start();
    }

    private static void save(Scene scene, String name) {
        WritableImage img = scene.snapshot(null);
        PixelReader px = img.getPixelReader();
        BufferedImage out = new BufferedImage((int) img.getWidth(), (int) img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < out.getHeight(); y++) for (int x = 0; x < out.getWidth(); x++) out.setRGB(x, y, px.getArgb(x, y));
        try {
            ImageIO.write(out, "png", new File(outDir, name));
            System.out.println("wrote " + new File(outDir, name).getAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override public void startStop() {}
    @Override public void translateLatest() {}
    @Override public void replay() {}
    @Override public void cancel() {}
    @Override public void openSettings() {}
    @Override public void translate(Utterance u, boolean force) {}
    @Override public void toggleRelay() {}
}
