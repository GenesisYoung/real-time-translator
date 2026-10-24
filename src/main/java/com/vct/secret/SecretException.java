package com.vct.secret;

/** A secret could not be read or written. The message never contains the secret itself. */
public final class SecretException extends Exception {

    public SecretException(String message) {
        super(message);
    }
}
