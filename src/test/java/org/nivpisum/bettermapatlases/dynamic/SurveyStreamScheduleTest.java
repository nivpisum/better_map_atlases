/* SPDX-License-Identifier: MPL-2.0 */
package org.nivpisum.bettermapatlases.dynamic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SurveyStreamScheduleTest {
    @Test void longTransferCompletesWithFreshLeaseAndDoesNotRepeatUnchangedFull() {
        SurveyStreamSchedule stream = new SurveyStreamSchedule(-1, 32768);
        stream.request(0, true); stream.inFlight = true;
        assertFalse(stream.expired(256));
        stream.request(240, true);
        assertEquals(-1, stream.sent);
        stream.completed(32768, 256);
        assertFalse(stream.expired(257));
        assertFalse(stream.due(32768, 257, 20));
        assertFalse(stream.expired(456));
        assertTrue(stream.expired(457));
    }
    @Test void forgottenStreamResumesKnownRevisionAndInvalidBaseRequiresFull() {
        assertEquals(100, new SurveyStreamSchedule(100, 120).sent);
        assertEquals(120, new SurveyStreamSchedule(120, 120).sent);
        assertEquals(-1, new SurveyStreamSchedule(121, 120).sent);
        assertEquals(-1, new SurveyStreamSchedule(-1, 120).sent);
        SurveyStreamSchedule stream = new SurveyStreamSchedule(100, 120);
        stream.request(500, false);
        assertTrue(stream.due(120, 500, 20));
        stream.request(501, true);
        assertEquals(-1, stream.sent);
    }
    @Test void oldDrawRequestAtCompletionDoesNotRestartFull() {
        SurveyStreamSchedule stream = new SurveyStreamSchedule(-1, 100);
        stream.request(0, -1, false, 100); stream.started(); stream.completed(100, 256);
        assertFalse(stream.request(257, -1, false, 100));
        assertEquals(100, stream.sent); assertFalse(stream.due(100, 257, 20));
        assertFalse(stream.request(258, 50, true, 100), "behind recovery also allows receipt grace");
        assertEquals(100, stream.sent);
    }
    @Test void missingAcknowledgementResumesExactClientBaseWithoutIntervalDelay() {
        SurveyStreamSchedule stream = new SurveyStreamSchedule(50, 100);
        stream.started(); stream.completed(100, 256);
        assertTrue(stream.request(266, 50, false, 100));
        assertEquals(50, stream.sent);
        assertTrue(stream.due(100, 266, 200), "repair must not wait the regular update interval");
        stream.started();
        assertFalse(stream.request(300, -1, true, 120)); assertEquals(50, stream.sent);
        stream.completed(100, 400);
        assertFalse(stream.request(401, 100, false, 120));
        assertFalse(stream.due(120, 401, 20), "ordinary new revisions keep configured cadence");
    }
    @Test void explicitCorruptionRecoverySurvivesDrawRenewalAndStartsImmediately() {
        SurveyStreamSchedule stream = new SurveyStreamSchedule(100, 100);
        stream.started(); stream.completed(100, 256);
        assertTrue(stream.request(257, 100, true, 100), "same revision may have corrupted content");
        assertEquals(-1, stream.sent); assertTrue(stream.due(100, 257, 200));
        assertFalse(stream.request(258, 100, false, 100)); assertEquals(-1, stream.sent);
        stream.started(); assertFalse(stream.request(300, 100, true, 100));
    }
    @Test void persistentBehindRecoveryAndInvalidClientBasesFallBackToFull() {
        SurveyStreamSchedule stream = new SurveyStreamSchedule(100, 100);
        stream.started(); stream.completed(100, 256);
        assertFalse(stream.request(257, 50, true, 100));
        assertTrue(stream.request(266, 50, true, 100)); assertEquals(-1, stream.sent);
        stream.started(); stream.completed(100, 300);
        assertTrue(stream.request(310, 101, false, 100)); assertEquals(-1, stream.sent);
        stream.started(); stream.completed(100, 400);
        assertTrue(stream.request(410, -1, false, 100)); assertEquals(-1, stream.sent);
    }
    @Test void servedBudgetRotatesRatherThanRestoringOriginalOrder() {
        SurveyStreamSchedule.Queue<Integer> queue = new SurveyStreamSchedule.Queue<>();
        for (int i = 0; i < 64; i++) queue.put(i, i);
        for (int tick = 0; tick < 32; tick++) for (int packet = 0; packet < 2; packet++)
            assertEquals(tick * 2 + packet, queue.next().getKey());
        assertEquals(0, queue.next().getKey());
    }
    @Test void revokedStreamIsRemovedAndCannotReappearInQueue() {
        SurveyStreamSchedule.Queue<Integer> queue = new SurveyStreamSchedule.Queue<>();
        queue.put(1, 1); queue.put(2, 2);
        var candidate = queue.next();
        assertEquals(1, candidate.getKey());
        queue.remove(candidate.getKey());
        for (int i = 0; i < 10; i++) assertEquals(2, queue.next().getKey());
        queue.evictOldest(); assertNull(queue.next());
    }
    @Test void packetExceedingRemainingByteBudgetGetsFirstTurnNextTick() {
        SurveyStreamSchedule.Queue<Integer> queue = new SurveyStreamSchedule.Queue<>();
        for (int i = 0; i < 4; i++) queue.put(i, i);
        assertEquals(0, queue.next().getKey());
        var deferred = queue.next(); queue.defer(deferred.getKey());
        assertEquals(1, queue.next().getKey());
        assertEquals(2, queue.next().getKey());
        assertEquals(3, queue.next().getKey());
    }
    @Test void primitiveWireBudgetHandlesAllSignedHeightsWithoutPrecisionLoss() {
        for (int value : new int[]{Integer.MIN_VALUE, -8192, -1, 0, 1, 8192, Integer.MAX_VALUE}) {
            assertEquals(value, SurveyPacketSize.unzigzag(SurveyPacketSize.zigzag(value)));
            assertTrue(SurveyPacketSize.varInt(SurveyPacketSize.zigzag(value)) <= 5);
        }
        assertEquals(1, SurveyPacketSize.varInt(127)); assertEquals(2, SurveyPacketSize.varInt(128));
        assertEquals(5, SurveyPacketSize.varInt(-1)); assertEquals(10, SurveyPacketSize.varLong(-1));
        assertEquals(17, SurveyPacketSize.utf("minecraft:plains"));
    }
    @Test void tickBurstEnforcesBothPacketAndByteLimits() {
        SurveyPacketSize.Burst regular = new SurveyPacketSize.Burst(2);
        regular.sent(SurveyPacketSize.TARGET_BYTES); regular.sent(SurveyPacketSize.TARGET_BYTES);
        assertFalse(regular.available());
        assertThrows(IllegalArgumentException.class, () -> regular.sent(1));
        SurveyPacketSize.Burst large = new SurveyPacketSize.Burst(2);
        large.sent(SurveyPacketSize.MAX_BYTES - 100);
        assertEquals(100, large.remainingBytes());
        assertThrows(IllegalArgumentException.class, () -> large.sent(101));
        large.sent(100); assertFalse(large.available());
    }
    @Test void snapshotReferencesStayBoundedAndCapacityRecoversAfterCompletion() {
        SurveySnapshotBudget budget = new SurveySnapshotBudget();
        budget.retain(1_000_000); budget.retain(1_000_000);
        assertFalse(budget.canStart(1)); assertEquals(2_000_000, budget.columns());
        long previous = budget.version(); budget.release(1_000_000);
        assertTrue(budget.version() > previous); assertTrue(budget.canStart(1_000_000));
        assertThrows(IllegalArgumentException.class, () -> budget.retain(1_000_001));
        budget.release(1_000_000); assertEquals(0, budget.transfers());
        budget.retain(2_000_001); assertFalse(budget.canStart(1));
        budget.release(2_000_001); assertEquals(0, budget.columns());
    }
}
