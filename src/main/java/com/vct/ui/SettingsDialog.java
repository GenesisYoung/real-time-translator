package com.vct.ui;

import com.vct.app.AppSettings;
import com.vct.capture.AudioDevice;
import com.vct.capture.RemoteSource;
import com.vct.hotkey.Hotkey;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Edits {@link AppSettings} and the API key. Device and model lists load in the background. */
final class SettingsDialog {

    /**
     * @param newApiKey a key typed in the dialog, to save in Credential Manager
     * @param removeKey the user asked to delete the saved key
     * @param startWithWindows the "start with Windows" choice (kept by Windows, not in the settings file)
     */
    record Result(AppSettings settings, Optional<String> newApiKey, boolean removeKey, boolean startWithWindows) {}

    /** A device choice; {@code id} null is "Windows default". */
    private record Choice(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final Choice DEFAULT_MIC = new Choice(null, "Windows default microphone");
    private static final Choice DEFAULT_OUTPUT = new Choice(null, "Windows default output");

    private SettingsDialog() {}

    /**
     * @param microphones  loads the capture devices (runs in the background)
     * @param outputs      output device names for translations
     * @param modelLister  lists OpenAI models for these settings and key (runs in the background)
     */
    static Optional<Result> show(Window owner, AppSettings s, boolean hasSavedKey,
                                 CompletableFuture<List<AudioDevice>> microphones, List<String> outputs,
                                 Function<String, CompletableFuture<List<String>>> modelLister) {
        Dialog<Result> d = new Dialog<>();
        d.initOwner(owner);
        d.setTitle("Settings");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().getStylesheets().add(SettingsDialog.class.getResource("app.css").toExternalForm());

        // Audio
        ComboBox<RemoteSource> remote = new ComboBox<>();
        remote.getItems().setAll(RemoteSource.values());
        remote.setValue(s.remoteSource());
        remote.setConverter(new StringConverter<>() {
            @Override public String toString(RemoteSource r) { return r == null ? "" : r.label(); }
            @Override public RemoteSource fromString(String t) { return null; }
        });
        ComboBox<Choice> mic = new ComboBox<>();
        mic.getItems().add(DEFAULT_MIC);
        mic.setValue(DEFAULT_MIC);
        mic.setPromptText("Loading microphones...");
        microphones.whenComplete((list, e) -> MainWindow.runOnFx(() -> {
            if (e != null) {
                mic.setPromptText("Could not list microphones");
                return;
            }
            for (AudioDevice dev : list) mic.getItems().add(new Choice(dev.id(), dev.name() + (dev.isDefault() ? "  (default)" : "")));
            mic.getItems().stream().filter(c -> c.id() != null && c.id().equals(s.micDeviceId())).findFirst().ifPresent(mic::setValue);
        }));
        ComboBox<Choice> output = new ComboBox<>();
        output.getItems().add(DEFAULT_OUTPUT);
        outputs.forEach(o -> output.getItems().add(new Choice(o, o)));
        output.setValue(output.getItems().stream().filter(c -> c.id() != null && c.id().equals(s.outputDevice()))
                .findFirst().orElse(DEFAULT_OUTPUT));

        // Translation
        PasswordField key = new PasswordField();
        key.setPromptText(hasSavedKey ? "Saved. Type a new key to replace it." : "sk-...");
        CheckBox removeKey = new CheckBox("Remove the saved key");
        removeKey.setDisable(!hasSavedKey);
        ComboBox<String> model = new ComboBox<>();
        model.setEditable(true);
        model.setValue(s.openAiModel());
        Button loadModels = new Button("Load list");
        Label modelHint = hint("A fast model that doesn't reason first, e.g. gpt-4.1-mini.");
        loadModels.setOnAction(e -> {
            loadModels.setDisable(true);
            modelHint.setText("Loading...");
            String typedKey = key.getText().isBlank() ? null : key.getText().strip();
            modelLister.apply(typedKey).whenComplete((list, err) -> MainWindow.runOnFx(() -> {
                loadModels.setDisable(false);
                if (err != null) {
                    modelHint.setText("Could not load: " + rootMessage(err));
                    return;
                }
                String current = model.getEditor().getText();
                model.getItems().setAll(list.stream().sorted().toList());
                model.setValue(current);
                modelHint.setText(list.size() + " models available.");
            }));
        });
        TextField languages = new TextField(String.join(",", s.recognizedLanguages()));
        TextField nativeLanguage = new TextField(s.nativeLanguage());

        // Speech engines
        TextField whisper = new TextField(s.whisperModel());
        CheckBox gpu = new CheckBox("Use the GPU (CUDA)");
        gpu.setSelected(s.whisperGpu());
        TextField voice = new TextField(s.ttsVoice());
        Slider speed = new Slider(0.5, 2.0, s.ttsSpeed());
        speed.setMajorTickUnit(0.25);
        speed.setSnapToTicks(true);
        Label speedValue = new Label();
        speedValue.textProperty().bind(speed.valueProperty().map(v -> String.format(Locale.ROOT, "%.2fx", v.doubleValue())));

        // Hotkeys
        TextField translateKey = new TextField(s.translateHotkey());
        TextField replayKey = new TextField(s.replayHotkey());
        TextField pauseKey = new TextField(s.relay().pauseHotkey());
        TextField liveKey = new TextField(s.relay().liveHotkey());
        TextField subtitlesKey = new TextField(s.subtitles().hotkey());

        // Subtitles
        AppSettings.SubtitleSettings st = s.subtitles();
        CheckBox subtitlesOn = new CheckBox("Show what the others say as subtitles over the game");
        subtitlesOn.setSelected(st.enabled());
        CheckBox subtitlesSpeak = new CheckBox("Also read them aloud on my headphones");
        subtitlesSpeak.setSelected(st.speak());
        CheckBox subtitlesOriginal = new CheckBox("Show the original under the translation");
        subtitlesOriginal.setSelected(st.showOriginal());
        Slider fontSize = new Slider(16, 48, st.fontSize());
        fontSize.setMajorTickUnit(2);
        fontSize.setSnapToTicks(true);
        Label fontSizeValue = new Label();
        fontSizeValue.textProperty().bind(fontSize.valueProperty().map(v -> String.format(Locale.ROOT, "%.0f px", v.doubleValue())));
        Slider hold = new Slider(3, 20, st.holdSeconds());
        hold.setMajorTickUnit(1);
        hold.setSnapToTicks(true);
        Label holdValue = new Label();
        holdValue.textProperty().bind(hold.valueProperty().map(v -> String.format(Locale.ROOT, "%.0f s", v.doubleValue())));
        CheckBox resetPosition = new CheckBox("Put them back in the default place");
        for (Node n : List.of(subtitlesSpeak, subtitlesOriginal, fontSize, hold)) n.disableProperty().bind(subtitlesOn.selectedProperty().not());
        if (st.x() == null) resetPosition.setDisable(true); // already there
        else resetPosition.disableProperty().bind(subtitlesOn.selectedProperty().not());

        // Translate my speech
        AppSettings.RelaySettings rs = s.relay();
        CheckBox relayOn = new CheckBox("Translate everything I say and speak it into the voice chat");
        relayOn.setSelected(rs.enabled());
        ComboBox<Choice> relayTarget = new ComboBox<>();
        relayTarget.getItems().setAll(new Choice("en", "English"), new Choice("ja", "Japanese"));
        relayTarget.setValue(relayTarget.getItems().stream().filter(c -> c.id().equals(rs.targetLanguage())).findFirst()
                .orElse(relayTarget.getItems().getFirst()));
        ComboBox<Choice> relayOutput = new ComboBox<>();
        relayOutput.getItems().add(DEFAULT_OUTPUT);
        outputs.forEach(o -> relayOutput.getItems().add(new Choice(o, o)));
        relayOutput.setValue(matchOutput(relayOutput, rs.outputDevice()));
        ComboBox<String> relayVoice = new ComboBox<>();
        relayVoice.setEditable(true);
        relayVoice.getItems().setAll("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse");
        relayVoice.setValue(rs.voice());
        TextField relayModel = new TextField(rs.ttsModel());
        for (Node n : List.of(relayTarget, relayOutput, relayVoice, relayModel)) n.disableProperty().bind(relayOn.selectedProperty().not());

        // Program
        CheckBox withWindows = new CheckBox("Start with Windows (in the tray)");
        withWindows.setSelected(WindowsStartup.isOn());
        withWindows.setDisable(!WindowsStartup.available());
        CheckBox listenOnStart = new CheckBox("Start listening as soon as the app opens");
        listenOnStart.setSelected(s.listenOnStart());

        Label error = new Label();
        error.getStyleClass().add("error-text");
        error.setWrapText(true);

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(4, 8, 4, 4));
        g.getColumnConstraints().addAll(new ColumnConstraints(150), grow());
        int row = 0;
        row = section(g, row, "Audio");
        row = field(g, row, "Others' voices", wide(remote));
        row = field(g, row, "Your microphone", wide(mic));
        row = field(g, row, "Play translations on", wide(output));
        row = section(g, row, "Translation");
        row = field(g, row, "OpenAI API key", new HBox(10, grown(key), removeKey));
        row = field(g, row, "", hint("Stored in Windows Credential Manager, never in the settings file."));
        row = field(g, row, "OpenAI model", new HBox(10, grown(model), loadModels));
        row = field(g, row, "", modelHint);
        row = field(g, row, "Translate into", nativeLanguage);
        row = field(g, row, "Languages to recognize", languages);
        row = field(g, row, "", hint("Language codes separated by commas, e.g. en,ja,zh."));
        row = section(g, row, "Speech engines");
        row = field(g, row, "Whisper model", browse(whisper, false, "Choose the Whisper model (.bin)"));
        row = field(g, row, "", gpu);
        row = field(g, row, "Voice folder", browse(voice, true, "Choose the voice folder"));
        row = field(g, row, "Speech speed", new HBox(10, grown(speed), speedValue));
        row = section(g, row, "Subtitles over the game");
        row = field(g, row, "", subtitlesOn);
        row = field(g, row, "", hint("Every line of the others is translated as soon as it is recognized (one OpenAI "
                + "request each). The hotkey hides them, and then nothing is translated. The game must run in "
                + "borderless or windowed mode: exclusive full screen hides every other window."));
        row = field(g, row, "", subtitlesSpeak);
        row = field(g, row, "", subtitlesOriginal);
        row = field(g, row, "Text size", new HBox(10, grown(fontSize), fontSizeValue));
        row = field(g, row, "Each line stays", new HBox(10, grown(hold), holdValue));
        row = field(g, row, "Position", resetPosition);
        row = field(g, row, "", hint("To move them, press \"Move subtitles\" in the main window and drag them."));
        row = section(g, row, "Translate my speech");
        row = field(g, row, "", relayOn);
        row = field(g, row, "Others hear", wide(relayTarget));
        row = field(g, row, "Send it to", wide(relayOutput));
        row = field(g, row, "", hint("Choose \"CABLE Input\" and set the voice chat's microphone (in Discord: Settings > "
                + "Voice & Video > Input Device) to \"CABLE Output\". Others then hear only the translation."));
        row = field(g, row, "OpenAI voice", wide(relayVoice));
        row = field(g, row, "OpenAI speech model", relayModel);
        row = section(g, row, "Program");
        row = field(g, row, "", withWindows);
        if (!WindowsStartup.available()) row = field(g, row, "", hint("Only in the installed app (see docs/install.md)."));
        row = field(g, row, "", listenOnStart);
        row = field(g, row, "", hint("Loads the speech engines and starts the session without pressing Start."));
        row = section(g, row, "Hotkeys");
        row = field(g, row, "Translate latest", translateKey);
        row = field(g, row, "Replay", replayKey);
        row = field(g, row, "Pause my voice", pauseKey);
        row = field(g, row, "Talk live (hold)", liveKey);
        row = field(g, row, "Subtitles on/off", subtitlesKey);
        row = field(g, row, "", hint("While you hold it, your real voice goes into the voice chat, not translated: "
                + "for when you speak English or Japanese yourself. Keep all of its keys held while you talk."));
        row = field(g, row, "", hint("For example Ctrl+Alt+T, Shift+F9 or F13. They work while a game has focus."));
        g.add(error, 0, row, 2, 1);

        ScrollPane scroll = new ScrollPane(g);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(640, 720);
        d.getDialogPane().setContent(scroll);

        Node ok = d.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            String problem = validate(List.of(translateKey.getText(), replayKey.getText(), pauseKey.getText(), liveKey.getText(),
                    subtitlesKey.getText()), languages.getText(),
                    nativeLanguage.getText(), model.getEditor().getText(), whisper.getText(), voice.getText());
            if (problem == null) problem = validateRelay(relayOn.isSelected(), relayVoice.getEditor().getText(), relayModel.getText());
            if (problem != null) {
                error.setText(problem);
                e.consume(); // keep the dialog open
            }
        });
        d.setResultConverter(b -> {
            if (b != ButtonType.OK) return null;
            AppSettings out = new AppSettings(remote.getValue(), mic.getValue().id(), output.getValue().id(),
                    nativeLanguage.getText().strip(), splitLanguages(languages.getText()), whisper.getText().strip(),
                    gpu.isSelected(), voice.getText().strip(), Math.round(speed.getValue() * 100) / 100.0,
                    model.getEditor().getText().strip(), Hotkey.parse(translateKey.getText()).toString(),
                    Hotkey.parse(replayKey.getText()).toString(), s.contextLines(),
                    new AppSettings.RelaySettings(relayOn.isSelected(), relayTarget.getValue().id(), relayOutput.getValue().id(),
                            relayModel.getText().strip(), relayVoice.getEditor().getText().strip(),
                            Hotkey.parse(pauseKey.getText()).toString(), Hotkey.parse(liveKey.getText()).toString()),
                    new AppSettings.SubtitleSettings(subtitlesOn.isSelected(), subtitlesSpeak.isSelected(),
                            Math.round(fontSize.getValue()), subtitlesOriginal.isSelected(), (int) Math.round(hold.getValue()),
                            resetPosition.isSelected() ? null : st.x(), resetPosition.isSelected() ? null : st.y(),
                            Hotkey.parse(subtitlesKey.getText()).toString()),
                    listenOnStart.isSelected());
            String typed = key.getText().strip();
            return new Result(out, typed.isEmpty() ? Optional.empty() : Optional.of(typed), removeKey.isSelected() && typed.isEmpty(),
                    withWindows.isSelected());
        });
        return d.showAndWait();
    }

    /** Returns a user-readable problem, or null if everything is valid. */
    static String validate(List<String> hotkeys, String languages,
                           String nativeLanguage, String model, String whisper, String voice) {
        List<Hotkey> keys = new ArrayList<>();
        try {
            for (String k : hotkeys) keys.add(Hotkey.parse(k));
        } catch (IllegalArgumentException e) {
            return "Hotkey: " + e.getMessage();
        }
        if (keys.stream().distinct().count() < keys.size()) return "The hotkeys must all be different.";
        if (splitLanguages(languages).isEmpty()) return "Enter at least one language to recognize.";
        if (nativeLanguage.isBlank()) return "Enter the language to translate into, e.g. zh.";
        if (model.isBlank()) return "Enter an OpenAI model, e.g. gpt-4.1-mini.";
        if (whisper.isBlank() || voice.isBlank()) return "Enter the Whisper model file and the voice folder.";
        return null;
    }

    static String validateRelay(boolean on, String voice, String model) {
        if (!on) return null;
        if (voice.isBlank()) return "Choose an OpenAI voice for Translate my speech, e.g. alloy.";
        if (model.isBlank()) return "Enter an OpenAI speech model, e.g. gpt-4o-mini-tts.";
        return null;
    }

    /** The device whose name contains {@code wanted}; if none does, an entry saying it is missing. */
    private static Choice matchOutput(ComboBox<Choice> box, String wanted) {
        if (wanted == null) return DEFAULT_OUTPUT;
        String w = wanted.toLowerCase(Locale.ROOT);
        return box.getItems().stream().filter(c -> c.id() != null && c.id().toLowerCase(Locale.ROOT).contains(w))
                .findFirst().orElseGet(() -> {
                    Choice missing = new Choice(wanted, wanted + "  (not found: is VB-CABLE installed?)");
                    box.getItems().add(missing);
                    return missing;
                });
    }

    static List<String> splitLanguages(String text) {
        List<String> out = new ArrayList<>();
        Arrays.stream(text.split(",")).map(String::strip).filter(x -> !x.isEmpty()).forEach(out::add);
        return out;
    }

    private static Node browse(TextField field, boolean directory, String title) {
        Button b = new Button("Browse...");
        b.setOnAction(e -> {
            File start = com.vct.util.AppHome.resolve(field.getText().isBlank() ? "." : field.getText()).toFile();
            File chosen;
            if (directory) {
                DirectoryChooser c = new DirectoryChooser();
                c.setTitle(title);
                if (start.isDirectory()) c.setInitialDirectory(start);
                chosen = c.showDialog(b.getScene().getWindow());
            } else {
                FileChooser c = new FileChooser();
                c.setTitle(title);
                if (start.getParentFile() != null && start.getParentFile().isDirectory()) c.setInitialDirectory(start.getParentFile());
                chosen = c.showOpenDialog(b.getScene().getWindow());
            }
            if (chosen != null) field.setText(chosen.getPath());
        });
        return new HBox(10, grown(field), b);
    }

    private static int section(GridPane g, int row, String title) {
        Label l = new Label(title);
        l.getStyleClass().add("settings-section");
        g.add(l, 0, row, 2, 1);
        return row + 1;
    }

    private static int field(GridPane g, int row, String label, Node control) {
        g.add(new Label(label), 0, row);
        g.add(control, 1, row);
        return row + 1;
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("hint");
        l.setWrapText(true);
        return l;
    }

    private static <T extends javafx.scene.layout.Region> T grown(T n) {
        HBox.setHgrow(n, Priority.ALWAYS);
        n.setMaxWidth(Double.MAX_VALUE);
        return n;
    }

    /** Fills the column, so all fields line up. */
    private static <T extends javafx.scene.layout.Region> T wide(T n) {
        n.setMaxWidth(Double.MAX_VALUE);
        return n;
    }

    private static ColumnConstraints grow() {
        ColumnConstraints c = new ColumnConstraints();
        c.setHgrow(Priority.ALWAYS);
        return c;
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
