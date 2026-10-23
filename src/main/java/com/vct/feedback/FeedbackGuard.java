package com.vct.feedback;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Remembers what the app itself just said, so that if synthesized speech leaks back into a capture
 * stream (speakers into the mic, or private playback into loopback) it is not transcribed, buffered
 * or treated as a command. This is the last line of defence, behind device/process isolation.
 */
public final class FeedbackGuard {

    private record Spoken(Set<String> tokens, Instant start, Instant end) {}

    private final Clock clock;
    private final Duration grace;
    private final double threshold;
    private final ConcurrentLinkedDeque<Spoken> spoken = new ConcurrentLinkedDeque<>();

    public FeedbackGuard(Clock clock, Duration grace, double threshold) {
        this.clock = clock;
        this.grace = grace;
        this.threshold = threshold;
    }

    public static FeedbackGuard defaults(Clock clock) {
        return new FeedbackGuard(clock, Duration.ofMillis(1500), 0.6);
    }

    /** Registers text the app is about to play during [start, end]. */
    public void register(String text, Instant start, Instant end) {
        Set<String> t = tokens(text);
        if (!t.isEmpty()) spoken.addLast(new Spoken(t, start, end));
        prune();
    }

    /** True if text heard during [start, end] matches something the app was playing at that time. */
    public boolean isEcho(String text, Instant start, Instant end) {
        prune();
        Set<String> heard = tokens(text);
        if (heard.isEmpty()) return false;
        for (Spoken s : spoken) {
            boolean overlaps = !start.isAfter(s.end().plus(grace)) && !end.isBefore(s.start().minus(grace));
            if (!overlaps) continue;
            Set<String> common = new HashSet<>(heard);
            common.retainAll(s.tokens());
            int smaller = Math.min(heard.size(), s.tokens().size());
            if (smaller == 1 ? common.size() == 1 && heard.size() == 1
                    : (double) common.size() / smaller >= threshold) {
                return true;
            }
        }
        return false;
    }

    private void prune() {
        Instant cutoff = clock.instant().minus(Duration.ofSeconds(60));
        Iterator<Spoken> it = spoken.iterator();
        while (it.hasNext()) {
            if (it.next().end().isBefore(cutoff)) it.remove();
        }
    }

    static Set<String> tokens(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Set<String> out = new HashSet<>(Arrays.asList(s.split("[^\\p{L}\\p{N}']+")));
        out.remove("");
        return out;
    }
}
