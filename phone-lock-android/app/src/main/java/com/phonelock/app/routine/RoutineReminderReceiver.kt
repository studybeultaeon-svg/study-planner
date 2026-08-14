package com.phonelock.app.routine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.phonelock.app.R
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

// v2: 기존 "routine_reminder" 채널은 IMPORTANCE_DEFAULT로 이미 만들어진 기기가 많아 코드에서
// importance를 올려도 반영 안 됨(안드로이드 정책 — 채널 생성 후엔 앱이 재정의 못 하고 사용자가 시스템
// 설정에서 직접 바꿔야 함). 사용자 요청(2026-08-14, 플로팅 바+진동)으로 새 채널 ID를 발급해 우회.
private const val CHANNEL_ID = "routine_reminder_v2"
private const val NOTIFICATION_ID_BASE = 20000
private const val STREAK_NOTIFICATION_ID = 29999
private val VIBRATE_PATTERN = longArrayOf(0, 250, 150, 250)

/**
 * 루틴 알림(52차, IDEAS.md 요청) — 부팅 후 재예약(ACTION_BOOT_COMPLETED)과 실제 알람 발화
 * (ACTION_ROUTINE_REMINDER/ACTION_STREAK_CHECK)를 한 리시버가 함께 처리한다(위젯 토글 리시버와
 * 같은 goAsync+코루틴 패턴, RoutineWidgetToggleReceiver.kt 참고).
 */
class RoutineReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> runAsync {
                val repository = PhoneLockRepository(appContext)
                RoutineAlarmScheduler.rescheduleAll(appContext, repository)
                if (AppPreferences(appContext).routineStreakNotifyEnabled) {
                    RoutineAlarmScheduler.scheduleStreakCheck(appContext, repository.dailyResetHour)
                }
            }
            RoutineAlarmScheduler.ACTION_ROUTINE_REMINDER -> {
                val routineId = intent.getLongExtra(RoutineAlarmScheduler.EXTRA_ROUTINE_ID, -1L)
                if (routineId < 0) return
                runAsync {
                    val repository = PhoneLockRepository(appContext)
                    val routine = repository.getRoutines().find { it.id == routineId }
                    if (routine != null && routine.notifyEnabled) {
                        notify(
                            appContext,
                            NOTIFICATION_ID_BASE + (routineId % 10000).toInt(),
                            if (routine.icon.isNotBlank()) "${routine.icon} ${routine.title}" else routine.title,
                            "루틴 시간이에요"
                        )
                        RoutineAlarmScheduler.scheduleNext(appContext, routine)
                    }
                }
            }
            RoutineAlarmScheduler.ACTION_STREAK_CHECK -> runAsync {
                val prefs = AppPreferences(appContext)
                if (prefs.routineStreakNotifyEnabled) {
                    val repository = PhoneLockRepository(appContext)
                    val today = LocalDate.now().toString()
                    if (prefs.lastRoutineStreakNotifyDate != today) {
                        val routines = repository.getRoutines()
                        val completed = routines.associate { it.id to repository.getRoutineCompletedDateKeys(it.id) }
                        val streak = RoutineEngine.currentStreak(routines, completed, LocalDate.now().minusDays(1))
                        val broken = prefs.lastRoutineStreak > 0 && streak == 0
                        notify(appContext, STREAK_NOTIFICATION_ID, "🌱 루틴 스트릭", RoutineQuotes.forStreak(streak, broken))
                        prefs.lastRoutineStreak = streak
                        prefs.lastRoutineStreakNotifyDate = today
                    }
                    RoutineAlarmScheduler.scheduleStreakCheck(appContext, repository.dailyResetHour)
                }
            }
        }
    }

    private fun runAsync(block: suspend () -> Unit) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                block()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                // IMPORTANCE_HIGH라야 화면 위에 플로팅(헤드업)으로 뜬다 — DEFAULT는 알림창에만 조용히 쌓인다.
                val channel = NotificationChannel(CHANNEL_ID, "루틴 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = VIBRATE_PATTERN
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, id, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            // Android 8 미만은 채널이 아니라 이 값들을 직접 본다 — 채널 설정과 중복이어도 안전.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(VIBRATE_PATTERN)
            .build()
        manager.notify(id, notification)
    }
}
