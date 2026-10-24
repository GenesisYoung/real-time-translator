package com.vct.capture;

import java.util.Locale;

/** Where the other participants' voices come from. */
public enum RemoteSource {
    /** Everything except this app: works with any voice chat, includes game sound. */
    OTHERS("All audio except this app"),
    /** Only the Discord app (its whole process tree). */
    DISCORD("Discord only"),
    /** Everything the default output device plays (fallback). */
    SYSTEM("Everything the default output plays");

    private final String label;

    RemoteSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The capture target, resolved again on every (re)connect, so a restarted Discord is found. */
    public CaptureLoop.TargetResolver resolver() {
        return switch (this) {
            case DISCORD -> DiscordLocator.resolver();
            case SYSTEM -> CaptureLoop.TargetResolver.of(new CaptureTarget.Loopback(null));
            case OTHERS -> CaptureLoop.TargetResolver.of(
                    new CaptureTarget.ProcessTree(ProcessHandle.current().pid(), CaptureTarget.ProcessTree.Mode.EXCLUDE));
        };
    }

    /** Parses "others", "discord" or "system" (case-insensitive). */
    public static RemoteSource parse(String s) {
        try {
            return valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Remote source must be others, discord or system, not \"" + s + "\"");
        }
    }
}
