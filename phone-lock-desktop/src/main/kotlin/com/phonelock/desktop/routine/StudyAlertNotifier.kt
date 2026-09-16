package com.phonelock.desktop.routine

import com.phonelock.desktop.data.CalcTask
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.getAllCalendarTasks
import com.phonelock.desktop.data.getCalcTasks
import com.phonelock.desktop.data.isLinkedGoalAchieved
import com.phonelock.shared.calc.CalcEngine
import com.phonelock.shared.study.StudyAlertEngine
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 공부 알림(122차, 안드로이드판 `StudyAlertChecker`와 대칭) — 판정 규칙은 :shared의 [StudyAlertEngine]
 * 하나를 같이 쓴다. 데스크탑엔 AlarmManager 같은 예약 API가 없어 [WeeklySummaryNotifier]처럼 Main.kt의
 * 30초 루프에 얹되, 실제 검사는 [CHECK_INTERVAL_MS]마다만 한다(조건 기반 알림이라 자주 볼 필요가 없음).
 *
 * 과다 반복 방지 규칙은 안드로이드와 같다: 검사 1회에 알림 최대 1건, 같은 종류는 하루 한 번
 * (`Repository.lastStudyAlertDate`, data.json에 저장돼 앱을 다시 켜도 유지), 알림 가능 시간대 밖이면
 * 안 보냄, 이 기기에서 공부 타이머가 도는 중이면 건너뜀.
 */
object StudyAlertNotifier {
    /** 안드로이드판 검사 주기(3시간)보다 짧게 잡는다 — 데스크탑은 켜져 있는 시간이 짧은 편이라
     *  켜져 있는 동안 한 번은 확인되도록. 같은 종류는 어차피 하루 한 번만 나간다. */
    private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
    private var lastCheckAt = 0L

    fun tick(repository: Repository) {
        if (!repository.studyAlertEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < CHECK_INTERVAL_MS) return
        lastCheckAt = now
        checkAndNotify(repository, force = false)
    }

    /**
     * 조건을 검사하고 필요하면 트레이 알림을 띄운다. 반환값은 설정 화면의 "지금 한 번 확인" 버튼이 결과를
     * 보여주는 데 쓴다.
     *
     * @param force 설정 화면에서 직접 확인하는 경우 — 시간대와 "하루 한 번" 제한을 건너뛴다(켜고 끈
     *   항목 자체는 그대로 존중).
     */
    fun checkAndNotify(repository: Repository, force: Boolean): String {
        if (!repository.studyAlertEnabled) return "공부 알림이 꺼져 있습니다."
        if (!force && repository.isStudyLockActive()) return "공부 중이라 이번 검사는 건너뜁니다."

        val startHour = repository.studyAlertStartHour
        val endHour = repository.studyAlertEndHour
        if (!force && !StudyAlertEngine.isWithinWindow(LocalDateTime.now().hour, startHour, endHour)) {
            return "알림 가능 시간대(${startHour}시~${endHour}시)가 아니라 보내지 않습니다."
        }

        // "오늘"은 캘린더/공부 기록과 같은 dailyResetHour 기준.
        val today = LocalDate.parse(repository.todayCalendarDateKey())
        val settings = StudyAlertEngine.Settings(
            enabled = true,
            notStartedEnabled = repository.studyAlertNotStartedEnabled,
            paceEnabled = repository.studyAlertPaceEnabled,
            scheduleEnabled = repository.studyAlertScheduleEnabled,
            startHour = if (force) 0 else startHour,
            endHour = if (force) 0 else endHour
        )
        val alerts = StudyAlertEngine.evaluate(settings, buildSnapshot(repository, today))
        if (alerts.isEmpty()) return "지금은 보낼 알림이 없습니다(일정·진행 상황 이상 없음)."

        val todayKey = today.toString()
        val alert = (if (force) alerts.first()
        else alerts.firstOrNull { repository.lastStudyAlertDate(it.kind.name) != todayKey })
            ?: return "보낼 알림은 있지만 오늘 이미 보낸 종류라 건너뜁니다."

        DesktopNotifier.notify(alert.title, alert.text)
        repository.setLastStudyAlertDate(alert.kind.name, todayKey)
        return "알림을 보냈습니다: ${alert.title}"
    }

    private fun buildSnapshot(repository: Repository, today: LocalDate): StudyAlertEngine.Snapshot {
        val todayKey = today.toString()
        val allCalendar = repository.getAllCalendarTasks()
        val todayTasks = allCalendar.filter { it.dateKey == todayKey }
        val jsDow = CalcEngine.jsDow(today)

        val tasks = repository.getCalcTasks().mapNotNull { task ->
            val dday = runCatching { LocalDate.parse(task.dday) }.getOrNull() ?: return@mapNotNull null
            val quantity = task.qty.toDoubleOrNull() ?: return@mapNotNull null
            if (task.name.isBlank() || quantity <= 0.0) return@mapNotNull null
            val start = task.start.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
            // 오늘치 목표는 일정표 화면이 보여주는 것과 같은 값(요일별 할당량, 휴일·기간 밖이면 0).
            val quota = if (todayKey in task.holidays || today.isBefore(start) || today.isAfter(dday)) 0.0
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
}
