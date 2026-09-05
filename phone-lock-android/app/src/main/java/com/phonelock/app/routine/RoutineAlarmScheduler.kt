package com.phonelock.app.routine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.*
import com.phonelock.app.data.Routine
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 루틴 알림(52차, IDEAS.md 요청) 스케줄러. 처음엔 부정확 알람(setAndAllowWhileIdle)을 썼으나
 * Doze 상태에서 실제로 2분가량 지연 전달되는 게 확인돼(56차), 권한이 허용된 경우
 * setExactAndAllowWhileIdle로 전환. SCHEDULE_EXACT_ALARM은 Android 13+부터 기본 거부되므로
 * canScheduleExactAlarms()로 확인 후 없으면 기존 부정확 알람으로 자동 폴백(크래시 방지, 설정
 * 화면에서 권한 허용 유도는 SettingsScreen.kt 참고). requestCode는 routine.id(항상 양수, Room
 * autoGenerate)를 그대로 쓰고, 스트릭 알림(루틴별이 아닌 전역)은 -1로 예약해 겹치지 않게 한다.
 */
object RoutineAlarmScheduler {
    const val ACTION_ROUTINE_REMINDER = "com.phonelock.app.ACTION_ROUTINE_REMINDER"
    const val ACTION_STREAK_CHECK = "com.phonelock.app.ACTION_STREAK_CHECK"
    const val ACTION_GROUP_NUDGE_CHECK = "com.phonelock.app.ACTION_GROUP_NUDGE_CHECK"
    const val ACTION_WEEKLY_SUMMARY = "com.phonelock.app.ACTION_WEEKLY_SUMMARY"
    const val EXTRA_ROUTINE_ID = "routineId"
    private const val STREAK_REQUEST_CODE = -1
    private const val GROUP_NUDGE_REQUEST_CODE = -2
    private const val WEEKLY_SUMMARY_REQUEST_CODE = -3

