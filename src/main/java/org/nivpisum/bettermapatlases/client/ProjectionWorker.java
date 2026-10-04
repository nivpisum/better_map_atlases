/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.client;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** One pure-computation worker, one queued snapshot per key, no world/GL access. */
final class ProjectionWorker<T> {
    private final int capacity;
    private Session<T> session;
    ProjectionWorker(int capacity) { if (capacity < 1) throw new IllegalArgumentException(); this.capacity = capacity; }

    static final class Ticket<T> {
        final Object key;
        Function<BooleanSupplier, T> build;
        volatile boolean cancelled, done;
        T value; RuntimeException failure;
        Ticket(Object key, Function<BooleanSupplier, T> build) { this.key = key; this.build = build; }
    }
    synchronized Ticket<T> submit(Object key, Function<BooleanSupplier, T> build) {
        if (session == null) session = new Session<>(capacity);
        return session.submit(new Ticket<>(key, build));
    }
    synchronized Ticket<T> poll() { return session == null ? null : session.poll(); }
    synchronized void reset() { if (session != null) { session.close(); session = null; } }

    private static final class Session<T> {
        final int capacity;
        final LinkedHashMap<Object, Ticket<T>> pending = new LinkedHashMap<>();
        final ArrayDeque<Ticket<T>> completed = new ArrayDeque<>();
        final ThreadPoolExecutor executor;
        Ticket<T> active;
        boolean closed, running;
        Session(int capacity) {
            this.capacity = capacity;
            executor = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), action -> {
                Thread thread = new Thread(action, "Better Map Atlases index"); thread.setDaemon(true); return thread;
            });
            executor.allowCoreThreadTimeOut(true);
        }
        synchronized Ticket<T> submit(Ticket<T> next) {
            Ticket<T> old = pending.put(next.key, next);
            if (old != null) { old.cancelled = true; old.build = null; }
            while (pending.size() > capacity) {
                var first = pending.entrySet().iterator(); Ticket<T> evicted = first.next().getValue(); evicted.cancelled = true; evicted.build = null; first.remove();
            }
            if (!running) { running = true; executor.execute(this::drain); }
            return next;
        }
        synchronized Ticket<T> poll() { return completed.pollFirst(); }
        void drain() {
            while (true) {
                Ticket<T> task;
                synchronized (this) {
                    if (closed || pending.isEmpty()) { running = false; active = null; return; }
                    var first = pending.entrySet().iterator(); task = first.next().getValue(); first.remove(); active = task;
                }
                try { task.value = task.build.apply(() -> task.cancelled || Thread.currentThread().isInterrupted()); }
                catch (CancellationException ignored) { task.cancelled = true; }
                catch (RuntimeException | Error failure) {
                    task.failure = failure instanceof RuntimeException runtime ? runtime : new IllegalStateException("Projection worker failed", failure);
                }
                synchronized (this) {
                    task.build = null; task.done = true; active = null;
                    if (!closed && !task.cancelled) {
                        completed.addLast(task);
                        // A paused renderer cannot accumulate an unbounded result queue.
                        while (completed.size() > capacity + 1) completed.removeFirst().cancelled = true;
                    }
                }
            }
        }
        synchronized void close() {
            closed = true;
            if (active != null) active.cancelled = true;
            for (Ticket<T> ticket : pending.values()) { ticket.cancelled = true; ticket.build = null; }
            pending.clear(); completed.clear(); executor.shutdownNow();
        }
    }
}
