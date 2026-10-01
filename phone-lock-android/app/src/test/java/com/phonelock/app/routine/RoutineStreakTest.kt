package com.phonelock.app.routine

import com.phonelock.shared.routine.RoutineStreak
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.random.Random

/**
 * 루틴 연속 기록 + 방지권(142차) 테스트 — 엔진은 :shared의 순수 로직이라 안드로이드 유닛테스트에서 검증한다
 * (RoutineRepeatTest와 같은 방식).
 */
class RoutineStreakTest {

    private fun d(text: String) = LocalDate.parse(text)

    // 2026-09-28은 월요일, 2026-09-30은 수요일.
    private val monday = d("2026-09-28")
    private val wednesday = d("2026-09-30")

    /** 날짜별 결과를 직접 적어 넣고, 안 적은 날은 [default]로 본다. */
    private fun results(default: Boolean?, vararg entries: Pair<String, Boolean?>): (LocalDate) -> Boolean? {
        val map = entries.associate { d(it.first) to it.second }
        return { date -> if (map.containsKey(date)) map[date] else default }
    }

    /** 과거에서 오늘 방향으로 한 번 훑는 가장 단순한 기준 구현 — [RoutineStreak.current]와 비교한다. */
    private fun referenceCurrent(today: LocalDate, freeze: Int, lookback: Int, dayResult: (LocalDate) -> Boolean?): Int {
        var run = 0
        var misses = 0
        var date = today.minusDays(lookback.toLong())
        while (!date.isAfter(today)) {
            if (date.dayOfWeek == DayOfWeek.MONDAY) misses = 0
            val result = dayResult(date).let { if (date == today && it == false) null else it }
            when (result) {
                true -> run++
                false -> if (misses < freeze) misses++ else run = 0
                null -> {}
            }
            date = date.plusDays(1)
        }
        return run
    }

    @Test
    fun `every day done counts every day`() {
        assertEquals(10, RoutineStreak.current(wednesday, 2, lookbackDays = 9) { true })
    }

    @Test
    fun `without freeze a single missed day breaks the streak`() {
        val dayResult = results(true, "2026-09-29" to false)
        // 어제를 못 채웠으니 오늘(완료) 하루만 남는다.
        assertEquals(1, RoutineStreak.current(wednesday, 0, lookbackDays = 30, dayResult = dayResult))
    }

    @Test
    fun `missed days within the weekly allowance are skipped without breaking`() {
        // 지난주(9/21~9/27)에 이틀을 못 채웠지만 방지권 2일 안이라 이어진다 — 넘어간 날은 +1이 없다.
        val dayResult = results(true, "2026-09-22" to false, "2026-09-25" to false)
        val streak = RoutineStreak.current(wednesday, 2, lookbackDays = 9, dayResult = dayResult)
        // 9/21~9/30 열흘 중 이틀을 빼고 8일.
        assertEquals(8, streak)
    }

    @Test
    fun `the miss after the allowance is used up breaks the streak`() {
        // 지난주에 사흘을 못 채웠다 — 세 번째(9/26)에서 끊기고, 그 뒤 채운 날부터 다시 센다.
        val dayResult = results(true, "2026-09-22" to false, "2026-09-24" to false, "2026-09-26" to false)
        val streak = RoutineStreak.current(wednesday, 2, lookbackDays = 30, dayResult = dayResult)
        // 9/27(일) + 9/28~9/30 = 4일.
        assertEquals(4, streak)
    }

    @Test
    fun `the allowance refills every Monday`() {
        // 지난주 이틀 + 이번 주 이틀 = 주마다 2일씩이라 전부 넘어간다.
        val dayResult = results(
            true,
            "2026-09-21" to false, "2026-09-27" to false,
            "2026-09-28" to false, "2026-09-29" to false
        )
        val streak = RoutineStreak.current(wednesday, 2, lookbackDays = 9, dayResult = dayResult)
        // 9/21~9/30 열흘 중 나흘을 빼고 6일.
        assertEquals(6, streak)
    }

    @Test
    fun `days without any scheduled routine neither break nor count`() {
        val dayResult = results(true, "2026-09-29" to null, "2026-09-28" to null)
        assertEquals(3, RoutineStreak.current(wednesday, 0, lookbackDays = 4, dayResult = dayResult))
    }

    @Test
    fun `an unfinished today is pending and does not use the allowance`() {
        val dayResult = results(true, "2026-09-30" to false)
        // 오늘은 아직 진행 중 — 어제까지의 연속 일수가 그대로 보인다.
        assertEquals(5, RoutineStreak.current(wednesday, 0, lookbackDays = 5, dayResult = dayResult))
        assertEquals(0, RoutineStreak.usedThisWeek(wednesday, 2, dayResult))
    }

    @Test
    fun `a finished day that was missed counts as a miss when it is no longer pending`() {
        val dayResult = results(true, "2026-09-30" to false)
        // 알림처럼 "그날까지를 확정값"으로 볼 때: 방지권이 없으면 끊기고, 있으면 유지된다.
        assertEquals(0, RoutineStreak.current(wednesday, 0, todayPending = false, lookbackDays = 5, dayResult = dayResult))
        assertEquals(5, RoutineStreak.current(wednesday, 1, todayPending = false, lookbackDays = 5, dayResult = dayResult))
    }

    @Test
    fun `used allowance this week counts only finished days and is capped`() {
        val dayResult = results(true, "2026-09-28" to false, "2026-09-29" to false)
        assertEquals(2, RoutineStreak.usedThisWeek(wednesday, 2, dayResult))
        assertEquals(1, RoutineStreak.usedThisWeek(wednesday, 1, dayResult))
        assertEquals(0, RoutineStreak.usedThisWeek(monday, 2, dayResult))
    }

    @Test
    fun `best streak follows the same allowance rule`() {
        // 9/1~9/13 전부 완료(13일), 9/14 주에 사흘을 못 채워 끊김, 그 뒤 다시 이어감.
        val dayResult = results(
            true,
            "2026-08-31" to false, "2026-08-30" to false, "2026-08-29" to false, "2026-08-28" to false,
            "2026-09-14" to false, "2026-09-15" to false, "2026-09-16" to false
        )
        val best = RoutineStreak.best(wednesday, 2, lookbackDays = 33, dayResult = dayResult)
        // 9/1~9/13(13일) — 9/14·9/15는 방지권으로 넘어가고 9/16에서 끊긴다. 그 뒤 9/17~9/30은 14일.
        assertEquals(14, best)
    }

    @Test
    fun `clamp keeps the allowance between zero and six`() {
        assertEquals(0, RoutineStreak.clampFreeze(-3))
        assertEquals(6, RoutineStreak.clampFreeze(7))
        assertEquals(2, RoutineStreak.clampFreeze(2))
    }

    @Test
    fun `week by week scan agrees with a plain forward pass on random histories`() {
        val random = Random(142)
        repeat(300) {
            val history = (0..80).associate { offset ->
                val roll = random.nextInt(10)
                wednesday.minusDays(offset.toLong()) to when {
                    roll < 6 -> true
                    roll < 9 -> false
                    else -> null
                }
            }
            val dayResult: (LocalDate) -> Boolean? = { history[it] }
            for (freeze in 0..3) {
                assertEquals(
                    referenceCurrent(wednesday, freeze, 80, dayResult),
                    RoutineStreak.current(wednesday, freeze, lookbackDays = 80, dayResult = dayResult)
                )
            }
        }
    }
}
