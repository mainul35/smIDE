package com.smide.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a background task runs on.
 *
 * <p>Every plugin and every part of the IDE that is not the window goes through one service to
 * get off the UI thread, so this is the single place where the answer is decided - and worth a
 * test, because "it still works" is exactly how this would quietly go back to platform threads.
 */
class BackgroundThreadsTest {

    @Test
    void aBackgroundTaskRunsOnAVirtualThread() throws Exception {
        try (ExecutorService pool = WindowServiceImpl.backgroundExecutor()) {
            assertTrue(pool.submit(() -> Thread.currentThread().isVirtual()).get(),
                    "these are short pieces of waiting, not threads worth keeping");
        }
    }

    @Test
    void andSaysWhoItIsInAThreadDump() throws Exception {
        try (ExecutorService pool = WindowServiceImpl.backgroundExecutor()) {
            String name = pool.submit(() -> Thread.currentThread().getName()).get();

            assertTrue(name.startsWith("smide-background-"), name);
            assertNotEquals("smide-background-", name, "numbered, so two can be told apart");
        }
    }

    @Test
    void oneThreadEachRatherThanOneThreadBetweenThem() throws Exception {
        try (ExecutorService pool = WindowServiceImpl.backgroundExecutor()) {
            // Two tasks that will not finish until both have started: a single worker would hang.
            java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2);
            for (int i = 0; i < 2; i++) {
                pool.execute(() -> {
                    both.countDown();
                    try {
                        both.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertTrue(both.await(5, TimeUnit.SECONDS), "both tasks should be running at once");
        }
    }

    @Test
    void aTaskThatThrowsDoesNotTakeTheNextOneWithIt() throws Exception {
        try (ExecutorService pool = WindowServiceImpl.backgroundExecutor()) {
            pool.execute(() -> {
                throw new IllegalStateException("as background tasks do");
            });

            assertEquals("still here", pool.submit(() -> "still here").get());
        }
    }
}
