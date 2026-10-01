package com.phonelock.desktop.routine

import com.phonelock.shared.routine.RoutineRepeat
import com.phonelock.shared.routine.RoutineStreak

import com.phonelock.desktop.data.Routine
import java.time.LocalDate

/**
 * 루틴앱 스트릭 계산의 플랫폼 쪽 입구 — "하루" 단위 전역 스트릭(51차): 그날 예정된 루틴을 전부 완료하면
 * 그날 +1. 142차부터 실제 계산과 방지권(일주일에 며칠은 100%가 아니어도 넘어감) 규칙은
 * `shared/routine/RoutineStreak.kt` 하나에 있고, 여기서는 이 플랫폼의 [Routine]으로 "그날 결과"만 만든다.
 */
object RoutineEngine {

    private fun bitIndexFor(date: LocalDate): Int = date.dayOfWeek.value - 1
    /** 요일마스크+기간(52차)을 함께 고려해 이 날짜에 예정됐는지 판정 — 데스크탑 알림 tick 체크도 재사용. */
    fun isScheduledOn(routine: Routine, date: LocalDate): Boolean {
        val start = routine.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (start != null && date.isBefore(start)) return false
        routine.endDate?.let { end -> runCatching { LocalDate.parse(end) }.getOrNull()?.let { if (date.isAfter(it)) return false } }
        return RoutineRepeat.isScheduled(
            mode = routine.repeatMode,
            daysMask = routine.daysMask,
            intervalDays = routine.repeatIntervalDays,
            monthDaysCsv = routine.repeatMonthDaysCsv,
            anchorDate = start,
            date = date
        )
    }

    /** 그날 예정된 루틴이 없으면 null(중립, 스트릭 계산에서 건너뜀), 하나라도 미완료면 false, 전부 완료면 true. */
    private fun dayResult(routines: List<Routine>, completedByRoutine: Map<Long, Set<String>>, date: LocalDate): Boolean? {
        val scheduled = routines.filter { isScheduledOn(it, date) }
        if (scheduled.isEmpty()) return null
        val key = date.toString()
        return scheduled.all { key in (completedByRoutine[it.id] ?: emptySet()) }
    }

    /**
     * 지금 진행 중인 스트릭. 예정 없는 날은 건너뛰고, 못 채운 날은 그 주의 방지권([freezePerWeek]일)까지는
     * 넘어가며 그다음부터 끊긴다. [todayPending]이 true면 아직 못 채운 오늘은 세지 않는다.
     */
    fun currentStreak(
        routines: List<Routine>,
        completedByRoutine: Map<Long, Set<String>>,
        today: LocalDate,
        freezePerWeek: Int,
        todayPending: Boolean = true
    ): Int = RoutineStreak.current(today, freezePerWeek, todayPending) { dayResult(routines, completedByRoutine, it) }

    /** 지금까지 통틀어 가장 길었던 스트릭(최고 기록). */
    fun bestStreak(
        routines: List<Routine>,
        completedByRoutine: Map<Long, Set<String>>,
        today: LocalDate,
        freezePerWeek: Int
    ): Int = RoutineStreak.best(today, freezePerWeek) { dayResult(routines, completedByRoutine, it) }

    /** 이번 주(월~어제)에 이미 쓴 방지권 수. */
    fun freezeUsedThisWeek(
        routines: List<Routine>,
        completedByRoutine: Map<Long, Set<String>>,
        today: LocalDate,
        freezePerWeek: Int
    ): Int = RoutineStreak.usedThisWeek(today, freezePerWeek) { dayResult(routines, completedByRoutine, it) }
}
