/* SPDX-License-Identifier: CC0-1.0 */
package org.nivpisum.bettermapatlases.dynamic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SurveyRefreshScheduleTest {
    @Test void stationaryEditsGetAnotherAvatarVisitAfterFourTicks() {
        var refresh = new SurveyRecorder.RefreshSchedule();
        assertTrue(refresh.avatarDue(100, 7));
        refresh.avatarFinished(100, 7);
        for (int tick = 101; tick < 104; tick++) assertFalse(refresh.avatarDue(tick, 7));
        assertTrue(refresh.avatarDue(104, 7));
    }

    @Test void movingToAnotherColumnDoesNotWaitForThePreviousRevisitInterval() {
        var refresh = new SurveyRecorder.RefreshSchedule();
        refresh.avatarFinished(100, 7);
        assertTrue(refresh.avatarDue(101, 8));
        refresh.avatarFinished(101, 8);
        assertFalse(refresh.avatarDue(102, 8));
        assertTrue(refresh.avatarDue(102, 7));
    }

    @Test void partialAvatarWorkRemainsDueUntilItActuallyFinishes() {
        var refresh = new SurveyRecorder.RefreshSchedule();
        for (int tick = 100; tick <= 110; tick++) assertTrue(refresh.avatarDue(tick, 7));
        refresh.avatarFinished(110, 7);
        assertFalse(refresh.avatarDue(111, 7));
        assertTrue(refresh.avatarDue(114, 7));
    }

    @Test void nativeAndManagedCallbacksShareOneAttemptPerMapAndTick() {
        var refresh = new SurveyRecorder.RefreshSchedule();
        assertTrue(refresh.beginUpdate(100));
        assertFalse(refresh.beginUpdate(100));
        assertTrue(refresh.alreadyUpdated(100));
        assertTrue(refresh.beginUpdate(101));
        assertFalse(refresh.beginUpdate(101));
    }

    @Test void playersAndPagesKeepIndependentRevisitAndDedupState() {
        var first = new SurveyRecorder.RefreshSchedule();
        var second = new SurveyRecorder.RefreshSchedule();
        first.avatarFinished(100, 7);
        first.beginUpdate(100);
        assertFalse(first.avatarDue(101, 7));
        assertTrue(second.avatarDue(101, 7));
        assertFalse(first.beginUpdate(100));
        assertTrue(second.beginUpdate(100));
    }
}
