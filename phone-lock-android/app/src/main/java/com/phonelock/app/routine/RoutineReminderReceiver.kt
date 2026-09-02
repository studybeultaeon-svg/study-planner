package com.phonelock.app.routine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.phonelock.shared.routine.RoutineQuotes
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.*
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

// "무작위 알림"(처지는 멤버 정보 알림, 81차) 전용 채널 — 루틴 리마인더와 성격이 달라 사용자가 따로
// 켜고 끌 수 있도록 별도 채널로 뒀다.
private const val SLACKING_MEMBER_CHANNEL_ID = "group_slacking_member_v1"
private const val SLACKING_MEMBER_NOTIFICATION_ID_BASE = 35000

// 주간 요약 알림(82차, §9) 전용 채널.
private const val WEEKLY_SUMMARY_CHANNEL_ID = "weekly_summary_v1"
private const val WEEKLY_SUMMARY_NOTIFICATION_ID = 39999

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
                    RoutineAlarmScheduler.scheduleWeeklySummary(appContext)
                }
            }
            RoutineAlarmScheduler.ACTION_ROUTINE_REMINDER -> {
                val routineId = intent.getLongExtra(RoutineAlarmScheduler.EXTRA_ROUTINE_ID, -1L)
                if (routineId < 0) return
                runAsync {
                    val repository = PhoneLockRepository(appContext)
                    val routine = repository.getRoutines().find { it.id == routineId }
                    if (routine != null && routine.notifyEnabled) {
                        com.phonelock.app.service.StudyNotificationGate.showOrQueue(
                            appContext, repository,
                            NOTIFICATION_ID_BASE + (routineId % 10000).toInt(),
                            CHANNEL_ID,
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
                        com.phonelock.app.service.StudyNotificationGate.showOrQueue(
                            appContext, repository, STREAK_NOTIFICATION_ID, CHANNEL_ID, "🌱 루틴 스트릭", message
                        )
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
                    runCatching { checkAndNotifySlackingMembers(appContext, prefs) }
                    prefs.lastGroupNudgeCheckDate = today
                }
                RoutineAlarmScheduler.scheduleGroupNudgeCheck(appContext)
            }
            RoutineAlarmScheduler.ACTION_WEEKLY_SUMMARY -> runAsync {
                runCatching { sendWeeklySummary(appContext) }
                RoutineAlarmScheduler.scheduleWeeklySummary(appContext)
            }
        }
    }

    /**
     * 주간 요약 알림(82차, §9) — 이번 주(오늘 포함 최근 7일) 루틴 완료율/공부 총 시간/계산기 평균 진척도를
     * 한 알림으로 요약한다. 새 집계 로직 없이 이미 있는 함수만 조합(판정 로직과 무관, 순수 통계).
     */
    private suspend fun sendWeeklySummary(context: Context) {
        val repository = PhoneLockRepository(context)
        ensureWeeklySummaryChannel(context)
        val today = LocalDate.now()
        val weekAgo = today.minusDays(6)

        val routines = repository.getRoutines()
        val completed = routines.associate { it.id to repository.getRoutineCompletedDateKeys(it.id) }
        var scheduledCount = 0
        var doneCount = 0
        for (i in 0..6) {
            val d = weekAgo.plusDays(i.toLong())
            routines.filter { RoutineEngine.isScheduledOn(it, d) }.forEach { r ->
                scheduledCount++
                if (d.toString() in (completed[r.id] ?: emptySet())) doneCount++
            }
        }
        val routineRate = if (scheduledCount > 0) Math.round(doneCount * 100.0 / scheduledCount).toInt() else 0

        val studySeconds = repository.getAllStudyLogOnce()
            .filter { it.dateKey in weekAgo.toString()..today.toString() }
            .sumOf { it.seconds }
        val studyHours = studySeconds / 3600.0

        val calcTasks = repository.getCalcTasks().filter { it.qty.toDoubleOrNull()?.let { q -> q > 0 } == true }
        val avgCalcProgress = if (calcTasks.isNotEmpty()) {
            calcTasks.map { ((it.progress.toDoubleOrNull() ?: 0.0) / (it.qty.toDoubleOrNull() ?: 1.0) * 100).coerceIn(0.0, 100.0) }.average()
        } else null

        val message = buildString {
            append("루틴 완료율 $routineRate%($doneCount/$scheduledCount)")
            append(" · 공부 %.1f시간".format(studyHours))
            if (avgCalcProgress != null) append(" · 계산기 평균 진척도 ${Math.round(avgCalcProgress)}%")
        }
        com.phonelock.app.service.StudyNotificationGate.showOrQueue(
            context, repository, WEEKLY_SUMMARY_NOTIFICATION_ID, WEEKLY_SUMMARY_CHANNEL_ID, "📅 이번 주 요약", message
        )
    }

    /**
     * "무작위 알림"(77차, 81차에 의도 정정) — 내가 속한 모임(무작위 알림을 켜둔 모임만)의 멤버들을
     * 훑어, 오늘 예정된 루틴 중 안 한 게 있거나 오늘 캘린더 일정 중 완료(O) 안 된 게 있는 사람이
     * 있으면 나(이 알림을 받는 사람)에게 "OO님이 아직 할 일을 안 했어요"라고만 알려준다. 실제로
     * 깨울지는 알림을 본 내가 직접 판단해서 모임 화면에서 😴 깨우기를 눌러야 한다 — 77차 최초 구현은
     * 대신 넛지를 자동으로 보내버렸는데, 이건 "본인이 알림을 받고 스스로 깨우러 가게" 하려던 원래
     * 의도와 달랐다고 81차에 사용자가 바로잡았다. 상대가 그 항목을 공유 안 했으면(null) 판단할
     * 데이터가 없으므로 건너뛴다. 일회성 알림이라 공부 중이면 [StudyNotificationGate]가 큐에 쌓아뒀다가
     * 공부가 끝나면 다시 띄운다.
     */
    private suspend fun checkAndNotifySlackingMembers(context: Context, prefs: AppPreferences) {
        val repository = PhoneLockRepository(context)
        val myUid = com.phonelock.app.service.AuthManager.currentUser?.uid ?: return
        val today = LocalDate.now().toString()
        ensureSlackingMemberChannel(context)
        var index = 0
        repository.readMySocialGroupIds().forEach groupLoop@{ groupId ->
            if (!prefs.randomNudgeEnabledFor(groupId)) return@groupLoop
            val stats = repository.readSocialGroupStats(groupId)
            stats.forEach memberLoop@{ member ->
                if (member.uid == myUid) return@memberLoop
                val routineIncomplete = member.routines?.any { !it.doneToday } ?: false
                val scheduleIncomplete = member.schedule?.any { it.dateKey == today && it.status != "O" } ?: false
                if (routineIncomplete || scheduleIncomplete) {
                    com.phonelock.app.service.StudyNotificationGate.showOrQueue(
                        context, repository,
                        SLACKING_MEMBER_NOTIFICATION_ID_BASE + index,
                        SLACKING_MEMBER_CHANNEL_ID,
                        "🔔 ${member.displayName}님이 아직 할 일을 안 했어요",
                        "모임에서 확인하고, 필요하면 직접 깨워주세요"
                    )
                    index++
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

    private fun ensureWeeklySummaryChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(WEEKLY_SUMMARY_CHANNEL_ID) == null) {
                val channel = NotificationChannel(WEEKLY_SUMMARY_CHANNEL_ID, "주간 요약", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    enableVibration(true)
                    vibrationPattern = VIBRATE_PATTERN
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun ensureSlackingMemberChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(SLACKING_MEMBER_CHANNEL_ID) == null) {
                val channel = NotificationChannel(SLACKING_MEMBER_CHANNEL_ID, "모임 무작위 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = VIBRATE_PATTERN
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
