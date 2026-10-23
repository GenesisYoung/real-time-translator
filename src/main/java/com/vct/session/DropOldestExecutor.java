package com.vct.session;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Single worker with a bounded queue. When full, the oldest queued task is discarded and handed to
 * {@code onDrop}, so recognition degrades by skipping stale audio instead of growing memory.
 */
final class DropOldestExecutor implements AutoCloseable {

    private final BlockingQueue<Runnable> queue;
    private final Consumer<Runnable> onDrop;
    private final Thread worker;
    private volatile boolean running = true;

    DropOldestExecutor(String name, int capacity, Consumer<Runnable> onDrop) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.onDrop = onDrop;
        this.worker = Thread.ofPlatform().name(name).daemon().unstarted(this::loop);
        this.worker.start();
    }

    void submit(Runnable task) {
        while (!queue.offer(task)) {
            Runnable dropped = queue.poll();
            if (dropped != null) onDrop.accept(dropped);
        }
    }

    private void loop() {
        while (running) {
            try {
                Runnable r = queue.poll(200, TimeUnit.MILLISECONDS);
                if (r != null) r.run();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // Tasks report their own errors; never let one kill the worker.
            }
        }
    }

    /** Stops after draining queued tasks, waiting at most {@code timeoutMs}. */
    void shutdown(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        running = false;
        worker.join(Math.max(1, deadline - System.currentTimeMillis()));
        if (worker.isAlive()) worker.interrupt();
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
    }
}
