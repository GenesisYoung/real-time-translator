package com.vct.model;

/** Where an utterance came from. Loopback audio is a mix, so we never claim more than "remote". */
public enum Source {
    /** The user's own microphone. */
    MIC("me"),
    /** System / application loopback audio (other participants, possibly mixed with game sound). */
    REMOTE("remote");

    private final String label;

    Source(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
