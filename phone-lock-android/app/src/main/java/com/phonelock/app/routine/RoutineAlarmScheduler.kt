package com.phonelock.app.routine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.phonelock.app.data.PhoneLockRepository
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
    const val EXTRA_ROUTINE_ID = "routineId"
    private const val STREAK_REQUEST_CODE = -1

    private fun alarmManager(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun scheduleAlarm(context: Context, triggerAtMillis: Long, pendingIntent: PendingIntent) {
        val manager = alarmManager(context)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (canExact) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
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

    /** 스트릭 알림(전역, 루틴별 아님)을 dailyResetHour 정각에 매일 예약한다. */
    fun scheduleStreakCheck(context: Context, hour: Int) {
        val now = LocalDateTime.now()
        var candidate = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour.coerceIn(0, 23), 0))
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        val triggerAtMillis = candidate.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pendingIntent = pendingIntentFor(context, STREAK_REQUEST_CODE, ACTION_STREAK_CHECK)
        scheduleAlarm(context, triggerAtMillis, pendingIntent)
    }

    fun cancelStreakCheck(context: Context) {
        alarmManager(context).cancel(pendingIntentFor(context, STREAK_REQUEST_CODE, ACTION_STREAK_CHECK))
    }
}
