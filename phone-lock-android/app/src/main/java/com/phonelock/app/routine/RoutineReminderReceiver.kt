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
            Intent.ACTION_BOOT_COMPLETED -> {
                // 재부팅되면 무전기 수신 포그라운드 서비스도 죽어있으므로 다시 띄워야 앱을 한 번도 안
                // 연 상태에서도 백그라운드 수신이 이어진다(모임별 켜짐 여부는 서비스 폴링 안에서 따로 확인).
                com.phonelock.app.service.WalkieTalkieService.start(appContext)
                runAsync {
                    val repository = PhoneLockRepository(appContext)
                    RoutineAlarmScheduler.rescheduleAll(appContext, repository)
                    if (AppPreferences(appContext).routineStreakNotifyEnabled) {
                        RoutineAlarmScheduler.scheduleStreakCheck(appContext)
                    }
                    RoutineAlarmScheduler.scheduleGroupNudgeCheck(appContext)
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
                        val message: String
                        if (streak > 0) {
                            message = RoutineQuotes.forStreak(streak, broken = false)
                            prefs.zeroStreakDays = 0
                        } else {
                            val broken = prefs.lastRoutineStreak > 0
                            prefs.zeroStreakDays = if (broken) 0 else prefs.zeroStreakDays + 1
                            message = RoutineQuotes.forZeroStreak(prefs.zeroStreakDays, broken)
                        }
                        notify(appContext, STREAK_NOTIFICATION_ID, "🌱 루틴 스트릭", message)
                        prefs.lastRoutineStreak = streak
                        prefs.lastRoutineStreakNotifyDate = today
                    }
                    RoutineAlarmScheduler.scheduleStreakCheck(appContext)
                }
            }
            RoutineAlarmScheduler.ACTION_GROUP_NUDGE_CHECK -> runAsync {
                val prefs = AppPreferences(appContext)
                val today = LocalDate.now().toString()
                if (prefs.lastGroupNudgeCheckDate != today) {
                    runCatching { checkAndSendGroupNudges(appContext, prefs) }
                    prefs.lastGroupNudgeCheckDate = today
                }
                RoutineAlarmScheduler.scheduleGroupNudgeCheck(appContext)
            }
        }
    }

    /**
     * "무작위 알림"(77차) — 내가 속한 모임(무작위 알림을 켜둔 모임만)의 멤버들을 훑어, 오늘 예정된
     * 루틴 중 안 한 게 있거나 오늘 캘린더 일정 중 완료(O) 안 된 게 있는 사람에게 자동으로 넛지를
     * 보낸다. 상대가 그 항목을 공유 안 했으면(null) 판단할 데이터가 없으므로 건너뛴다.
     */
    private suspend fun checkAndSendGroupNudges(context: Context, prefs: AppPreferences) {
        val repository = PhoneLockRepository(context)
        val myUid = com.phonelock.app.service.AuthManager.currentUser?.uid ?: return
        val today = LocalDate.now().toString()
        repository.readMySocialGroupIds().forEach groupLoop@{ groupId ->
            if (!prefs.randomNudgeEnabledFor(groupId)) return@groupLoop
            val stats = repository.readSocialGroupStats(groupId)
            stats.forEach memberLoop@{ member ->
                if (member.uid == myUid) return@memberLoop
                val routineIncomplete = member.routines?.any { !it.doneToday } ?: false
                val scheduleIncomplete = member.schedule?.any { it.dateKey == today && it.status != "O" } ?: false
                if (routineIncomplete || scheduleIncomplete) {
                    repository.sendSocialGroupNudge(groupId, member.uid)
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
