package com.vct.tts;

/** Speech synthesis could not be set up or failed. The message is meant for the user. */
public final class TtsException extends Exception {

    public TtsException(String message) {
        super(message);
    }
}
