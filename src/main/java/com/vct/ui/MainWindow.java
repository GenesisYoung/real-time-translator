package com.vct.ui;

import com.vct.app.SessionController;
import com.vct.capture.CaptureLoop;
import com.vct.model.Source;
import com.vct.model.Utterance;
import com.vct.translate.TranslationResult;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The main window: session controls, capture status and the history of recognized lines. Receives
 * controller events on any thread and applies them on the JavaFX thread.
 */
final class MainWindow implements SessionController.Listener {

    /** What the buttons and the history do; implemented by {@link TranslatorApp}. */
    interface Actions {
        void startStop();

        void translateLatest();

        void replay();

        void cancel();

        void openSettings();

        void translate(Utterance u, boolean force);

        /** Pauses or resumes "translate my speech". */
        void toggleRelay();
    }

    private static final int MAX_HISTORY = 300;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Stage stage;
    private final ObservableList<HistoryItem> history = FXCollections.observableArrayList();
    private final ListView<HistoryItem> list = new ListView<>(history);
    private final Button startStop = new Button("Start");
    private final Button translate = new Button("Translate latest");
    private final Button replay = new Button("Replay");
    private final Label status = new Label("Stopped");
    private final Indicator mic = new Indicator("Mic");
    private final Indicator remote = new Indicator("Others");
    private final Indicator relay = new Indicator("My voice: off");
    private final Button relayButton = new Button("Pause my voice");
    private String pauseKey = "";
    private boolean relayPaused;
    /** Keep showing the newest line; false while the user has scrolled up to read older ones. */
    private boolean following = true;
    private final Label notice = new Label();

