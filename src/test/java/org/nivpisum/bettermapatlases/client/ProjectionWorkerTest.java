/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProjectionWorkerTest {
    private static final long TIMEOUT_SECONDS = 5;

    @Test void capacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectionWorker<>(0));
        assertThrows(IllegalArgumentException.class, () -> new ProjectionWorker<>(-1));
    }

    @Test void queuedBuildsExecuteOnOneBackgroundThread() throws InterruptedException {
        var worker = new ProjectionWorker<Integer>(4);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximumActive = new AtomicInteger();
        var threads = new ArrayList<Thread>();
        Thread caller = Thread.currentThread();
        try {
            var first = worker.submit("first", cancelled -> {
                recordEntry(active, maximumActive, threads);
                try {
                    entered.countDown();
                    awaitBuild(release);
                    return 1;
                } finally { active.decrementAndGet(); }
            });
            await(entered);
            var second = worker.submit("second", cancelled -> {
                recordEntry(active, maximumActive, threads);
                try { return 2; } finally { active.decrementAndGet(); }
            });
            var third = worker.submit("third", cancelled -> {
                recordEntry(active, maximumActive, threads);
                try { return 3; } finally { active.decrementAndGet(); }
            });
            assertFalse(second.done);
            assertFalse(third.done);
            release.countDown();
            assertSame(first, awaitPublished(worker));
            assertSame(second, awaitPublished(worker));
            assertSame(third, awaitPublished(worker));
            assertEquals(1, maximumActive.get());
            assertEquals(0, active.get());
            assertEquals(3, threads.size());
            assertSame(threads.get(0), threads.get(1));
            assertSame(threads.get(0), threads.get(2));
            assertFalse(threads.contains(caller));
        } finally {
            worker.reset();
            release.countDown();
        }
    }

    @Test void replacingAQueuedKeyKeepsItsOriginalFifoPosition() throws InterruptedException {
        var worker = new ProjectionWorker<String>(4);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var order = new ArrayList<String>();
        var replacedRan = new AtomicBoolean();
        try {
            var running = worker.submit("running", cancelled -> {
                entered.countDown();
                awaitBuild(release);
                return "running";
            });
            await(entered);
            var replaced = worker.submit("a", cancelled -> {
                replacedRan.set(true);
                return "old a";
            });
            var b = worker.submit("b", cancelled -> { order.add("b"); return "b"; });
            var newestA = worker.submit("a", cancelled -> { order.add("new a"); return "new a"; });
            var c = worker.submit("c", cancelled -> { order.add("c"); return "c"; });
            assertTrue(replaced.cancelled);
            assertFalse(newestA.cancelled);
            release.countDown();
            assertSame(running, awaitPublished(worker));
            assertSame(newestA, awaitPublished(worker));
            assertSame(b, awaitPublished(worker));
            assertSame(c, awaitPublished(worker));
            assertEquals(List.of("new a", "b", "c"), order);
            assertFalse(replacedRan.get());
            assertNull(worker.poll());
        } finally {
            worker.reset();
            release.countDown();
        }
    }

    @Test void pendingCapacityEvictsAndCancelsTheOldestQueuedKey() throws InterruptedException {
        var worker = new ProjectionWorker<String>(2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var evictedRan = new AtomicBoolean();
        try {
            var running = worker.submit("running", cancelled -> {
                entered.countDown();
                awaitBuild(release);
                return "running";
            });
            await(entered);
            var evicted = worker.submit("a", cancelled -> { evictedRan.set(true); return "a"; });
            var b = worker.submit("b", cancelled -> "b");
            var c = worker.submit("c", cancelled -> "c");
            assertTrue(evicted.cancelled);
            assertFalse(running.cancelled);
            assertFalse(b.cancelled);
            assertFalse(c.cancelled);
            release.countDown();
            assertSame(running, awaitPublished(worker));
            assertSame(b, awaitPublished(worker));
            assertSame(c, awaitPublished(worker));
            assertFalse(evictedRan.get());
            assertNull(worker.poll());
        } finally {
            worker.reset();
            release.countDown();
        }
    }

    @Test void publicationExposesCompletedValueAndFailureAndBuildsContinue() {
        var worker = new ProjectionWorker<String>(4);
        var failure = new IllegalStateException("failed snapshot");
        try {
            var success = worker.submit("success", cancelled -> "computed value");
            assertSame(success, awaitPublished(worker));
            assertTrue(success.done);
            assertFalse(success.cancelled);
            assertEquals("computed value", success.value);
            assertNull(success.failure);

            var failed = worker.submit("failed", cancelled -> { throw failure; });
            assertSame(failed, awaitPublished(worker));
            assertTrue(failed.done);
            assertFalse(failed.cancelled);
            assertNull(failed.value);
            assertSame(failure, failed.failure);

            var cancelled = worker.submit("cancelled", cancellation -> { throw new CancellationException(); });
            awaitDone(cancelled);
            var next = worker.submit("next", cancellation -> "next value");
            assertSame(next, awaitPublished(worker));
            assertTrue(cancelled.cancelled);
            assertEquals("next value", next.value);
            assertNull(worker.poll());
        } finally { worker.reset(); }
    }

    @Test void completedCapacityCancelsAndDropsTheOldestUnconsumedResult() {
        var worker = new ProjectionWorker<Integer>(2);
        var tickets = new ArrayList<ProjectionWorker.Ticket<Integer>>();
        try {
            for (int value = 0; value < 4; value++) {
                int result = value;
                var ticket = worker.submit(value, cancelled -> result);
                awaitDone(ticket);
                tickets.add(ticket);
            }
            // Poll synchronizes with publication even if done became visible just before enqueue.
            assertSame(tickets.get(1), awaitPublished(worker));
            assertTrue(tickets.get(0).cancelled);
            assertSame(tickets.get(2), awaitPublished(worker));
            assertSame(tickets.get(3), awaitPublished(worker));
            assertNull(worker.poll());
        } finally { worker.reset(); }
    }

    @Test void anErrorIsPublishedAsFailureAndDoesNotStallSubsequentBuilds() throws InterruptedException {
        var worker = new ProjectionWorker<String>(2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var error = new AssertionError("failed computation");
        try {
            var failed = worker.submit("error", cancelled -> {
                entered.countDown();
                awaitBuild(release);
                throw error;
            });
            await(entered);
            var next = worker.submit("next", cancelled -> "after error");
            release.countDown();
            assertSame(failed, awaitPublished(worker));
            assertTrue(failed.done);
            assertFalse(failed.cancelled);
            assertNull(failed.value);
            assertTrue(failed.failure instanceof IllegalStateException);
            assertSame(error, failed.failure.getCause());
            assertSame(next, awaitPublished(worker));
            assertEquals("after error", next.value);
            assertNull(worker.poll());
        } finally {
            worker.reset();
            release.countDown();
        }
    }

    @Test void resetCancelsRunningAndQueuedBuildsAndIsolatesTheirLateCompletion() throws InterruptedException {
        var worker = new ProjectionWorker<String>(2);
        var oldEntered = new CountDownLatch(1);
        var oldRelease = new CountDownLatch(1);
        var newEntered = new CountDownLatch(1);
        var newRelease = new CountDownLatch(1);
        var queuedRan = new AtomicBoolean();
        var cancellationObserved = new AtomicBoolean();
        var oldThread = new AtomicReference<Thread>();
        var newThread = new AtomicReference<Thread>();
        try {
            var old = worker.submit("same key", cancelled -> {
                oldThread.set(Thread.currentThread());
                oldEntered.countDown();
                // Model a build that finishes late despite the reset interrupt.
                awaitBuildIgnoringInterrupt(oldRelease);
                cancellationObserved.set(cancelled.getAsBoolean());
                return "old session";
            });
            await(oldEntered);
            var queued = worker.submit("queued", cancelled -> { queuedRan.set(true); return "queued"; });
            worker.reset();
            assertTrue(old.cancelled);
            assertTrue(queued.cancelled);
            assertNull(worker.poll());

            var fresh = worker.submit("same key", cancelled -> {
                newThread.set(Thread.currentThread());
                newEntered.countDown();
                awaitBuild(newRelease);
                return "new session";
            });
            await(newEntered);
            assertFalse(fresh.cancelled);
            assertFalse(oldThread.get() == newThread.get());
            oldRelease.countDown();
            awaitDone(old);
            assertTrue(cancellationObserved.get());
            assertFalse(queuedRan.get());
            assertNull(worker.poll(), "An old session result must not enter the fresh session");
            newRelease.countDown();
            assertSame(fresh, awaitPublished(worker));
            assertTrue(fresh.done);
            assertEquals("new session", fresh.value);
            assertNull(worker.poll());
        } finally {
            worker.reset();
            oldRelease.countDown();
            newRelease.countDown();
        }
    }

    @Test void resetDropsAlreadyCompletedResultsAndCanBeRepeated() {
        var worker = new ProjectionWorker<String>(2);
        try {
            var old = worker.submit("old", cancelled -> "old");
            awaitDone(old);
            worker.reset();
            worker.reset();
            assertNull(worker.poll());
            var fresh = worker.submit("fresh", cancelled -> "fresh");
            assertSame(fresh, awaitPublished(worker));
            assertEquals("fresh", fresh.value);
            assertNull(worker.poll());
        } finally { worker.reset(); }
    }

    private static void recordEntry(AtomicInteger active, AtomicInteger maximumActive, List<Thread> threads) {
        maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
        synchronized (threads) { threads.add(Thread.currentThread()); }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Worker did not reach the expected latch");
    }

    private static void awaitBuild(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("Build latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }

    private static void awaitBuildIgnoringInterrupt(CountDownLatch latch) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        boolean interrupted = false;
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("Late build latch timed out");
                try {
                    if (!latch.await(remaining, TimeUnit.NANOSECONDS)) throw new IllegalStateException("Late build latch timed out");
                    return;
                } catch (InterruptedException ignored) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void awaitDone(ProjectionWorker.Ticket<?> ticket) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!ticket.done && System.nanoTime() < deadline) Thread.yield();
        assertTrue(ticket.done, "Worker did not finish the ticket");
    }

    private static <T> ProjectionWorker.Ticket<T> awaitPublished(ProjectionWorker<T> worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        ProjectionWorker.Ticket<T> ticket;
        do {
            ticket = worker.poll();
            if (ticket != null) return ticket;
            Thread.yield();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Worker did not publish a result");
    }
}
