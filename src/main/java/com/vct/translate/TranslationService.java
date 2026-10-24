package com.vct.translate;

import com.vct.translate.TranslationException.Kind;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs translations off the UI and audio threads, with bounded retries, an overall deadline,
 * cancellation, de-duplication of in-flight requests, and a cache so an utterance is never sent
 * twice unless the user explicitly retries ({@code force}). The provider can be swapped at runtime.
 */
public final class TranslationService implements AutoCloseable {

    public record RetryPolicy(int maxAttempts, Duration baseBackoff, Duration maxBackoff, Duration deadline) {
        public static RetryPolicy defaults() {
            return new RetryPolicy(3, Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofSeconds(15));
        }
    }

    private final AtomicReference<TranslationProvider> provider;
    private final RetryPolicy policy;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, TranslationResult> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<TranslationResult>> inFlight = new ConcurrentHashMap<>();

    public TranslationService(TranslationProvider initial, RetryPolicy policy) {
        this.provider = new AtomicReference<>(initial);
        this.policy = policy;
    }

    /** Switches provider/model without restarting; cached results remain valid. */
    public void setProvider(TranslationProvider p) {
        provider.set(p);
    }

    public TranslationProvider provider() {
        return provider.get();
    }

    public CompletableFuture<TranslationResult> translate(TranslationRequest req, boolean force) {
        String key = req.cacheKey();
        if (!force) {
            TranslationResult cached = cache.get(key);
            if (cached != null) return CompletableFuture.completedFuture(cached.asCached());
            CompletableFuture<TranslationResult> running = inFlight.get(key);
            if (running != null) return running;
        }
        CompletableFuture<TranslationResult> cf = new CompletableFuture<>();
        inFlight.put(key, cf);
        Future<?> task = exec.submit(() -> {
            try {
                TranslationResult r = runWithRetries(req);
                cache.put(key, r);
                cf.complete(r);
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            } finally {
                inFlight.remove(key, cf);
            }
        });
        cf.whenComplete((r, e) -> {
            if (cf.isCancelled()) {
                task.cancel(true);
                inFlight.remove(key, cf);
            }
        });
        return cf;
    }

    public void clearCache() {
        cache.clear();
    }

    private TranslationResult runWithRetries(TranslationRequest req) throws TranslationException {
        long deadline = System.nanoTime() + policy.deadline().toNanos();
        TranslationException last = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new TranslationException(Kind.CANCELLED, null);
            try {
                return provider.get().translate(req);
            } catch (TranslationException e) {
                last = e;
                if (!e.kind().retryable() || attempt == policy.maxAttempts()) throw e;
                Duration wait = backoff(attempt, e.retryAfter());
                if (System.nanoTime() + wait.toNanos() > deadline) throw e;
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new TranslationException(Kind.CANCELLED, null, null, ie);
                }
            }
        }
        throw last;
    }

    private Duration backoff(int attempt, Duration retryAfter) {
        Duration exp = policy.baseBackoff().multipliedBy(1L << (attempt - 1));
        Duration wait = retryAfter != null && retryAfter.compareTo(exp) > 0 ? retryAfter : exp;
        return wait.compareTo(policy.maxBackoff()) > 0 ? policy.maxBackoff() : wait;
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
