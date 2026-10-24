package com.vct.session;

import com.vct.audio.AudioSink;
import com.vct.audio.AudioStream;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * "Talk live": while a key is held, the user's real microphone goes straight into the virtual cable,
 * with no recognition or translation in between.
 *
 * <p>The microphone is always remembered for a short moment ({@code preRoll}), so a word started
 * just before the key press is not cut, and the stream keeps going for {@code tail} after the
 * release, so neither is the last word. The times spent live are kept, so that the recognized lines
 * from them can be left out of the translation.
 */
public final class LiveVoice implements AutoCloseable {

    private enum State { IDLE, OPENING, LIVE }

    /** A time the user was live; {@code to} is null while still live. */
    private record Interval(Instant from, Instant to) {}

    private static final int KEEP_INTERVALS = 20;

    private final AudioSink cable;
    private final int sampleRate;
    private final Duration preRoll;
    private final Duration tail;
    private final Clock clock;

    // Guarded by this.
    private State state = State.IDLE;
    private AudioStream stream;
    private Instant releasedAt;
    private final Deque<float[]> recent = new ArrayDeque<>();
    private int recentSamples;
    private final Deque<Interval> intervals = new ArrayDeque<>();

    public LiveVoice(AudioSink cable, int sampleRate, Duration preRoll, Duration tail, Clock clock) {
        this.cable = cable;
        this.sampleRate = sampleRate;
        this.preRoll = preRoll;
        this.tail = tail;
        this.clock = clock;
    }

    public static LiveVoice defaults(AudioSink cable, int sampleRate, Clock clock) {
        return new LiveVoice(cable, sampleRate, Duration.ofMillis(150), Duration.ofMillis(300), clock);
    }

    /**
     * Starts sending the microphone. Blocks while the device opens (a few tens of milliseconds); the
     * microphone heard meanwhile is sent right after. Pressing again while live just keeps it live.
     *
     * @return false if it was live already
     * @throws IllegalStateException if the cable cannot be opened
     */
    public boolean press() {
        synchronized (this) {
            if (state != State.IDLE) {
                releasedAt = null; // pressed again during the tail
                return false;
            }
            state = State.OPENING;
            releasedAt = null;
            addInterval(new Interval(clock.instant().minus(preRoll), null));
        }
        AudioStream opened;
        try {
            opened = cable.openStream(sampleRate);
        } catch (RuntimeException e) {
            synchronized (this) {
                state = State.IDLE;
                endInterval(clock.instant());
            }
            throw e;
        }
        synchronized (this) {
            for (float[] block : recent) opened.write(block);
            recent.clear();
            recentSamples = 0;
            stream = opened;
            state = State.LIVE;
        }
        return true;
    }

    /** Stops sending after the tail. */
    public synchronized void release() {
        if (state != State.IDLE && releasedAt == null) releasedAt = clock.instant();
    }

    public synchronized boolean isLive() {
        return state != State.IDLE;
    }

    /**
     * Feeds the microphone, as it is captured. Call from the capture thread.
     *
     * @return true if this block ended the live time (the tail is over)
     */
    public boolean onAudio(float[] samples) {
        AudioStream finished = null;
        synchronized (this) {
            switch (state) {
                case IDLE -> remember(samples.clone(), true);
                case OPENING -> remember(samples.clone(), false); // all of it is sent once the stream is open
                case LIVE -> {
                    stream.write(samples);
                    Instant now = clock.instant();
                    if (releasedAt != null && !now.isBefore(releasedAt.plus(tail))) {
                        finished = stream;
                        stream = null;
                        state = State.IDLE;
                        endInterval(now);
                    }
                }
            }
        }
        if (finished == null) return false;
        AudioStream s = finished;
        Thread.ofVirtual().start(s::close); // drains the last few blocks; the capture thread goes on
        return true;
    }

    /** True if most of this time span was spoken live (so the line was already heard as it is). */
    public synchronized boolean wasLive(Instant start, Instant end) {
        long length = Math.max(1, Duration.between(start, end).toMillis());
        long live = 0;
        Instant now = clock.instant();
        for (Interval i : intervals) {
            Instant to = i.to() == null ? now : i.to();
            Instant a = start.isAfter(i.from()) ? start : i.from();
            Instant b = end.isBefore(to) ? end : to;
            if (b.isAfter(a)) live += Duration.between(a, b).toMillis();
        }
        return live * 2 > length;
    }

    private void remember(float[] block, boolean trim) {
        recent.addLast(block);
        recentSamples += block.length;
        long keep = preRoll.toMillis() * sampleRate / 1000;
        while (trim && recent.size() > 1 && recentSamples - recent.peekFirst().length >= keep) {
            recentSamples -= recent.removeFirst().length;
        }
    }

    private void addInterval(Interval i) {
        intervals.addLast(i);
        while (intervals.size() > KEEP_INTERVALS) intervals.removeFirst();
    }

    private void endInterval(Instant to) {
        Interval last = intervals.pollLast();
        if (last != null) intervals.addLast(new Interval(last.from(), to));
    }

    @Override
    public void close() {
        AudioStream s;
        synchronized (this) {
            s = stream;
            stream = null;
            if (state != State.IDLE) endInterval(clock.instant());
            state = State.IDLE;
        }
        if (s != null) s.close();
    }
}
