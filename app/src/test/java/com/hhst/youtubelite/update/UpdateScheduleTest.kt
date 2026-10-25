package com.hhst.youtubelite.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateScheduleTest {
    private val day = UpdateSchedule.INTERVAL_MS

    @Test fun dueOnFirstRunAfterIntervalAndOnClockRollback() {
        assertTrue(UpdateSchedule.isDue(now = 1000L, lastCheck = 0L, interval = day))
        assertFalse(UpdateSchedule.isDue(now = day, lastCheck = 1L, interval = day))
        assertTrue(UpdateSchedule.isDue(now = day + 1, lastCheck = 1L, interval = day))
        assertTrue(UpdateSchedule.isDue(now = 10L, lastCheck = 20L, interval = day))
    }

    @Test fun promptRespectsSkipAndLater() {
        assertTrue(UpdateSchedule.shouldPrompt("v3.1", skippedTag = null, now = day, lastPrompt = 0L, interval = day))
        assertFalse(UpdateSchedule.shouldPrompt("v3.1", skippedTag = "v3.1", now = day, lastPrompt = 0L, interval = day))
        // A newer tag than the skipped one prompts again.
        assertTrue(UpdateSchedule.shouldPrompt("v3.2", skippedTag = "v3.1", now = day, lastPrompt = 0L, interval = day))
        // "Later" silences for one interval.
        assertFalse(UpdateSchedule.shouldPrompt("v3.1", skippedTag = null, now = day, lastPrompt = day - 1, interval = day))
        assertTrue(UpdateSchedule.shouldPrompt("v3.1", skippedTag = null, now = 2 * day, lastPrompt = day - 1, interval = day))
    }
}
