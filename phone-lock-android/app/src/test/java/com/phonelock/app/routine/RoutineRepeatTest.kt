package com.phonelock.app.routine

import com.phonelock.shared.routine.RoutineRepeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 루틴 반복 규칙(134차) 테스트 — 엔진은 :shared의 순수 로직이라 안드로이드 유닛테스트에서 그대로 검증한다
 * (shared 모듈 자체엔 테스트 구성이 없음, StudyAlertEngineTest와 같은 방식).
 */
class RoutineRepeatTest {

    private fun d(text: String) = LocalDate.parse(text)

    @Test
    fun `weekly mode keeps the old weekday mask behaviour`() {
        // 2026-09-23은 수요일 → bit 2.
        val onlyWednesday = 1 shl 2
        assertTrue(RoutineRepeat.isScheduled(RoutineRepeat.MODE_WEEKLY, onlyWednesday, 3, "1", null, d("2026-09-23")))
        assertFalse(RoutineRepeat.isScheduled(RoutineRepeat.MODE_WEEKLY, onlyWednesday, 3, "1", null, d("2026-09-24")))
    }

    @Test
    fun `interval counts from the anchor date and skips days in between`() {
        val anchor = d("2026-09-23")
        val scheduled = (0..7).map { RoutineRepeat.isIntervalDay(3, anchor, anchor.plusDays(it.toLong())) }
        assertEquals(listOf(true, false, false, true, false, false, true, false), scheduled)
    }

    @Test
    fun `interval does not fire before the anchor date`() {
        val anchor = d("2026-09-23")
        assertFalse(RoutineRepeat.isIntervalDay(3, anchor, anchor.minusDays(3)))
    }

    @Test
    fun `interval without an anchor still lands on the same days everywhere`() {
        // 기준일을 안 정했으면 고정 기준일(2000-01-01)로 센다 — 기기가 달라도 같은 날 실행돼야 한다.
        assertTrue(RoutineRepeat.isIntervalDay(2, null, d("2000-01-01")))
        assertFalse(RoutineRepeat.isIntervalDay(2, null, d("2000-01-02")))
        assertTrue(RoutineRepeat.isIntervalDay(2, null, d("2026-09-23")) == (java.time.temporal.ChronoUnit.DAYS.between(d("2000-01-01"), d("2026-09-23")) % 2 == 0L))
    }

    @Test
    fun `monthly fires on the chosen days`() {
        assertTrue(RoutineRepeat.isMonthlyDay("1,15", d("2026-09-01")))
        assertTrue(RoutineRepeat.isMonthlyDay("1,15", d("2026-09-15")))
        assertFalse(RoutineRepeat.isMonthlyDay("1,15", d("2026-09-16")))
    }

    @Test
    fun `month start and month end`() {
        val csv = "1," + RoutineRepeat.LAST_DAY
        assertTrue(RoutineRepeat.isMonthlyDay(csv, d("2026-09-01")))
        assertTrue(RoutineRepeat.isMonthlyDay(csv, d("2026-09-30")))   // 9월 말일
        assertFalse(RoutineRepeat.isMonthlyDay(csv, d("2026-09-29")))
        assertTrue(RoutineRepeat.isMonthlyDay(csv, d("2026-02-28")))   // 2월 말일
        assertTrue(RoutineRepeat.isMonthlyDay(csv, d("2028-02-29")))   // 윤년 말일
        assertFalse(RoutineRepeat.isMonthlyDay(csv, d("2028-02-28")))
    }

    @Test
    fun `a day that the month does not have falls back to the last day`() {
        // 31일 리마인더가 30일까지뿐인 달에 통째로 사라지면 안 된다.
        assertTrue(RoutineRepeat.isMonthlyDay("31", d("2026-09-30")))
        assertFalse(RoutineRepeat.isMonthlyDay("31", d("2026-09-29")))
        assertTrue(RoutineRepeat.isMonthlyDay("31", d("2026-10-31")))
        assertFalse(RoutineRepeat.isMonthlyDay("31", d("2026-10-30")))
    }

    @Test
    fun `parsing cleans up the day list`() {
        assertEquals(listOf("1", "15", "L"), RoutineRepeat.parseMonthDays(" 15, 1 , l , 1, 0, 32, "))
        assertEquals("1,15,L", RoutineRepeat.toMonthDaysCsv(setOf("15", "1", "L")))
        assertTrue(RoutineRepeat.parseMonthDays("").isEmpty())
        assertFalse(RoutineRepeat.isMonthlyDay("", d("2026-09-01")))
    }

    @Test
    fun `descriptions read like the labels in the app`() {
        assertEquals("매일", RoutineRepeat.describe(RoutineRepeat.MODE_WEEKLY, 127, 3, "1"))
        assertEquals("주중(월~금)", RoutineRepeat.describe(RoutineRepeat.MODE_WEEKLY, 0b0011111, 3, "1"))
        assertEquals("주말(토·일)", RoutineRepeat.describe(RoutineRepeat.MODE_WEEKLY, 0b1100000, 3, "1"))
        assertEquals("월 · 수 · 금", RoutineRepeat.describe(RoutineRepeat.MODE_WEEKLY, 0b0010101, 3, "1"))
        assertEquals("3일마다", RoutineRepeat.describe(RoutineRepeat.MODE_INTERVAL, 127, 3, "1"))
        assertEquals("매월 1일 · 말일", RoutineRepeat.describe(RoutineRepeat.MODE_MONTHLY, 127, 3, "1,L"))
    }
}
