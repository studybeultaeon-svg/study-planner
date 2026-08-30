package com.phonelock.app.routine

import com.phonelock.app.data.Routine
import java.time.LocalDate

/**
 * 루틴앱 스트릭 순수 계산 로직 — 51차 전면 개편: 루틴별 스트릭(+방어권)이 아니라 "하루" 단위 전역
 * 스트릭으로 바뀜(그날 예정된 루틴을 전부 완료하면 그날 +1, 하나라도 미완료면 그 자리에서 0으로 끊김,
 * 방어권 없음). 데스크탑 RoutineEngine.kt와 동일 로직(플랫폼 공유 모듈이 없어 대칭 복제).
 */
object RoutineEngine {

    private fun bitIndexFor(date: LocalDate): Int = date.dayOfWeek.value - 1
    /** 요일마스크+기간(52차)을 함께 고려해 이 날짜에 예정됐는지 판정 — 알림 스케줄러(RoutineAlarmScheduler)도 재사용. */
    fun isScheduledOn(routine: Routine, date: LocalDate): Boolean {
        routine.startDate?.let { if (date.isBefore(LocalDate.parse(it))) return false }
        routine.endDate?.let { if (date.isAfter(LocalDate.parse(it))) return false }
        return (routine.daysMask shr bitIndexFor(date)) and 1 == 1
    }

    /** 그날 예정된 루틴이 없으면 null(중립, 스트릭 계산에서 건너뜀), 하나라도 미완료면 false, 전부 완료면 true. */
    private fun dayResult(routines: List<Routine>, completedByRoutine: Map<Long, Set<String>>, date: LocalDate): Boolean? {
        val scheduled = routines.filter { isScheduledOn(it, date) }
        if (scheduled.isEmpty()) return null
        val key = date.toString()
        return scheduled.all { key in (completedByRoutine[it.id] ?: emptySet()) }
    }

    /** 오늘부터 과거로 훑어 지금 진행 중인 스트릭. 예정 없는 날은 건너뛰고, 하루라도 못 채우면 그 자리에서 끊긴다. */
    fun currentStreak(routines: List<Routine>, completedByRoutine: Map<Long, Set<String>>, today: LocalDate = LocalDate.now()): Int {
        var streak = 0
        for (i in 0 until 3650) {
            val date = today.minusDays(i.toLong())
            when (dayResult(routines, completedByRoutine, date)) {
                null -> continue
                true -> streak++
                false -> return streak
            }
        }
        return streak
    }

    /** 지금까지 통틀어 가장 길었던 스트릭(최고 기록). */
    fun bestStreak(
        routines: List<Routine>,
        completedByRoutine: Map<Long, Set<String>>,
        today: LocalDate = LocalDate.now(),
        lookbackDays: Int = 3650
    ): Int {
        var best = 0
        var current = 0
        for (i in lookbackDays downTo 0) {
            val date = today.minusDays(i.toLong())
            when (dayResult(routines, completedByRoutine, date)) {
                null -> {}
                true -> { current++; if (current > best) best = current }
                false -> current = 0
            }
        }
        return best
    }
}
