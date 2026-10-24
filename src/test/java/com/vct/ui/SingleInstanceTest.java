package com.vct.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleInstanceTest {

    @Test
    void aSecondCopyShowsTheFirstAndQuits(@TempDir Path dir) throws Exception {
        CountDownLatch shown = new CountDownLatch(1);
        try (SingleInstance first = SingleInstance.acquire(dir, shown::countDown)) {
            assertNotNull(first);
            assertNull(SingleInstance.acquire(dir, () -> {}), "the second copy does not run");
            assertTrue(shown.await(2, TimeUnit.SECONDS), "the first copy was asked to show its window");
        }
        try (SingleInstance again = SingleInstance.acquire(dir, () -> {})) {
            assertNotNull(again, "after the first copy closed, a new one can run");
        }
    }
}
