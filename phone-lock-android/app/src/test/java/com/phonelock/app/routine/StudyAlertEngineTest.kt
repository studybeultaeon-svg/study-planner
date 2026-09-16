package com.phonelock.app.routine

import com.phonelock.shared.study.StudyAlertEngine
import com.phonelock.shared.study.StudyAlertEngine.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 공부 알림 판정 엔진(122차) 시나리오 테스트 — 엔진은 :shared 모듈의 순수 로직이라 안드로이드 앱의
 * 유닛테스트에서 그대로 검증한다(shared 모듈 자체엔 테스트 구성이 없음).
 */
class StudyAlertEngineTest {

    private val allOn = StudyAlertEngine.Settings(
        enabled = true, notStartedEnabled = true, paceEnabled = true, scheduleEnabled = true,
        startHour = 9, endHour = 22
    )

    private fun snapshot(
        hour: Int = 15,
        studied: Int = 0,
        calTotal: Int = 0,
        calDone: Int = 0,
        overdue: Int = 0,
        tasks: List<StudyAlertEngine.TaskProgress> = emptyList()
    ) = StudyAlertEngine.Snapshot(hour, studied, calTotal, calDone, overdue, tasks)

    private fun task(
        progress: Double,
        quantity: Double = 100.0,
        elapsed: Int = 5,
        left: Int = 5,
        quota: Double = 0.0,
        achieved: Boolean = true
    ) = StudyAlertEngine.TaskProgress("수학", quantity, progress, elapsed, left, quota, achieved)

    @Test
    fun `nothing is sent when everything is on track`() {
        val alerts = StudyAlertEngine.evaluate(allOn, snapshot(studied = 1800, calTotal = 2, calDone = 1, tasks = listOf(task(progress = 50.0))))
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `not started alert fires when calendar has work and nothing was studied`() {
        val alerts = StudyAlertEngine.evaluate(allOn, snapshot(calTotal = 3))
        assertEquals(Kind.NOT_STARTED, alerts.first().kind)
        assertTrue(alerts.first().text.contains("캘린더 3건"))
    }

    @Test
    fun `not started alert also counts unfinished timetable quota`() {
        val alerts = StudyAlertEngine.evaluate(allOn, snapshot(tasks = listOf(task(progress = 50.0, quota = 10.0, achieved = false))))
        assertEquals(Kind.NOT_STARTED, alerts.first().kind)
        assertTrue(alerts.first().text.contains("일정표 1건"))
    }

    @Test
    fun `not started alert is suppressed once any study time exists`() {
        val alerts = StudyAlertEngine.evaluate(allOn, snapshot(studied = 60, calTotal = 3))
        assertFalse(alerts.any { it.kind == Kind.NOT_STARTED })
    }

    @Test
    fun `pace alert fires when progress lags elapsed time by the tolerance`() {
        // 기간 50% 경과, 진행 20% → 30%p 뒤처짐
        val alerts = StudyAlertEngine.evaluate(allOn, snapshot(studied = 60, tasks = listOf(task(progress = 20.0))))
        assertTrue(alerts.any { it.kind == Kind.PACE_BEHIND })
    }

    @Test
    fun `pace alert does not fire for a small lag`() {
        // 기간 50% 경과, 진행 45% → 5%p (허용 오차 10%p 미만)
        val alerts = StudyAlertEngine.evaluate(allOn, snapshot(studied = 60, tasks = listOf(task(progress = 45.0))))
        assertFalse(alerts.any { it.kind == Kind.PACE_BEHIND })
    }

    @Test
    fun `schedule alert fires when current pace misses the deadline`() {
        // 5일 동안 20 → 하루 4, 남은 5일 → 40까지밖에 못 감(100 목표)
        val alerts = StudyAlertEngine.evaluate(allOn, snapshot(studied = 60, tasks = listOf(task(progress = 20.0))))
        assertTrue(alerts.any { it.kind == Kind.SCHEDULE_DELAYED })
    }

    @Test
    fun `schedule alert fires for overdue unfinished task`() {
        val alerts = StudyAlertEngine.evaluate(allOn, snapshot(studied = 60, tasks = listOf(task(progress = 90.0, elapsed = 10, left = -1))))
        val schedule = alerts.first { it.kind == Kind.SCHEDULE_DELAYED }
        assertTrue(schedule.title.contains("마감이 지난"))
    }

    @Test
    fun `schedule alert fires for overdue calendar entries`() {
        val alerts = StudyAlertEngine.evaluate(allOn, snapshot(studied = 60, overdue = 4))
        assertTrue(alerts.single().text.contains("4건"))
    }

    @Test
    fun `alerts are ordered not started then schedule then pace`() {
        val alerts = StudyAlertEngine.evaluate(allOn, snapshot(calTotal = 1, tasks = listOf(task(progress = 20.0))))
        assertEquals(listOf(Kind.NOT_STARTED, Kind.SCHEDULE_DELAYED, Kind.PACE_BEHIND), alerts.map { it.kind })
    }

    @Test
    fun `individual toggles are respected`() {
        val onlyPace = allOn.copy(notStartedEnabled = false, scheduleEnabled = false)
        val alerts = StudyAlertEngine.evaluate(onlyPace, snapshot(calTotal = 1, tasks = listOf(task(progress = 20.0))))
        assertEquals(listOf(Kind.PACE_BEHIND), alerts.map { it.kind })
    }

    @Test
    fun `master switch off sends nothing`() {
        val alerts = StudyAlertEngine.evaluate(allOn.copy(enabled = false), snapshot(calTotal = 3, overdue = 3))
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `nothing is sent outside the allowed time window`() {
        assertTrue(StudyAlertEngine.evaluate(allOn, snapshot(hour = 23, calTotal = 3)).isEmpty())
        assertTrue(StudyAlertEngine.evaluate(allOn, snapshot(hour = 8, calTotal = 3)).isEmpty())
        assertFalse(StudyAlertEngine.evaluate(allOn, snapshot(hour = 9, calTotal = 3)).isEmpty())
    }

    @Test
    fun `window crossing midnight is handled`() {
        assertTrue(StudyAlertEngine.isWithinWindow(23, 22, 6))
        assertTrue(StudyAlertEngine.isWithinWindow(2, 22, 6))
        assertFalse(StudyAlertEngine.isWithinWindow(12, 22, 6))
        assertTrue(StudyAlertEngine.isWithinWindow(12, 0, 0))
    }
}
