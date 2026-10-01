package com.phonelock.app.routine

import com.phonelock.shared.study.StudyStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 공부 통계(연속 기록 · 평균 공부 시간, 142차) 테스트 — 엔진은 :shared의 순수 로직이라 안드로이드
 * 유닛테스트에서 검증한다(StudyAlertEngineTest와 같은 방식).
 */
class StudyStatsTest {

    private fun d(text: String) = LocalDate.parse(text)
    private val today = d("2026-09-30")

    private fun dates(vararg keys: String) = keys.map { d(it) }.toSet()

    @Test
    fun `streak counts back from today while every day has a record`() {
        assertEquals(3, StudyStats.currentStreak(dates("2026-09-30", "2026-09-29", "2026-09-28", "2026-09-26"), today))
    }

    @Test
    fun `streak keeps yesterday's value until today is over`() {
        // 오늘 아직 기록이 없다 — 어제까지 이틀 연속이 그대로 보인다.
        assertEquals(2, StudyStats.currentStreak(dates("2026-09-29", "2026-09-28"), today))
    }

    @Test
    fun `a day without any record breaks the streak`() {
        assertEquals(0, StudyStats.currentStreak(dates("2026-09-28", "2026-09-27"), today))
        assertEquals(0, StudyStats.currentStreak(emptySet(), today))
    }

    @Test
    fun `best streak is the longest run of consecutive days`() {
        val studied = dates(
            "2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04",
            "2026-09-10", "2026-09-11",
            "2026-09-30"
        )
        assertEquals(4, StudyStats.bestStreak(studied))
        assertEquals(0, StudyStats.bestStreak(emptySet()))
    }

    @Test
    fun `daily average divides by the window including rest days`() {
        val seconds = mapOf(d("2026-09-30") to 3600L, d("2026-09-28") to 7200L, d("2026-09-20") to 9000L)
        // 최근 7일(9/24~9/30) 합계 10800초 ÷ 7일.
        assertEquals(10800L / 7, StudyStats.dailyAverageSeconds(seconds, today, 7, d("2026-08-01")))
        // 최근 30일(9/1~9/30) 합계 19800초 ÷ 30일.
        assertEquals(19800L / 30, StudyStats.dailyAverageSeconds(seconds, today, 30, d("2026-08-01")))
    }

    @Test
    fun `daily average only counts the days since the first record`() {
        val seconds = mapOf(d("2026-09-30") to 3600L, d("2026-09-28") to 7200L)
        // 첫 기록이 그제 — 7이 아니라 사흘(9/28~9/30)로 나눈다.
        assertEquals(10800L / 3, StudyStats.dailyAverageSeconds(seconds, today, 7, d("2026-09-28")))
    }

    @Test
    fun `average is unknown without any record`() {
        assertNull(StudyStats.dailyAverageSeconds(emptyMap(), today, 7, null))
        assertNull(StudyStats.activeDayAverageSeconds(emptyMap(), today, 30))
    }

    @Test
    fun `active day average ignores rest days and days outside the window`() {
        val seconds = mapOf(
            d("2026-09-30") to 3600L, d("2026-09-28") to 7200L, d("2026-09-27") to 0L,
            d("2026-07-01") to 99999L
        )
        assertEquals(5400L, StudyStats.activeDayAverageSeconds(seconds, today, 30))
    }

    @Test
    fun `summary merges dates known only by key and skips broken or future keys`() {
        val totals = StudyStats.DayTotals(
            secondsByDate = mapOf("2026-09-30" to 1800L, "2026-09-29" to 600L, "not-a-date" to 50L),
            // 9/28은 다른 기기에서만 공부해 날짜 키로만 안다. 내일 날짜는 시계가 다른 기기의 기록일 수 있어 무시.
            studiedDates = setOf("2026-09-28", "2026-10-01", "broken")
        )
        val summary = StudyStats.summarize(totals, today)
        assertEquals(3, summary.currentStreak)
        assertEquals(3, summary.bestStreak)
        assertEquals(1800L, summary.todaySeconds)
        // 첫 기록(9/28)부터 사흘 — 합계 2400초 ÷ 3.
        assertEquals(800L, summary.shortAverageSeconds)
        assertEquals(800L, summary.longAverageSeconds)
        assertEquals(1200L, summary.activeDayAverageSeconds)
    }

    @Test
    fun `duration label stays short`() {
        assertEquals("0분", StudyStats.durationLabel(0))
        assertEquals("1분 미만", StudyStats.durationLabel(59))
        assertEquals("45분", StudyStats.durationLabel(45 * 60L))
        assertEquals("2시간", StudyStats.durationLabel(7200))
        assertEquals("2시간 5분", StudyStats.durationLabel(7200 + 300 + 20))
    }
}
