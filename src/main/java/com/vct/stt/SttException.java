package com.vct.stt;

/** Speech recognition could not be set up or failed. The message is meant for the user. */
public final class SttException extends Exception {

    public SttException(String message) {
        super(message);
    }
}
