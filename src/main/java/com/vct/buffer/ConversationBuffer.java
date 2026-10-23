package com.vct.buffer;

import com.vct.model.Source;
import com.vct.model.Utterance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Bounded, thread-safe rolling buffer of recent utterances. Bounded both by count and by age so
 * memory stays flat during long sessions.
 */
public final class ConversationBuffer {

    private final int maxItems;
    private final Duration maxAge;
    private final Clock clock;
    private final ArrayDeque<Utterance> items = new ArrayDeque<>();

    public ConversationBuffer(int maxItems, Duration maxAge, Clock clock) {
        if (maxItems < 1) throw new IllegalArgumentException("maxItems must be >= 1");
        this.maxItems = maxItems;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    public synchronized void add(Utterance u) {
        items.addLast(u);
        evict();
    }

    /**
     * The most recent utterance from {@code source} that started before {@code cutoff}. Using the
     * start time means a line that was still being spoken when the user pressed the trigger counts.
     */
    public synchronized Optional<Utterance> latestStartedBefore(Source source, Instant cutoff) {
        evict();
        Iterator<Utterance> it = items.descendingIterator();
        while (it.hasNext()) {
            Utterance u = it.next();
            if (u.source() == source && u.start().isBefore(cutoff)) return Optional.of(u);
        }
        return Optional.empty();
    }

    /** The utterance from the same source immediately preceding {@code id} (for "go back one"). */
    public synchronized Optional<Utterance> previous(String id) {
        Utterance target = null;
        Iterator<Utterance> it = items.descendingIterator();
        while (it.hasNext()) {
            Utterance u = it.next();
            if (target == null) {
                if (u.id().equals(id)) target = u;
            } else if (u.source() == target.source()) {
                return Optional.of(u);
            }
        }
        return Optional.empty();
    }

    /** Up to {@code n} utterances from any source that precede {@code id}, oldest first. */
    public synchronized List<Utterance> contextBefore(String id, int n) {
        List<Utterance> out = new ArrayList<>();
        boolean found = false;
        Iterator<Utterance> it = items.descendingIterator();
        while (it.hasNext() && out.size() < n) {
            Utterance u = it.next();
            if (found) out.add(u);
            else if (u.id().equals(id)) found = true;
        }
        Collections.reverse(out);
        return out;
    }

    public synchronized List<Utterance> snapshot() {
        evict();
        return List.copyOf(items);
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized void clear() {
        items.clear();
    }

    private void evict() {
        while (items.size() > maxItems) items.removeFirst();
        Instant oldest = clock.instant().minus(maxAge);
        while (!items.isEmpty() && items.peekFirst().end().isBefore(oldest)) items.removeFirst();
    }
}
