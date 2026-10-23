package com.vct.audio;

/** A playback destination (private headphones now; virtual microphone in Phase 2). */
public interface AudioSink {

    /** Plays the clip and blocks until playback finishes or {@link #stop()} is called. */
    void play(AudioClip clip) throws InterruptedException;

    /** Stops the clip currently playing (not later ones). Safe to call from any thread. */
    void stop();
}
