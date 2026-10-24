package com.vct.ui;

import com.vct.model.Utterance;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** One recognized line in the history, with its translation once there is one. Used on the FX thread. */
final class HistoryItem {

    final Utterance utterance;
    final StringProperty translation = new SimpleStringProperty("");
    final StringProperty note = new SimpleStringProperty("");

    HistoryItem(Utterance utterance) {
        this.utterance = utterance;
    }
}
