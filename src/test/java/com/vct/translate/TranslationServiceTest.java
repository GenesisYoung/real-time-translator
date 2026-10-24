package com.vct.translate;

import com.vct.translate.TranslationException.Kind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TranslationServiceTest {

    static final TranslationService.RetryPolicy FAST =
            new TranslationService.RetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofSeconds(5));

    /** Provider that fails with the given kinds, then succeeds. */
    static final class ScriptedProvider implements TranslationProvider {
        final AtomicInteger calls = new AtomicInteger();
        final List<Kind> failures;
        final CountDownLatch block;

        ScriptedProvider(List<Kind> failures, CountDownLatch block) {
            this.failures = failures;
            this.block = block;
        }

        @Override public String id() { return "fake"; }
        @Override public String model() { return "m"; }
        @Override public List<String> listModels() { return List.of("m"); }

        @Override
        public TranslationResult translate(TranslationRequest r) throws TranslationException {
            int n = calls.getAndIncrement();
            if (block != null) {
                try {
                    block.await();
                } catch (InterruptedException e) {
                    throw new TranslationException(Kind.CANCELLED, null);
                }
            }
            if (n < failures.size()) throw new TranslationException(failures.get(n), "scripted");
            return new TranslationResult("translated:" + r.text(), "fake", "m", Duration.ofMillis(1), false);
        }
    }

    static TranslationRequest req(String id) {
        return new TranslationRequest(id, "hola", "es", "en", TranslationMode.TRANSLATE, List.of(), List.of());
    }

    @Test
    void retriesTransientFailuresThenSucceeds() throws Exception {
        var p = new ScriptedProvider(List.of(Kind.RATE_LIMIT, Kind.SERVER), null);
        try (var s = new TranslationService(p, FAST)) {
            assertEquals("translated:hola", s.translate(req("a"), false).get(2, TimeUnit.SECONDS).text());
            assertEquals(3, p.calls.get());
        }
    }

    @Test
    void doesNotRetryAuthErrors() {
        var p = new ScriptedProvider(List.of(Kind.AUTH), null);
        try (var s = new TranslationService(p, FAST)) {
            var e = assertThrows(ExecutionException.class, () -> s.translate(req("a"), false).get(2, TimeUnit.SECONDS));
            assertEquals(Kind.AUTH, ((TranslationException) e.getCause()).kind());
            assertEquals(1, p.calls.get());
        }
    }

    @Test
    void cachesUnlessForced() throws Exception {
        var p = new ScriptedProvider(List.of(), null);
        try (var s = new TranslationService(p, FAST)) {
            assertFalse(s.translate(req("a"), false).get().fromCache());
            assertTrue(s.translate(req("a"), false).get().fromCache());
            assertEquals(1, p.calls.get(), "same utterance is not sent twice");
            assertFalse(s.translate(req("a"), true).get().fromCache());
            assertEquals(2, p.calls.get(), "explicit retry sends again");
        }
    }

    @Test
    void anotherLineWithTheSameIdIsNotTakenFromTheCache() throws Exception {
        // Ids start again at r-0001 in every session, but the service (and its cache) is kept.
        var p = new ScriptedProvider(List.of(), null);
        try (var s = new TranslationService(p, FAST)) {
            s.translate(new TranslationRequest("r-0005", "Push mid.", "en", "zh", TranslationMode.TRANSLATE, List.of(), List.of()), false).get();
            var other = s.translate(new TranslationRequest("r-0005", "Wait for me.", "en", "zh", TranslationMode.TRANSLATE, List.of(), List.of()), false).get();
            assertFalse(other.fromCache());
            assertEquals(2, p.calls.get());
        }
    }

    @Test
    void deduplicatesConcurrentRequests() throws Exception {
        var gate = new CountDownLatch(1);
        var p = new ScriptedProvider(List.of(), gate);
        try (var s = new TranslationService(p, FAST)) {
            CompletableFuture<TranslationResult> f1 = s.translate(req("a"), false);
            CompletableFuture<TranslationResult> f2 = s.translate(req("a"), false);
            assertSame(f1, f2);
            gate.countDown();
            f1.get(2, TimeUnit.SECONDS);
            assertEquals(1, p.calls.get());
        }
    }

    @Test
    void cancellationInterruptsProvider() throws Exception {
        var gate = new CountDownLatch(1); // never released
        var p = new ScriptedProvider(List.of(), gate);
        try (var s = new TranslationService(p, FAST)) {
            var f = s.translate(req("a"), false);
            Thread.sleep(50);
            assertTrue(f.cancel(true));
            assertTrue(f.isCancelled());
            // A fresh request is not blocked by the cancelled one.
            gate.countDown();
            assertFalse(s.translate(req("a"), false).get(2, TimeUnit.SECONDS).fromCache());
        }
    }

    @Test
    void providerCanBeSwitchedAtRuntime() throws Exception {
        var p1 = new ScriptedProvider(List.of(), null);
        var p2 = new ScriptedProvider(List.of(), null);
        try (var s = new TranslationService(p1, FAST)) {
            s.translate(req("a"), false).get();
            s.setProvider(p2);
            s.translate(req("b"), false).get();
            assertEquals(1, p1.calls.get());
            assertEquals(1, p2.calls.get());
        }
    }
}
