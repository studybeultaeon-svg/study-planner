package com.phonelock.app.routine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.phonelock.app.R
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.CalcTask
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.*
import com.phonelock.app.ui.MainActivity
import com.phonelock.shared.calc.CalcEngine
import com.phonelock.shared.study.StudyAlertEngine
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

// 진동 켜짐/꺼짐 채널을 따로 만든다 — 안드로이드는 채널을 한 번 만들고 나면 앱이 진동 설정을 다시
// 바꿀 수 없어서(사용자만 시스템 설정에서 바꿀 수 있음), 설정의 "진동" 토글을 실제로 동작하게 하려면
// 두 채널을 미리 만들어두고 발송 시점에 골라 쓰는 수밖에 없다(RoutineReminderReceiver의 채널 v2
// 우회와 같은 제약).
private const val CHANNEL_ID_VIBRATE = "study_alert_v1"
private const val CHANNEL_ID_SILENT = "study_alert_silent_v1"
private const val NOTIFICATION_ID = 37000
private val VIBRATE_PATTERN = longArrayOf(0, 250, 150, 250)

/**
 * 공부 알림(122차, 사용자 요청) — 캘린더/계산기(일정표)/공부 기록을 읽어 "지금 알려야 할 일이 있는지"를
 * 판정하고, 있으면 알림 하나를 띄운다. 정해진 간격으로 무조건 보내는 방식이 아니라 [StudyAlertEngine]의
 * 조건을 통과할 때만 보낸다.
 *
 * 과다 반복 방지 규칙:
 * - 한 번 검사에서 알림은 **최대 1건**(우선순위: 미실행 → 일정 지연 → 페이스 지연).
 * - 같은 종류는 **하루 한 번**만(`AppPreferences.lastStudyAlertDate`, dailyResetHour 기준 "오늘").
 * - 설정한 알림 가능 시간대 밖이면 아무것도 안 보낸다.
 * - 공부 중이면 검사 자체를 건너뛴다 — "공부를 안 했다"는 알림이 공부 중에 뜨는 건 틀렸고, 조건 기반
 *   알림이라 이번 검사를 놓쳐도 다음 검사에서 그대로 다시 판정된다(큐에 쌓아둘 필요가 없다 —
 *   [com.phonelock.app.service.StudyNotificationGate]의 일회성 알람 큐와는 성격이 다르다).
 *
 * 예약은 [RoutineAlarmScheduler.scheduleStudyAlertCheck]가 몇 시간 간격으로 걸고,
 * [RoutineReminderReceiver]가 발화 때마다 여기를 부른 뒤 다음 검사를 다시 예약한다.
 */
object StudyAlertChecker {

    /**
     * 조건을 검사하고 필요하면 알림을 띄운다. 돌아오는 문자열은 설정 화면의 "지금 확인" 버튼이 결과를
     * 그대로 보여주기 위한 것(알람 경로에서는 무시해도 된다).
     *
     * @param force 설정 화면에서 직접 눌러 확인하는 경우 — 알림 가능 시간대와 "하루 한 번" 제한을
     *   건너뛴다(켜고 끈 항목 자체는 그대로 존중한다). 자동 검사에서는 항상 false.
     */
    suspend fun checkAndNotify(context: Context, force: Boolean = false): String {
        val appContext = context.applicationContext
        val prefs = AppPreferences(appContext)
        if (!prefs.studyAlertEnabled) return "공부 알림이 꺼져 있습니다."

        val repository = PhoneLockRepository(appContext)
        if (!force && com.phonelock.app.service.StudyNotificationGate.isStudying(repository)) {
            return "공부 중이라 이번 검사는 건너뜁니다."
        }

        val startHour = prefs.studyAlertStartHour
        val endHour = prefs.studyAlertEndHour
        if (!force && !StudyAlertEngine.isWithinWindow(LocalDateTime.now().hour, startHour, endHour)) {
            return "알림 가능 시간대(${startHour}시~${endHour}시)가 아니라 보내지 않습니다."
        }

        val today = effectiveDate(repository.dailyResetHour)
        val snapshot = buildSnapshot(repository, today)
        val settings = StudyAlertEngine.Settings(
            enabled = true,
            notStartedEnabled = prefs.studyAlertNotStartedEnabled,
            paceEnabled = prefs.studyAlertPaceEnabled,
            scheduleEnabled = prefs.studyAlertScheduleEnabled,
            // force면 시작=종료(0시~0시 = 하루 종일)로 넘겨 엔진의 시간대 제한도 통과시킨다.
            startHour = if (force) 0 else startHour,
            endHour = if (force) 0 else endHour
        )
        val alerts = StudyAlertEngine.evaluate(settings, snapshot)
        if (alerts.isEmpty()) return "지금은 보낼 알림이 없습니다(일정·진행 상황 이상 없음)."

        val todayKey = today.toString()
        val alert = (if (force) alerts.first()
        else alerts.firstOrNull { prefs.lastStudyAlertDate(it.kind.name) != todayKey })
            ?: return "보낼 알림은 있지만 오늘 이미 보낸 종류라 건너뜁니다."

        ensureChannels(appContext)
        notify(appContext, alert, prefs.studyAlertVibrate)
        prefs.setLastStudyAlertDate(alert.kind.name, todayKey)
        return "알림을 보냈습니다: ${alert.title}"
    }

