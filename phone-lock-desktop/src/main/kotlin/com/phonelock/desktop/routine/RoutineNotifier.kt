package com.phonelock.desktop.routine

import com.phonelock.desktop.data.Repository
import java.time.LocalDateTime

/**
 * 루틴 알림(52차) 데스크탑판 — 안드로이드는 AlarmManager로 정확한 시각에 예약하지만, 데스크탑은 별도
 * 예약 API 없이 Main.kt의 30초 주기 틱에서 이 tick()을 호출해 "지금이 알림 시각인가"를 직접 비교한다.
 * 인메모리로만 중복 방지(앱을 그 순간에 재시작하면 그날 한 번 더 울릴 수 있지만 실사용에서 거의 안 벌어짐).
 */
object RoutineNotifier {
    private val notifiedRoutineToday = mutableSetOf<Pair<Long, String>>()
    private var lastStreakNotifyDate: String? = null

    fun tick(repository: Repository) {
        val now = LocalDateTime.now()
        val nowHm = "%02d:%02d".format(now.hour, now.minute)
        val today = now.toLocalDate()
        val todayKey = today.toString()

        repository.getRoutines().forEach { routine ->
            if (!routine.notifyEnabled || routine.timeSlot != nowHm) return@forEach
            if (!RoutineEngine.isScheduledOn(routine, today)) return@forEach
            val key = routine.id to todayKey
            if (key in notifiedRoutineToday) return@forEach
            notifiedRoutineToday.add(key)
            DesktopNotifier.notify(
                if (routine.icon.isNotBlank()) "${routine.icon} ${routine.title}" else routine.title,
                "루틴 시간이에요"
            )
        }
        if (notifiedRoutineToday.size > 500) notifiedRoutineToday.clear()

        if (!repository.routineStreakNotifyEnabled) return
        if (now.hour == repository.dailyResetHour.coerceIn(0, 23) && now.minute == 0 && lastStreakNotifyDate != todayKey) {
            lastStreakNotifyDate = todayKey
            val routines = repository.getRoutines()
            val completed = routines.associate { it.id to repository.getRoutineCompletedDateKeys(it.id) }
            val streak = RoutineEngine.currentStreak(routines, completed, today.minusDays(1))
            val broken = repository.lastRoutineStreak > 0 && streak == 0
            DesktopNotifier.notify("🌱 루틴 스트릭", RoutineQuotes.forStreak(streak, broken))
            repository.lastRoutineStreak = streak
        }
    }
}
