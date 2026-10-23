package com.vct.buffer;

import com.vct.model.Source;
import com.vct.model.Utterance;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationBufferTest {

    static final Instant T0 = Instant.parse("2026-10-22T18:00:00Z");

    static Utterance u(String id, Source s, int startSec, int endSec) {
        return new Utterance(id, s, T0.plusSeconds(startSec), T0.plusSeconds(endSec), id, "en", 1, 1, false);
    }

    static ConversationBuffer buffer(int max, Instant now) {
        return new ConversationBuffer(max, Duration.ofMinutes(5), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void picksLatestRemoteStartedBeforeTrigger() {
        var b = buffer(10, T0.plusSeconds(30));
        b.add(u("r1", Source.REMOTE, 0, 2));
        b.add(u("m1", Source.MIC, 3, 4));
        b.add(u("r2", Source.REMOTE, 5, 9));   // still being spoken at trigger time 8
        b.add(u("r3", Source.REMOTE, 10, 12)); // started after the trigger
        assertEquals("r2", b.latestStartedBefore(Source.REMOTE, T0.plusSeconds(8)).orElseThrow().id());
        assertEquals("m1", b.latestStartedBefore(Source.MIC, T0.plusSeconds(8)).orElseThrow().id());
        assertTrue(b.latestStartedBefore(Source.REMOTE, T0).isEmpty());
    }

    @Test
    void boundedByCountAndAge() {
        var b = buffer(3, T0.plus(Duration.ofMinutes(6)));
        b.add(u("old", Source.REMOTE, 0, 1));          // older than 5 min -> evicted
        for (int i = 0; i < 5; i++) b.add(u("n" + i, Source.REMOTE, 400 + i, 401 + i));
        assertEquals(List.of("n2", "n3", "n4"), b.snapshot().stream().map(Utterance::id).toList());
    }

    @Test
    void previousAndContextNavigation() {
        var b = buffer(10, T0.plusSeconds(30));
        b.add(u("r1", Source.REMOTE, 0, 1));
        b.add(u("m1", Source.MIC, 2, 3));
        b.add(u("r2", Source.REMOTE, 4, 5));
        assertEquals("r1", b.previous("r2").orElseThrow().id());
        assertTrue(b.previous("r1").isEmpty());
        assertEquals(List.of("r1", "m1"), b.contextBefore("r2", 5).stream().map(Utterance::id).toList());
        assertEquals(List.of("m1"), b.contextBefore("r2", 1).stream().map(Utterance::id).toList());
    }
}
