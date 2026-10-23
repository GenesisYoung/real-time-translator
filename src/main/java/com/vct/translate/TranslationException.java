package com.vct.translate;

import java.time.Duration;

/** A classified provider failure, so the UI can show a meaningful message and the service can retry. */
public final class TranslationException extends Exception {

    public enum Kind {
        AUTH(false, "API key rejected. Check the key in Settings."),
        BAD_REQUEST(false, "The provider rejected the request (check the selected model)."),
        RATE_LIMIT(true, "Rate limited by the provider."),
        SERVER(true, "The provider had a server error."),
        TIMEOUT(true, "The provider did not answer in time."),
        NETWORK(true, "Network error while contacting the provider."),
        BAD_RESPONSE(false, "The provider returned an unusable response."),
        NOT_CONFIGURED(false, "No API key configured."),
        CANCELLED(false, "Cancelled.");

        private final boolean retryable;
        private final String userMessage;

        Kind(boolean retryable, String userMessage) {
            this.retryable = retryable;
            this.userMessage = userMessage;
        }

        public boolean retryable() {
            return retryable;
        }

        public String userMessage() {
            return userMessage;
        }
    }

    private final Kind kind;
    private final Duration retryAfter;

    public TranslationException(Kind kind, String detail) {
        this(kind, detail, null, null);
    }

    public TranslationException(Kind kind, String detail, Duration retryAfter, Throwable cause) {
        super(kind.userMessage() + (detail == null || detail.isBlank() ? "" : " (" + detail + ")"), cause);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    /** Server-suggested wait before retrying, or null. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
