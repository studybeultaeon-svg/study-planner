package com.phonelock.shared.routine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 루틴 "하루 단위" 연속 기록 계산(142차) — 양 플랫폼 RoutineEngine이 함께 쓰는 단일 출처.
 *
 * 하루의 결과는 셋 중 하나다: 예정된 루틴이 없음(null, 중립) / 전부 완료(true) / 하나라도 못 채움(false).
 * 전부 완료한 날만 연속 일수에 +1 된다.
 *
 * **방지권(142차, 사용자 요청)**: 일주일(월~일)에 [freezePerWeek]일까지는 100%를 채우지 못해도 연속 기록이
 * 끊기지 않는다. 루틴 하나하나가 아니라 "하루" 단위다 — 그 주에 못 채운 날을 앞에서부터 세어 [freezePerWeek]
 * 번째까지는 그냥 넘어가고(연속 일수는 그대로, +1은 없음), 그다음 못 채운 날부터 0으로 끊긴다. 주가 바뀌면
 * 다시 채워진다. 쓴 횟수를 따로 저장하지 않고 완료 기록에서 매번 다시 센다(파생 값).
 *
 * **오늘은 아직 진행 중**([todayPending]): 오늘을 못 채운 상태는 "못 채운 날"로 세지 않는다 — 하루가 끝나기
 * 전에 방지권을 써 버리거나 연속 기록이 0으로 보였다가 완료하는 순간 되살아나는 일이 없게 한다. 어제까지의
 * 상태를 확정값으로 볼 때(연속 기록 알림 등)는 false로 넘긴다.
 */
object RoutineStreak {

    const val DEFAULT_FREEZE_DAYS_PER_WEEK = 2
    /** 7이면 어떤 주도 끊길 수 없어 연속 기록이 의미를 잃는다 — 최소 하루는 채워야 하는 6까지만 허용. */
    const val MAX_FREEZE_DAYS_PER_WEEK = 6
    const val DEFAULT_LOOKBACK_DAYS = 3650

    fun clampFreeze(value: Int): Int = value.coerceIn(0, MAX_FREEZE_DAYS_PER_WEEK)

    private fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private inline fun resultOn(
        date: LocalDate,
        today: LocalDate,
        todayPending: Boolean,
        dayResult: (LocalDate) -> Boolean?
    ): Boolean? {
        val result = dayResult(date)
        return if (todayPending && date == today && result == false) null else result
    }

    /**
     * 지금 이어지고 있는 연속 일수. 이번 주부터 한 주씩 거슬러 올라가며, 방지권을 넘겨 끊긴 주를 만나면 그 주의
     * 마지막으로 못 채운 날 뒤에 채운 날까지만 더하고 멈춘다(끊긴 주에선 방지권을 다 쓴 뒤의 못 채운 날이 전부
     * 0으로 되돌리므로, 마지막으로 못 채운 날이 곧 끊긴 지점이다).
     */
    fun current(
        today: LocalDate,
        freezePerWeek: Int,
        todayPending: Boolean = true,
        lookbackDays: Int = DEFAULT_LOOKBACK_DAYS,
        dayResult: (LocalDate) -> Boolean?
    ): Int {
        val freeze = clampFreeze(freezePerWeek)
        val oldest = today.minusDays(lookbackDays.toLong())
        var streak = 0
        var from = weekStart(today)
        var to = today
        while (!to.isBefore(oldest)) {
            var misses = 0
            var done = 0
            var doneAfterLastMiss = 0
            var date = if (from.isBefore(oldest)) oldest else from
            while (!date.isAfter(to)) {
                when (resultOn(date, today, todayPending, dayResult)) {
                    true -> { done++; doneAfterLastMiss++ }
                    false -> { misses++; doneAfterLastMiss = 0 }
                    null -> {}
                }
                date = date.plusDays(1)
            }
            if (misses > freeze) return streak + doneAfterLastMiss
            streak += done
            to = from.minusDays(1)
            from = from.minusDays(7)
        }
        return streak
    }

    /** 지금까지 가장 길었던 연속 일수 — [current]와 같은 규칙을 과거에서 오늘 방향으로 한 번 훑는다. */
    fun best(
        today: LocalDate,
        freezePerWeek: Int,
        todayPending: Boolean = true,
        lookbackDays: Int = DEFAULT_LOOKBACK_DAYS,
        dayResult: (LocalDate) -> Boolean?
    ): Int {
        val freeze = clampFreeze(freezePerWeek)
        var best = 0
        var run = 0
        var misses = 0
        var date = today.minusDays(lookbackDays.toLong())
        while (!date.isAfter(today)) {
            if (date.dayOfWeek == DayOfWeek.MONDAY) misses = 0
            when (resultOn(date, today, todayPending, dayResult)) {
                true -> { run++; if (run > best) best = run }
                false -> if (misses < freeze) misses++ else run = 0
                null -> {}
            }
            date = date.plusDays(1)
        }
        return best
    }

    /** 이번 주(월~어제)에 이미 쓴 방지권 수 — 화면에 "이번 주 방지권 1/2"처럼 보여줄 때 쓴다. */
    fun usedThisWeek(today: LocalDate, freezePerWeek: Int, dayResult: (LocalDate) -> Boolean?): Int {
        val freeze = clampFreeze(freezePerWeek)
        var misses = 0
        var date = weekStart(today)
        while (date.isBefore(today)) {
            if (dayResult(date) == false) misses++
            date = date.plusDays(1)
        }
        return minOf(misses, freeze)
    }
}
