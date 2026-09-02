package com.phonelock.desktop.routine

import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 주간 요약 알림(82차, §9, 안드로이드판과 대칭) — 매주 일요일 20시, 이번 주 루틴 완료율/공부 총 시간/
 * 계산기 평균 진척도를 로컬 알림으로 요약한다. [SocialGroupNotifier]와 같은 tick() 구조로 Main.kt의
 * 30초 주기 루프에 얹는다.
 */
object WeeklySummaryNotifier {
    private var lastSentDate: String? = null

    fun tick(repository: Repository) {
        val now = LocalDateTime.now()
        if (now.dayOfWeek != DayOfWeek.SUNDAY || now.toLocalTime().isBefore(LocalTime.of(20, 0))) return
        val todayKey = now.toLocalDate().toString()
        if (lastSentDate == todayKey) return
        lastSentDate = todayKey

        val today = now.toLocalDate()
        val weekAgo = today.minusDays(6)

        val routines = repository.getRoutines()
        var scheduledCount = 0
        var doneCount = 0
        for (i in 0..6) {
            val d = weekAgo.plusDays(i.toLong())
            routines.filter { RoutineEngine.isScheduledOn(it, d) }.forEach { r ->
                scheduledCount++
                if (d.toString() in repository.getRoutineCompletedDateKeys(r.id)) doneCount++
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
        DesktopNotifier.notify("📅 이번 주 요약", message)
    }
}
