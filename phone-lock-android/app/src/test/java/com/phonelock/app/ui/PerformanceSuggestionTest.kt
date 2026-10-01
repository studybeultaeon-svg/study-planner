package com.phonelock.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceSuggestionTest {
    private val now = 1_000_000_000L

    private fun decide(
        minimal: Boolean = false, lowEnd: Boolean = false, saver: Boolean = false,
        never: Boolean = false, snoozedUntil: Long = 0L
    ) = shouldSuggestPerformanceMode(minimal, lowEnd, saver, never, snoozedUntil, now)

    @Test fun noReasonNoSuggestion() = assertNull(decide())

    @Test fun powerSaveSuggests() = assertEquals(PerformanceReason.POWER_SAVE, decide(saver = true))

    @Test fun lowEndSuggests() = assertEquals(PerformanceReason.LOW_END_DEVICE, decide(lowEnd = true))

    @Test fun powerSaveWinsOverLowEnd() = assertEquals(PerformanceReason.POWER_SAVE, decide(lowEnd = true, saver = true))

    @Test fun alreadyMinimalNeverAsks() = assertNull(decide(minimal = true, saver = true))

    @Test fun neverMeansNever() = assertNull(decide(never = true, saver = true, lowEnd = true))

    @Test fun snoozeHoldsUntilDeadline() {
        assertNull(decide(saver = true, snoozedUntil = now + 1))
        assertEquals(PerformanceReason.POWER_SAVE, decide(saver = true, snoozedUntil = now))
    }
}
