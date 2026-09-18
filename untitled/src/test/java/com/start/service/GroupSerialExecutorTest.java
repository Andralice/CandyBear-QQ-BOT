package com.start.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupSerialExecutorTest {

    @Test
    void tasksForSameGroupDoNotOverlap() throws Exception {
        GroupSerialExecutor executor = new GroupSerialExecutor(2, 5_000);
        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondFinished = new CountDownLatch(1);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();

            executor.execute("group-1", () -> {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                firstStarted.countDown();
                try {
                    releaseFirst.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                }
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            executor.execute("group-1", () -> {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                active.decrementAndGet();
                secondFinished.countDown();
            });

            assertEquals(1, maxActive.get());
            assertTrue(!secondFinished.await(100, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            assertTrue(secondFinished.await(1, TimeUnit.SECONDS));
            assertEquals(1, maxActive.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void differentGroupsCanRunInParallel() throws Exception {
        GroupSerialExecutor executor = new GroupSerialExecutor(2, 5_000);
        try {
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);

            executor.execute("group-1", () -> await(started, release));
            executor.execute("group-2", () -> await(started, release));

            assertTrue(started.await(1, TimeUnit.SECONDS));
            release.countDown();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void differentUsersInSameGroupCanRunInParallel() throws Exception {
        GroupSerialExecutor executor = new GroupSerialExecutor(2, 5_000);
        try {
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);

            executor.executeConversation("group-1", "user-1", () -> await(started, release));
            executor.executeConversation("group-1", "user-2", () -> await(started, release));

            assertTrue(started.await(1, TimeUnit.SECONDS));
            release.countDown();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void sameUserConversationTasksRemainSerial() throws Exception {
        GroupSerialExecutor executor = new GroupSerialExecutor(2, 5_000);
        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondFinished = new CountDownLatch(1);

            executor.executeConversation("group-1", "user-1", () -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            executor.executeConversation("group-1", "user-1", secondFinished::countDown);
            assertTrue(!secondFinished.await(100, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            assertTrue(secondFinished.await(1, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
        }
    }

    private static void await(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
