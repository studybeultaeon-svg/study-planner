package com.phonelock.desktop.routine

import com.phonelock.desktop.data.Repository
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 루틴 알림(52차) 데스크탑판 — 안드로이드는 AlarmManager로 정확한 시각에 예약하지만, 데스크탑은 별도
 * 예약 API 없이 Main.kt의 30초 주기 틱에서 이 tick()을 호출해 "지금이 알림 시각인가"를 직접 비교한다.
 * 인메모리로만 중복 방지(앱을 그 순간에 재시작하면 그날 한 번 더 울릴 수 있지만 실사용에서 거의 안 벌어짐).
 *
 * 58차: 스트릭 알림 시각을 dailyResetHour 정각 고정에서 하루 중 랜덤 시각으로 변경(사용자 요청). 기존엔
 * "정각 == 지금 분" 같은 정확한 일치 비교라 그 순간 앱이 안 떠 있으면 그날은 영영 못 울리는 취약점이 있었는데,
 * "목표 시각을 지났고 오늘 아직 안 보냈으면"(>=) 비교로 바꿔 안드로이드 알람 폴백만큼 안정적으로 만들었다.
 */
object RoutineNotifier {
    private val notifiedRoutineToday = mutableSetOf<Pair<Long, String>>()
    private var lastStreakNotifyDate: String? = null
    private var nextStreakCheckAt: LocalDateTime? = null

    private fun randomTimeAfter(now: LocalDateTime): LocalDateTime {
        var candidate = LocalDateTime.of(now.toLocalDate(), LocalTime.of((0..23).random(), (0..59).random()))
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate
    }

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

        if (!repository.routineStreakNotifyEnabled) {
            nextStreakCheckAt = null
            return
        }
        val target = nextStreakCheckAt ?: randomTimeAfter(now).also { nextStreakCheckAt = it }
        if (!now.isBefore(target) && lastStreakNotifyDate != todayKey) {
            lastStreakNotifyDate = todayKey
            nextStreakCheckAt = randomTimeAfter(now)
            val routines = repository.getRoutines()
            val completed = routines.associate { it.id to repository.getRoutineCompletedDateKeys(it.id) }
            val streak = RoutineEngine.currentStreak(routines, completed, today.minusDays(1))
            val message: String
            if (streak > 0) {
                message = RoutineQuotes.forStreak(streak, broken = false)
                repository.zeroStreakDays = 0
            } else {
                val broken = repository.lastRoutineStreak > 0
                repository.zeroStreakDays = if (broken) 0 else repository.zeroStreakDays + 1
                message = RoutineQuotes.forZeroStreak(repository.zeroStreakDays, broken)
            }
            DesktopNotifier.notify("🌱 루틴 스트릭", message)
            repository.lastRoutineStreak = streak
        }
    }
}
