package com.phonelock.app.routine

import com.phonelock.shared.calc.CalcEngine
import com.phonelock.shared.study.StudyAlertEngine
import com.phonelock.shared.study.StudyAlertEngine.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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

    /** 기본값: 남은 5일(+오늘) 동안 하루 10씩, 즉 요일별 목표대로 하면 오늘부터 마감까지 60을 할 수 있는 업무. */
    private fun task(
        progress: Double,
        quantity: Double = 100.0,
        elapsed: Int = 5,
        left: Int = 5,
        quota: Double = 0.0,
        achieved: Boolean = true,
        unit: String = "쪽",
        plan: Double = 60.0,
        planDays: Int = 6,
        name: String = "수학"
    ) = StudyAlertEngine.TaskProgress(name, quantity, progress, elapsed, left, quota, achieved, unit, plan, planDays)

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
    fun `schedule alert fires when the weekly plan cannot finish before the deadline`() {
        // 남은 80을 요일별 목표(60)대로 하면 마감 때 20이 남음 — 하루치(10) 이상 모자람
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
    // ---- 134차: "지금 페이스면 마감까지 223.6만큼 모자랍니다" 회귀 ----

    @Test
    fun `plan shortfall message uses the plan, the unit and whole numbers`() {
        // 실사용 데이터 "생윤 기출": 380쪽 중 22쪽, 9/22(화)~11/16(월) 56일, 요일별 목표 주 41쪽(수 5, 나머지 6).
        // 예전 공식(22쪽 ÷ 9일 × 55일)은 "223.6만큼"을 냈다. 요일별 목표대로면 328쪽을 할 수 있어 30쪽이 남는다.
        val plan = CalcEngine.planWindow(
            LocalDate.of(2026, 9, 22), LocalDate.of(2026, 11, 16),
            mapOf(0 to 6.0, 1 to 6.0, 2 to 6.0, 3 to 5.0, 4 to 6.0, 5 to 6.0, 6 to 6.0), emptySet()
        )
        assertEquals(328.0, plan.capacity, 0.0001)
        assertEquals(56, plan.activeDays)
        val t = task(progress = 22.0, quantity = 380.0, elapsed = 9, left = 55, plan = plan.capacity, planDays = plan.activeDays, name = "생윤 기출")
        val alert = StudyAlertEngine.evaluate(allOn, snapshot(studied = 60, tasks = listOf(t))).single { it.kind == Kind.SCHEDULE_DELAYED }
        assertEquals(
            "'생윤 기출': 요일별 목표대로 해도 마감(D-55) 때 30쪽이 남아요. 제때 끝내려면 하루 평균 5.9쪽 → 6.4쪽으로 늘려야 해요.",
            alert.text
        )
    }

    @Test
    fun `a shortfall smaller than one day of the plan is not reported`() {
        // 남은 65 vs 요일별 목표 60 — 5 모자라지만 하루치(10)도 안 되니 내일 조금 더 하면 된다.
        val alerts = StudyAlertEngine.evaluate(allOn, snapshot(studied = 60, tasks = listOf(task(progress = 35.0))))
        assertFalse(alerts.any { it.kind == Kind.SCHEDULE_DELAYED })
    }

    @Test
    fun `a task that has not started yet is not flagged as slow`() {
        // 다음 주에 시작하는 업무 — 진행 0이지만 요일별 목표대로 하면 제때 끝난다(예전엔 전체 분량이 "모자람"으로 떴다).
        val future = task(progress = 0.0, elapsed = 0, left = 12, plan = 100.0, planDays = 10)
        val alerts = StudyAlertEngine.evaluate(allOn, snapshot(studied = 60, tasks = listOf(future)))
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `no goal days before the deadline is explained instead of a number`() {
        val stuck = task(progress = 90.0, left = 2, plan = 0.0, planDays = 0)
        val alert = StudyAlertEngine.evaluate(allOn, snapshot(studied = 60, tasks = listOf(stuck))).single { it.kind == Kind.SCHEDULE_DELAYED }
        assertTrue(alert.text, alert.text.contains("목표가 잡힌 날이 없어 남은 10쪽을 끝낼 수 없어요"))
    }

    @Test
    fun `korean particles follow the unit`() {
        val workbook = task(progress = 20.0, unit = "문제")
        assertTrue(StudyAlertEngine.evaluate(allOn, snapshot(studied = 60, tasks = listOf(workbook)))
            .first { it.kind == Kind.SCHEDULE_DELAYED }.text.contains("20문제가 남아요"))
        val lecture = task(progress = 20.0, unit = "강")
        val text = StudyAlertEngine.evaluate(allOn, snapshot(studied = 60, tasks = listOf(lecture)))
            .first { it.kind == Kind.SCHEDULE_DELAYED }.text
        assertTrue(text, text.contains("20강이 남아요") && text.contains("13.4강으로 늘려야"))
    }

    @Test
    fun `the task furthest behind in days of work is reported first`() {
        // A: 30쪽 모자람 ÷ 하루 10쪽 = 3일치, B: 5강 모자람 ÷ 하루 1강 = 5일치 → B가 더 급하다.
        val a = task(progress = 10.0, quantity = 100.0, plan = 60.0, planDays = 6, unit = "쪽", name = "A")
        val b = task(progress = 0.0, quantity = 11.0, plan = 6.0, planDays = 6, unit = "강", name = "B")
        val alert = StudyAlertEngine.evaluate(allOn, snapshot(studied = 60, tasks = listOf(a, b))).first { it.kind == Kind.SCHEDULE_DELAYED }
        assertTrue(alert.text, alert.text.startsWith("'B'"))
    }

    @Test
    fun `plan window skips holidays and counts only days with a goal`() {
        // 2026-09-21(월)~27(일), 평일 2 / 주말 0, 수요일(23일) 휴일 → 월·화·목·금 4일, 8.
        val plan = CalcEngine.planWindow(
            LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27),
            mapOf(0 to 0.0, 1 to 2.0, 2 to 2.0, 3 to 2.0, 4 to 2.0, 5 to 2.0, 6 to 0.0), setOf("2026-09-23")
        )
        assertEquals(8.0, plan.capacity, 0.0001)
        assertEquals(4, plan.activeDays)
        val empty = CalcEngine.planWindow(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 21), mapOf(1 to 2.0), emptySet())
        assertEquals(0, empty.activeDays)
    }
}