    MainWindow(Stage stage, Actions actions) {
        this.stage = stage;
        Button cancel = new Button("Cancel");
        Button settings = new Button("Settings...");
        startStop.setDefaultButton(true);
        startStop.setOnAction(e -> actions.startStop());
        translate.setOnAction(e -> actions.translateLatest());
        replay.setOnAction(e -> actions.replay());
        cancel.setOnAction(e -> actions.cancel());
        cancel.setTooltip(new Tooltip("Stop the translation in progress and the voice"));
        settings.setOnAction(e -> actions.openSettings());
        relayButton.setOnAction(e -> actions.toggleRelay());
        relayButton.setDisable(true);
        relayButton.setTooltip(new Tooltip("While paused, nothing you say is sent to the voice chat"));
        translate.setDisable(true);
        replay.setDisable(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, startStop, translate, replay, cancel, relayButton, spacer, settings);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("status");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox statusRow = new HBox(16, status, gap, mic.node, remote.node, relay.node);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        list.setPlaceholder(new Label("Recognized speech appears here.\nPress Start, then Translate (or the hotkey) after someone speaks."));
        list.setCellFactory(v -> new HistoryCell(actions));
        // Only the user's own scrolling changes whether new lines are followed (like a chat window).
        Runnable userScrolled = () -> Platform.runLater(() -> following = atBottom());
        list.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> userScrolled.run());
        list.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> userScrolled.run());
        list.addEventFilter(javafx.scene.input.KeyEvent.KEY_RELEASED, e -> userScrolled.run());
        VBox.setVgrow(list, Priority.ALWAYS);

        notice.getStyleClass().add("notice");
        notice.setWrapText(true);

        VBox top = new VBox(10, toolbar, statusRow);
        BorderPane root = new BorderPane(list, top, null, notice, null);
        BorderPane.setMargin(list, new Insets(10, 0, 10, 0));
        root.setPadding(new Insets(12));
        Scene scene = new Scene(root, 960, 600); // wide enough for all toolbar buttons with their hotkeys
        scene.getStylesheets().add(MainWindow.class.getResource("app.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("Voice Chat Translator");
        stage.setMinWidth(560);
        stage.setMinHeight(360);
    }

    Stage stage() {
        return stage;
    }

    void setHotkeyHints(String translateKey, String replayKey, String pauseKey) {
        translate.setText("Translate latest  (" + translateKey + ")");
        replay.setText("Replay  (" + replayKey + ")");
        this.pauseKey = pauseKey;
        updateRelayButton();
    }

    private void updateRelayButton() {
        relayButton.setText((relayPaused ? "Resume my voice" : "Pause my voice") + (pauseKey.isEmpty() ? "" : "  (" + pauseKey + ")"));
    }

    void setRemoteLabel(String label) {
        remote.name.setText(label);
    }

    void showNotice(String text, boolean error) {
        runOnFx(() -> {
            notice.setText(text);
            notice.getStyleClass().removeAll("error");
            if (error) notice.getStyleClass().add("error");
        });
    }

    // ------------------------------------------------------------------ controller events

    @Override
    public void onStatus(SessionController.Status s, String detail) {
        runOnFx(() -> {
            status.setText(detail);
            boolean running = s == SessionController.Status.RUNNING;
            boolean busy = s == SessionController.Status.LOADING || s == SessionController.Status.STOPPING;
            startStop.setText(running || s == SessionController.Status.STOPPING ? "Stop" : "Start");
            startStop.setDisable(busy);
            translate.setDisable(!running);
            replay.setDisable(!running);
            if (!running) {
                mic.set(null, null);
                remote.set(null, null);
            }
        });
    }

    @Override
    public void onCaptureState(Source source, CaptureLoop.State state, String detail) {
        runOnFx(() -> (source == Source.MIC ? mic : remote).set(state, detail));
    }

    @Override
    public void onRelayState(boolean active, boolean paused, String targetLanguage) {
        runOnFx(() -> {
            String lang = targetLanguage == null ? "" : java.util.Locale.forLanguageTag(targetLanguage).getDisplayLanguage(Locale.ENGLISH);
            relay.name.setText(active ? "My voice → " + lang : "My voice: off");
            relay.show(!active ? "off" : paused ? "warn" : "ok", !active ? "Translate my speech is off"
                    : paused ? "Paused: nothing you say is sent" : "Everything you say is sent in " + lang);
            relayButton.setDisable(!active);
            relayPaused = paused;
            updateRelayButton();
        });
    }

    @Override
    public void onRelayTranslation(Utterance u, TranslationResult r) {
        runOnFx(() -> find(u).ifPresent(i -> {
            i.translation.set(r.text());
            i.note.set("sending to voice chat...");
            if (following) followBottom(); // the cell grew by a line
        }));
    }

    @Override
    public void onRelaySent(Utterance u) {
        runOnFx(() -> find(u).ifPresent(i -> i.note.set("sent to voice chat")));
    }

    @Override
    public void onRelaySkipped(Utterance u, String reason) {
        runOnFx(() -> find(u).ifPresent(i -> i.note.set(reason)));
    }

    @Override
    public void onRelayError(Utterance u, String message) {
        runOnFx(() -> find(u).ifPresent(i -> i.note.set("not sent")));
        showNotice(message, true);
    }

    @Override
    public void onUtterance(Utterance u) {
        runOnFx(() -> {
            history.add(new HistoryItem(u));
            if (history.size() > MAX_HISTORY) history.removeFirst();
            if (following) followBottom();
        });
    }

    @Override
    public void onTranslationStarted(Utterance u) {
        runOnFx(() -> find(u).ifPresent(i -> i.note.set("Translating...")));
    }

    @Override
    public void onTranslation(Utterance u, TranslationResult r) {
        runOnFx(() -> find(u).ifPresent(i -> {
            i.translation.set(r.text());
            i.note.set(String.format(Locale.ROOT, "%s %s, %d ms%s", r.provider(), r.model(), r.latency().toMillis(),
                    r.fromCache() ? ", cached" : ""));
            if (following) followBottom(); // the cell grew by a line
        }));
    }

    @Override
    public void onPlaybackStarted(Utterance u) {
        runOnFx(() -> status.setText("Speaking the translation..."));
    }

    @Override
    public void onPlaybackFinished(Utterance u) {
        runOnFx(() -> status.setText("Listening"));
    }

    @Override
    public void onSegmentDropped() {
        showNotice("Speech recognition fell behind; one line was skipped.", false);
    }

    @Override
    public void onNotice(String message) {
        showNotice(message, false);
    }

    @Override
    public void onError(String message, Throwable cause) {
        showNotice(message, true);
    }

    /** True when the newest line is visible, i.e. the user is not reading older lines. */
    private boolean atBottom() {
        VirtualFlow<?> flow = (VirtualFlow<?>) list.lookup(".virtual-flow");
        if (flow == null || history.isEmpty()) return true;
        IndexedCell<?> last = flow.getLastVisibleCell();
        return last == null || last.getIndex() >= history.size() - 1;
    }

    /**
     * Shows the newest line, after the next layout pass so the cell has its real height.
     * VirtualFlow.scrollTo scrolls only as far as needed; ListView.scrollTo puts the line at the top,
     * which left the list scrolled past its end, showing only empty rows (seen in the preview).
     */
    private void followBottom() {
        Platform.runLater(() -> {
            VirtualFlow<?> flow = (VirtualFlow<?>) list.lookup(".virtual-flow");
            if (flow != null && !history.isEmpty()) flow.scrollTo(history.size() - 1);
        });
    }

    private java.util.Optional<HistoryItem> find(Utterance u) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).utterance.id().equals(u.id())) return java.util.Optional.of(history.get(i));
        }
        return java.util.Optional.empty();
    }

    static void runOnFx(Runnable r) {
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }

    // ------------------------------------------------------------------ parts

    /** A coloured dot with a name: grey stopped, green running, orange reconnecting. */
    private static final class Indicator {
        final Circle dot = new Circle(5);
        final Label name;
        final HBox node;
        final Tooltip tip = new Tooltip("Not running");

        Indicator(String label) {
            name = new Label(label);
            node = new HBox(6, dot, name);
            node.setAlignment(Pos.CENTER_LEFT);
            Tooltip.install(node, tip);
            set(null, null);
        }

        void set(CaptureLoop.State state, String detail) {
            show(state == null ? "off" : switch (state) {
                case RUNNING -> "ok";
                case STARTING, RECONNECTING -> "warn";
                case STOPPED -> "off";
            }, state == null ? "Not running" : state + (detail == null ? "" : ": " + detail));
        }

        /** @param style "ok", "warn" or "off" */
        void show(String style, String tooltip) {
            dot.getStyleClass().setAll("dot", style);
            tip.setText(tooltip);
        }
    }

    /** Two or three lines: who and when, what was said, and the translation. */
    private static final class HistoryCell extends ListCell<HistoryItem> {
        private final Label header = new Label();
        private final Label text = new Label();
        private final Label translation = new Label();
        private final VBox box = new VBox(2, header, text, translation);
        private final Actions actions;

        HistoryCell(Actions actions) {
            this.actions = actions;
            header.getStyleClass().add("line-header");
            text.getStyleClass().add("line-text");
            translation.getStyleClass().add("line-translation");
            text.setWrapText(true);
            translation.setWrapText(true);
            text.maxWidthProperty().bind(widthProperty().subtract(30));
            translation.maxWidthProperty().bind(widthProperty().subtract(30));
            setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && getItem() != null) actions.translate(getItem().utterance, false);
            });
        }

        @Override
        protected void updateItem(HistoryItem item, boolean empty) {
            super.updateItem(item, empty);
            // Cells are reused for different items: drop the previous item's bindings first.
            header.textProperty().unbind();
            translation.textProperty().unbind();
            translation.visibleProperty().unbind();
            translation.managedProperty().unbind();
            if (empty || item == null) {
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            Utterance u = item.utterance;
            header.textProperty().bind(item.note.map(n -> TIME.format(u.start()) + "   "
                    + (u.source() == Source.MIC ? "you" : "others") + "   " + (u.language() == null ? "?" : u.language())
                    + (u.lowConfidence() ? "   (unclear)" : "") + (n.isEmpty() ? "" : "   ·   " + n)));
            text.setText(u.text());
            translation.textProperty().bind(item.translation);
            translation.visibleProperty().bind(item.translation.isNotEmpty());
            translation.managedProperty().bind(item.translation.isNotEmpty());
            getStyleClass().removeAll("mine");
            if (u.source() == Source.MIC) getStyleClass().add("mine");

            MenuItem tr = new MenuItem("Translate");
            tr.setOnAction(e -> actions.translate(u, false));
            MenuItem retry = new MenuItem("Translate again (new request)");
            retry.setOnAction(e -> actions.translate(u, true));
            MenuItem copy = new MenuItem("Copy");
            copy.setOnAction(e -> {
                ClipboardContent c = new ClipboardContent();
                c.putString(item.translation.get().isEmpty() ? u.text() : u.text() + "\n" + item.translation.get());
                Clipboard.getSystemClipboard().setContent(c);
            });
            setContextMenu(new ContextMenu(tr, retry, copy));
            setGraphic(box);
        }
    }
}