    private suspend fun buildSnapshot(repository: PhoneLockRepository, today: LocalDate): StudyAlertEngine.Snapshot {
        val todayKey = today.toString()
        val allCalendar = repository.getAllCalendarTasksOnce()
        val todayTasks = allCalendar.filter { it.dateKey == todayKey }
        val jsDow = CalcEngine.jsDow(today)

        val tasks = repository.getCalcTasks().mapNotNull { task ->
            val dday = runCatching { LocalDate.parse(task.dday) }.getOrNull() ?: return@mapNotNull null
            val quantity = task.qty.toDoubleOrNull() ?: return@mapNotNull null
            if (task.name.isBlank() || quantity <= 0.0) return@mapNotNull null
            val start = task.start.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
            val holidays = task.holidaysCsv.split(",").map { it.trim() }.toSet()
            // 오늘치 목표는 일정표 화면이 보여주는 것과 같은 값(요일별 할당량, 휴일·기간 밖이면 0).
            val quota = if (todayKey in holidays || today.isBefore(start) || today.isAfter(dday)) 0.0
            else dayQuota(task, jsDow)
            StudyAlertEngine.TaskProgress(
                name = task.name,
                quantity = quantity,
                progress = task.progress.toDoubleOrNull() ?: 0.0,
                daysElapsed = (ChronoUnit.DAYS.between(start, today).toInt() + 1).coerceAtLeast(1),
                daysLeft = ChronoUnit.DAYS.between(today, dday).toInt(),
                todayQuota = quota,
                todayAchieved = quota <= 0.0 || repository.isLinkedGoalAchieved(todayKey, task.name, quota)
            )
        }

        return StudyAlertEngine.Snapshot(
            hourOfDay = LocalDateTime.now().hour,
            studiedTodaySeconds = repository.getTodayStudyLog().sumOf { it.seconds },
            calendarTodayTotal = todayTasks.size,
            calendarTodayDone = todayTasks.count { it.status == "O" },
            calendarOverdue = allCalendar.count { it.dateKey < todayKey && it.status != "O" },
            tasks = tasks
        )
    }

    /** 요일별 할당량(일정표 화면의 dayValue와 같은 값). jsDow는 0=일 ~ 6=토. */
    private fun dayQuota(task: CalcTask, jsDow: Int): Double {
        val raw = when (jsDow) {
            0 -> task.sun
            1 -> task.mon
            2 -> task.tue
            3 -> task.wed
            4 -> task.thu
            5 -> task.fri
            else -> task.sat
        }
        return raw.toDoubleOrNull() ?: 0.0
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID_VIBRATE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_VIBRATE, "공부 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "공부 일정·진행 상황이 계획보다 늦어질 때 알려줍니다."
                    enableVibration(true)
                    vibrationPattern = VIBRATE_PATTERN
                }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ID_SILENT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_SILENT, "공부 알림(진동 없음)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "설정에서 진동을 끈 경우 이 채널로 알림이 옵니다."
                    enableVibration(false)
                }
            )
        }
    }

    private fun notify(context: Context, alert: StudyAlertEngine.Alert, vibrate: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        val pendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, if (vibrate) CHANNEL_ID_VIBRATE else CHANNEL_ID_SILENT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(if (vibrate) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        // Android 8 미만은 채널이 아니라 이 값을 직접 보므로 명시한다(다른 알림들과 같은 원칙).
        if (vibrate) builder.setVibrate(VIBRATE_PATTERN) else builder.setVibrate(longArrayOf(0))
        manager.notify(NOTIFICATION_ID, builder.build())
    }
}
