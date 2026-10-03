/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import java.util.LinkedHashMap;
import java.util.Map;

/** Minecraft-independent lease/revision policy and bounded fair queue. */
final class SurveyStreamSchedule {
    static final int LEASE_TICKS = 200;
    static final int COMPLETION_GRACE_TICKS = 10;
    long sent, wantedAt, lastSent;
    private long lastCompleted = -1;
    boolean inFlight;
    private boolean retry, fullRequested;

    SurveyStreamSchedule(long knownRevision, long currentRevision) {
        sent = knownRevision >= 0 && knownRevision <= currentRevision ? knownRevision : -1;
    }

    void request(long tick, boolean full) {
        request(tick, sent, full, Long.MAX_VALUE);
    }

    boolean request(long tick, long knownRevision, boolean full, long currentRevision) {
        wantedAt = tick;
        if (inFlight) return false;
        long known = knownRevision >= 0 && knownRevision <= currentRevision ? knownRevision : -1;
        // A queued draw renewal or a failed assembly can arrive just after the
        // final packet was sent, before the client has committed it. Wait for a
        // later request to confirm the missing base instead of repeating full.
        if (lastCompleted >= 0 && known < lastCompleted && tick - lastSent < COMPLETION_GRACE_TICKS) return false;
        if (full) {
            sent = -1; retry = fullRequested = true; return true;
        }
        // An ordinary draw renewal must not cancel explicit corruption recovery.
        if (fullRequested) return false;
        if (known == sent) return false;
        sent = known; retry = true; return true;
    }

    boolean expired(long tick) { return !inFlight && tick - wantedAt > LEASE_TICKS; }
    boolean due(long revision, long tick, int interval) {
        return !inFlight && sent != revision && (retry || sent < 0 || tick - lastSent >= interval);
    }
    void started() { inFlight = true; retry = fullRequested = false; }
    void completed(long revision, long tick) {
        sent = lastCompleted = revision; lastSent = tick; wantedAt = tick; inFlight = false;
        retry = fullRequested = false;
    }

    static final class Queue<T> {
        private final LinkedHashMap<Integer, T> entries = new LinkedHashMap<>();
        T get(int id) { return entries.get(id); }
        T remove(int id) { return entries.remove(id); }
        void put(int id, T value) { entries.put(id, value); }
        int size() { return entries.size(); }
        void evictOldest() { if (!entries.isEmpty()) entries.remove(entries.keySet().iterator().next()); }
        void defer(int id) {
            T value = entries.remove(id);
            if (value == null) return;
            LinkedHashMap<Integer, T> rest = new LinkedHashMap<>(entries);
            entries.clear(); entries.put(id, value); entries.putAll(rest);
        }
        Map.Entry<Integer, T> next() {
            var iterator = entries.entrySet().iterator();
            if (!iterator.hasNext()) return null;
            var first = iterator.next();
            var detached = Map.entry(first.getKey(), first.getValue());
            iterator.remove(); entries.put(detached.getKey(), detached.getValue());
            return detached;
        }
    }
}
