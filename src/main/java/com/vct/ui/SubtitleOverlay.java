package com.vct.ui;

import com.vct.model.Source;
import com.vct.model.Utterance;
import com.vct.session.TranslatorSession;
import com.vct.translate.TranslationResult;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Subtitles over the game: the others' lines, translated, in a transparent window that stays on top,
 * lets mouse clicks through to the game and never takes the keyboard focus.
 *
 * <p>A line appears as soon as it is recognized (grey, in the original language) and turns into the
 * translation when it arrives. Each line fades out a few seconds after its last change; at most
 * {@link #MAX_LINES} are shown. Games in exclusive full-screen mode draw over every window, so the
 * game must run in borderless or windowed mode.
 *
 * <p>All methods must be called on the JavaFX thread.
 */
final class SubtitleOverlay {

    /**
     * @param nativeLanguage lines already in it are shown as they are, without waiting for a translation
     * @param x              screen coordinate of the subtitles' horizontal centre, or null for the
     *                       middle of the main screen
     * @param y              screen coordinate of their bottom edge, or null for near the bottom
     */
    record Style(String nativeLanguage, double fontSize, boolean showOriginal, int holdSeconds, Double x, Double y) {}

    static final int MAX_LINES = 3;
    private static final String TITLE = "Voice Chat Translator subtitles " + ProcessHandle.current().pid();

    private final Stage owner = new Stage(StageStyle.UTILITY); // an owned window gets no taskbar button
    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final VBox lines = new VBox(6);
    private final Label moveHint = new Label("Drag to place the subtitles, then press \"Done moving\" in the main window.");
    private final StackPane root = new StackPane();
    private final Map<String, Line> shown = new LinkedHashMap<>();
    private final BiConsumer<Double, Double> onMoved;
    private Style style;
    private boolean active;
    private boolean moving;
    private Point2D dragFrom;

    /** @param onMoved receives the new position (see {@link Style}) after the user moved the subtitles */
    SubtitleOverlay(Style style, BiConsumer<Double, Double> onMoved) {
        this.style = style;
        this.onMoved = onMoved;
        lines.setAlignment(Pos.BOTTOM_CENTER);
        moveHint.getStyleClass().add("subtitle-move-hint");
        moveHint.setVisible(false);
        StackPane.setAlignment(moveHint, Pos.TOP_CENTER);
        root.getChildren().addAll(lines, moveHint);
        root.getStyleClass().add("subtitle-root");
        root.setPadding(new Insets(8));
        Scene scene = new Scene(root, Color.TRANSPARENT);
        scene.getStylesheets().add(SubtitleOverlay.class.getResource("app.css").toExternalForm());
        scene.setOnMousePressed(e -> dragFrom = moving ? new Point2D(e.getScreenX() - stage.getX(), e.getScreenY() - stage.getY()) : null);
        scene.setOnMouseDragged(e -> {
            if (dragFrom == null) return;
            stage.setX(e.getScreenX() - dragFrom.getX());
            stage.setY(e.getScreenY() - dragFrom.getY());
        });
        owner.setOpacity(0);
        owner.setWidth(1);
        owner.setHeight(1);
        owner.setX(-32_000);
        stage.initOwner(owner);
        stage.setTitle(TITLE);
        stage.setAlwaysOnTop(true);
        stage.setScene(scene);
        layout();
    }

    /** Applies new settings; the lines shown stay. */
    void setStyle(Style style) {
        this.style = style;
        shown.values().forEach(Line::restyle);
        layout();
    }

    /**
     * Opens the (empty) window. Call once at start, while the app has the focus anyway: opening it
     * later, during a game, would take the focus from the game for a moment. It then stays open,
     * empty while the subtitles are off.
     */
    void open() {
        owner.show();
        stage.show();
        if (!OverlayWindowStyle.apply(TITLE, true) && System.getProperty("os.name").startsWith("Windows")) {
            System.err.println("Subtitles: could not make the window click-through.");
        }
    }

    /** Shows or hides the subtitles. */
    void setActive(boolean active) {
        this.active = active;
        if (!active && !moving) clear();
        lines.setVisible(active || moving);
    }

    boolean isActive() {
        return active;
    }

    /** Move mode: the window takes the mouse and shows its area, so it can be dragged. */
    void setMoving(boolean moving) {
        if (moving == this.moving) return;
        this.moving = moving;
        OverlayWindowStyle.apply(TITLE, !moving);
        moveHint.setVisible(moving);
        root.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("moving"), moving);
        if (moving) {
            lines.setVisible(true);
            if (shown.isEmpty()) {
                Line sample = new Line("sample", "这是字幕的位置和大小。", "This is where the subtitles appear.", true);
                sample.keep();
            }
        } else {
            shown.remove("sample");
            lines.getChildren().removeIf(n -> "sample".equals(n.getId()));
            double x = stage.getX() + stage.getWidth() / 2;
            double y = stage.getY() + stage.getHeight();
            style = new Style(style.nativeLanguage(), style.fontSize(), style.showOriginal(), style.holdSeconds(), x, y);
            onMoved.accept(x, y);
            if (!active) clear();
            lines.setVisible(active);
        }
    }

    boolean isMoving() {
        return moving;
    }

    /** A line was recognized: shown at once, in the original language, if it is someone else's. */
    void onUtterance(Utterance u) {
        if (!active || u.source() != Source.REMOTE) return;
        boolean waiting = TranslatorSession.autoTranslates(u, style.nativeLanguage());
        new Line(u.id(), null, u.text(), !waiting);
    }

    void onTranslation(Utterance u, TranslationResult r) {
        if (!active || u.source() != Source.REMOTE) return;
        Line line = shown.get(u.id());
        if (line == null) line = new Line(u.id(), null, u.text(), false); // already faded, or translated by hand
        line.translated(r.text());
    }

    /** For the UI preview. */
    Scene scene() {
        return stage.getScene();
    }

    void clear() {
        shown.clear();
        lines.getChildren().clear();
    }

    void close() {
        stage.hide();
        owner.hide();
    }

    /**
     * Places the window: 60 % of its screen wide, centred on {@code x}, its bottom edge at {@code y}.
     * A saved position on a screen that is no longer there falls back to the main screen.
     */
    private void layout() {
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double cx = screen.getMinX() + screen.getWidth() / 2;
        double bottom = screen.getMinY() + screen.getHeight() * 0.88;
        if (style.x() != null && style.y() != null) {
            var hit = Screen.getScreensForRectangle(style.x(), style.y() - 1, 1, 1);
            if (!hit.isEmpty()) {
                screen = hit.getFirst().getVisualBounds();
                cx = style.x();
                bottom = style.y();
            }
        }
        double w = Math.min(screen.getWidth() * 0.6, 60 * style.fontSize());
        double h = Math.min(style.fontSize() * 2.6 * MAX_LINES * (style.showOriginal() ? 1.6 : 1) + 40, screen.getHeight() * 0.6);
        stage.setWidth(w);
        stage.setHeight(h);
        lines.setMaxWidth(w - 16);
        stage.setX(Math.clamp(cx - w / 2, screen.getMinX(), screen.getMaxX() - w));
        stage.setY(Math.clamp(bottom - h, screen.getMinY(), screen.getMaxY() - h));
    }

    /** One subtitle: the translation (large) and the original (small, optional). */
    private final class Line {
        final String id;
        final Label main = new Label();
        final Label original = new Label();
        final VBox box = new VBox(2, main, original);
        final String originalText;
        final SequentialTransition fade;
        boolean finalText;

        /** @param translation null while waiting; {@code done} when no translation will come */
        Line(String id, String translation, String originalText, boolean done) {
            this.id = id;
            this.originalText = originalText;
            box.setId(id);
            box.getStyleClass().add("subtitle");
            box.setAlignment(Pos.CENTER);
            box.setMaxWidth(Region.USE_PREF_SIZE);
            main.setWrapText(true);
            original.setWrapText(true);
            main.getStyleClass().add("subtitle-text");
            original.getStyleClass().add("subtitle-original");
            PauseTransition hold = new PauseTransition();
            FadeTransition out = new FadeTransition(Duration.millis(400), box);
            out.setToValue(0);
            fade = new SequentialTransition(hold, out);
            fade.setOnFinished(e -> remove());
            if (translation != null) translated(translation);
            else {
                finalText = done;
                main.setText(originalText);
                restyle();
            }
            shown.put(id, this);
            lines.getChildren().add(box);
            while (shown.size() > MAX_LINES) shown.values().iterator().next().remove();
            restartTimer();
        }

        void translated(String text) {
            finalText = true;
            main.setText(text);
            original.setText(originalText);
            restyle();
            restartTimer();
        }

        void restyle() {
            main.setStyle(String.format(Locale.ROOT, "-fx-font-size: %.0fpx;", style.fontSize()));
            original.setStyle(String.format(Locale.ROOT, "-fx-font-size: %.0fpx;", Math.max(11, style.fontSize() * 0.55)));
            main.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("waiting"), !finalText);
            boolean showOriginal = style.showOriginal() && finalText && !originalText.equals(main.getText());
            original.setVisible(showOriginal);
            original.setManaged(showOriginal);
        }

        void restartTimer() {
            fade.stop();
            box.setOpacity(1);
            ((PauseTransition) fade.getChildren().getFirst()).setDuration(Duration.seconds(style.holdSeconds()));
            fade.playFromStart();
        }

        /** Stays until move mode ends (the sample line). */
        void keep() {
            fade.stop();
        }

        void remove() {
            fade.stop();
            shown.remove(id, this);
            lines.getChildren().remove(box);
        }
    }
}
