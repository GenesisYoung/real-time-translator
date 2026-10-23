package com.vct.session;

import com.vct.model.Utterance;
import com.vct.translate.TranslationResult;

/**
 * Session events for the UI. Called from worker threads: UI implementations must hop to the
 * JavaFX thread themselves (Platform.runLater).
 */
public interface SessionListener {

    default void onUtterance(Utterance u) {}

    default void onTranslationStarted(Utterance u) {}

    default void onTranslation(Utterance u, TranslationResult r) {}

    default void onPlaybackStarted(Utterance u) {}

    default void onPlaybackFinished(Utterance u) {}

    /** A segment was dropped because recognition fell behind (CPU overloaded). */
    default void onSegmentDropped() {}

    /** Non-fatal problem; the session keeps running. */
    default void onError(String message, Throwable cause) {}
}
