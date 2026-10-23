package com.vct.feedback;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackGuardTest {

    static final Instant T0 = Instant.parse("2026-10-22T18:00:00Z");
    final FeedbackGuard g = FeedbackGuard.defaults(Clock.fixed(T0.plusSeconds(5), ZoneOffset.UTC));

    @Test
    void detectsOwnSpeechHeardDuringPlayback() {
        g.register("We need to go around the left.", T0, T0.plusSeconds(2));
        assertTrue(g.isEcho("we need to go around the left", T0.plusMillis(100), T0.plusSeconds(2)));
        // partial / misrecognized echo still matches
        assertTrue(g.isEcho("need to go round the left", T0.plusMillis(500), T0.plusSeconds(2)));
    }

    @Test
    void ignoresDifferentTextOrNonOverlappingTime() {
        g.register("We need to go around the left.", T0, T0.plusSeconds(2));
        assertFalse(g.isEcho("Who has the bomb?", T0, T0.plusSeconds(1)));
        assertFalse(g.isEcho("we need to go around the left", T0.plusSeconds(10), T0.plusSeconds(12)));
    }
}