    private fun alarmManager(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun scheduleAlarm(context: Context, triggerAtMillis: Long, pendingIntent: PendingIntent) {
        val manager = alarmManager(context)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        // 앱당 예약 알람 500개 한도(IllegalStateException)에 걸려도 앱 전체가 죽지 않게 흡수한다 —
        // 2026-08-30에 실제로 이 예외가 잡히지 않아 앱이 열자마자 계속 죽는 크래시 루프가 있었다.
        // 근본 원인(루틴 동기화 시 옛 ID의 알람이 취소 안 되고 계속 쌓이던 버그)은 별도로 고쳤지만,
        // 이 한도 자체는 다른 경로로도 걸릴 수 있으니 방어는 남겨둔다.
        runCatching {
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        }
    }

    /** 2026-08-30 발견된 알람 누수(루틴 동기화 때마다 옛 Room ID의 예약 알람이 취소되지 않고 쌓이던 버그)로
     *  이미 기기에 쌓여있는 알람을 한 번 정리한다 — Room ID는 순차 정수(autoIncrement)이므로 있음직한
     *  범위를 넉넉히 훑어 전부 취소 시도한다(존재하지 않는 걸 취소해도 예외 없이 조용히 무시됨). 앱
     *  실행마다 반복할 필요는 없어 [AppPreferences.leakedAlarmsCleaned]로 한 번만 수행한다. */
    fun cleanupLeakedAlarmsIfNeeded(context: Context, prefs: com.phonelock.app.data.AppPreferences) {
        if (prefs.leakedAlarmsCleaned) return
        for (id in 1..20000) {
            cancel(context, id.toLong())
        }
        prefs.leakedAlarmsCleaned = true
    }

    private fun pendingIntentFor(context: Context, requestCode: Int, action: String, routineId: Long? = null): PendingIntent {
        val intent = Intent(context, RoutineReminderReceiver::class.java).apply {
            this.action = action
            if (routineId != null) putExtra(EXTRA_ROUTINE_ID, routineId)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** routine의 다음 알림 시각(오늘 포함 최대 8일 이내에서 요일마스크+기간을 만족하는 가장 가까운 날)을
     *  찾아 예약한다. 알림이 꺼져있거나 시간대 미지정, 또는 8일 안에 해당하는 날이 없으면 기존 예약만 취소. */
    fun scheduleNext(context: Context, routine: Routine) {
        if (!routine.notifyEnabled || routine.timeSlot == null) {
            cancel(context, routine.id)
            return
        }
        val time = runCatching {
            val parts = routine.timeSlot.split(":")
            LocalTime.of(parts[0].trim().toInt(), parts[1].trim().toInt())
        }.getOrNull()
        if (time == null) {
            cancel(context, routine.id)
            return
        }
        val now = LocalDateTime.now()
        for (i in 0..7) {
            val date = now.toLocalDate().plusDays(i.toLong())
            if (!RoutineEngine.isScheduledOn(routine, date)) continue
            val candidate = LocalDateTime.of(date, time)
            if (candidate.isBefore(now)) continue
            val triggerAtMillis = candidate.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pendingIntent = pendingIntentFor(context, routine.id.toInt(), ACTION_ROUTINE_REMINDER, routine.id)
            scheduleAlarm(context, triggerAtMillis, pendingIntent)
            return
        }
        cancel(context, routine.id)
    }

    fun cancel(context: Context, routineId: Long) {
        alarmManager(context).cancel(pendingIntentFor(context, routineId.toInt(), ACTION_ROUTINE_REMINDER, routineId))
    }

    /** 부팅 직후/앱 시작 시 알림 켜진 루틴 전부를 다시 예약한다(예약은 재부팅 시 초기화되므로 필수). */
    suspend fun rescheduleAll(context: Context, repository: PhoneLockRepository) {
        repository.getRoutines().forEach { scheduleNext(context, it) }
    }

    /** 스트릭 알림(전역, 루틴별 아님)을 하루에 한 번, 완전히 랜덤한 시각에 예약한다(58차 사용자 요청 —
     *  기존엔 dailyResetHour 정각 고정이었으나 예측 가능해서 매번 하루 중 아무 시각이나 고르도록 변경). */
    fun scheduleStreakCheck(context: Context) {
        val now = LocalDateTime.now()
        var candidate = LocalDateTime.of(now.toLocalDate(), LocalTime.of((0..23).random(), (0..59).random()))
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        val triggerAtMillis = candidate.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pendingIntent = pendingIntentFor(context, STREAK_REQUEST_CODE, ACTION_STREAK_CHECK)
        scheduleAlarm(context, triggerAtMillis, pendingIntent)
    }

    fun cancelStreakCheck(context: Context) {
        alarmManager(context).cancel(pendingIntentFor(context, STREAK_REQUEST_CODE, ACTION_STREAK_CHECK))
    }

    /** "무작위 알림"(77차) — 스트릭 알림과 동일한 패턴으로 하루 한 번, 완전히 랜덤한 시각에 예약한다. */
    fun scheduleGroupNudgeCheck(context: Context) {
        val now = LocalDateTime.now()
        var candidate = LocalDateTime.of(now.toLocalDate(), LocalTime.of((0..23).random(), (0..59).random()))
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        val triggerAtMillis = candidate.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pendingIntent = pendingIntentFor(context, GROUP_NUDGE_REQUEST_CODE, ACTION_GROUP_NUDGE_CHECK)
        scheduleAlarm(context, triggerAtMillis, pendingIntent)
    }

    fun cancelGroupNudgeCheck(context: Context) {
        alarmManager(context).cancel(pendingIntentFor(context, GROUP_NUDGE_REQUEST_CODE, ACTION_GROUP_NUDGE_CHECK))
    }

    /** 주간 요약 알림(82차, §9) — 매주 일요일 20시(다음 세션에서 정확한 시각 요청받으면 조정). 이미 지났으면 다음 주 일요일. */
    fun scheduleWeeklySummary(context: Context) {
        val now = LocalDateTime.now()
        var candidate = LocalDateTime.of(now.toLocalDate(), LocalTime.of(20, 0))
        while (candidate.dayOfWeek != java.time.DayOfWeek.SUNDAY || !candidate.isAfter(now)) {
            candidate = candidate.plusDays(1)
        }
        val triggerAtMillis = candidate.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pendingIntent = pendingIntentFor(context, WEEKLY_SUMMARY_REQUEST_CODE, ACTION_WEEKLY_SUMMARY)
        scheduleAlarm(context, triggerAtMillis, pendingIntent)
    }

    fun cancelWeeklySummary(context: Context) {
        alarmManager(context).cancel(pendingIntentFor(context, WEEKLY_SUMMARY_REQUEST_CODE, ACTION_WEEKLY_SUMMARY))
    }
}
