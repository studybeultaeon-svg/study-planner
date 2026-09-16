package com.phonelock.shared.study

/**
 * 공부 알림(122차, 사용자 요청) 판정 엔진 — "일정한 간격으로 무조건 보내는" 알림이 아니라, 실제 공부
 * 일정/진행 데이터를 보고 **필요할 때만** 보낼 내용을 고른다.
 *
 * 순수 함수라 안드로이드/데스크탑 어느 쪽에서도 같은 규칙으로 판정된다 — 플랫폼 코드가 하는 일은
 * 저장소에서 [Snapshot]을 만들어 넘기고, 돌아온 [Alert] 중 오늘 아직 안 보낸 것 하나를 띄우는 것뿐이다
 * (`StudyAlertChecker`(안드로이드) / `StudyAlertNotifier`(데스크탑) 참고).
 *
 * 판정에 쓰는 데이터:
 * - 캘린더(`CalendarTask`): 오늘 예정된 일정 수 / 완료(O) 수, 마감이 지났는데 미완료인 일정 수
 * - 일정표·할당량 계산기(`CalcTask`): 업무별 총량/진행량/기간과 오늘치 목표 달성 여부
 * - 공부 기록(`StudyLogEntry`): 오늘 실제로 공부한 시간
 */
object StudyAlertEngine {

    /** 진행률이 기간 경과율보다 이만큼 이상 뒤처지면 "페이스 지연"으로 본다(10%p). 오차/주말 편차로
     *  매일 알림이 뜨지 않도록 둔 여유값. */
    private const val PACE_TOLERANCE = 0.10

    enum class Kind {
        /** 오늘 예정된 공부가 있는데 아직 아무것도 안 했다. */
        NOT_STARTED,

        /** 계획 대비 진행량이 부족하다(진행률이 기간 경과율보다 뒤처짐). */
        PACE_BEHIND,

        /** 마감이 지났거나, 지금 페이스면 마감까지 못 끝낸다. */
        SCHEDULE_DELAYED
    }

    /**
     * 사용자 설정(설정 → 공부 → "공부 알림"). 전부 기기별 로컬 값이라 네트워크 상태와 무관하다 —
     * 동기화 대상이 아니므로 통신이 끊겨도 초기화되지 않는다.
     */
    data class Settings(
        val enabled: Boolean,
        val notStartedEnabled: Boolean,
        val paceEnabled: Boolean,
        val scheduleEnabled: Boolean,
        /** 알림 가능 시간대(시작 시각, 0~23). */
        val startHour: Int,
        /** 알림 가능 시간대(끝 시각, 0~23, 이 시각 정각부터는 안 보냄). */
        val endHour: Int
    )

    /**
     * 업무(할당량 계산기 = 일정표의 원천) 하나의 현재 상태. 비율/예측은 전부 이 엔진이 계산하므로
     * 플랫폼 쪽은 저장된 값을 그대로 꺼내 담기만 하면 된다.
     */
    data class TaskProgress(
        val name: String,
        /** 목표 총량(0 이하면 판정에서 제외). */
        val quantity: Double,
        /** 지금까지 진행한 양. */
        val progress: Double,
        /** 시작일부터 오늘까지 흐른 일수(오늘 포함, 최소 1). */
        val daysElapsed: Int,
        /** 오늘 이후 마감까지 남은 일수(오늘 제외). 음수면 마감이 지난 것. */
        val daysLeft: Int,
        /** 오늘 해야 할 양(요일별 할당량). 0이면 오늘은 예정 없음. */
        val todayQuota: Double,
        /** 오늘치 목표를 채웠는지(연동된 캘린더 일정 완료분 합 ≥ 오늘 할당량). */
        val todayAchieved: Boolean
    )

    data class Snapshot(
        /** 지금 시각(0~23) — 알림 가능 시간대 판정용. */
        val hourOfDay: Int,
        /** 오늘 실제 공부한 시간(초). */
        val studiedTodaySeconds: Int,
        val calendarTodayTotal: Int,
        val calendarTodayDone: Int,
        /** 날짜가 지났는데 아직 완료(O)가 아닌 캘린더 일정 수. */
        val calendarOverdue: Int,
        val tasks: List<TaskProgress>
    )

    data class Alert(val kind: Kind, val title: String, val text: String)

    /**
     * 지금 보낼 만한 알림을 **우선순위 순서**로 돌려준다(없으면 빈 목록). 호출부는 이 중에서 오늘 아직
     * 안 보낸 종류 하나만 띄운다 — 한 번에 여러 개를 쏟아내지 않기 위한 규칙이다.
     */
    fun evaluate(settings: Settings, snapshot: Snapshot): List<Alert> {
        if (!settings.enabled) return emptyList()
        if (!isWithinWindow(snapshot.hourOfDay, settings.startHour, settings.endHour)) return emptyList()

        val alerts = mutableListOf<Alert>()
        val scheduledToday = snapshot.calendarTodayTotal - snapshot.calendarTodayDone
        val timetableTodo = snapshot.tasks.count { it.todayQuota > 0.0 && !it.todayAchieved }

        // 1) 오늘 예정된 공부가 있는데 아직 아무것도 안 한 경우.
        if (settings.notStartedEnabled &&
            snapshot.studiedTodaySeconds <= 0 &&
            snapshot.calendarTodayDone == 0 &&
            (scheduledToday > 0 || timetableTodo > 0)
        ) {
            val parts = mutableListOf<String>()
            if (scheduledToday > 0) parts.add("캘린더 ${scheduledToday}건")
            if (timetableTodo > 0) parts.add("일정표 ${timetableTodo}건")
            alerts.add(
                Alert(
                    Kind.NOT_STARTED,
                    "📘 오늘 공부를 아직 시작하지 않았어요",
                    "${parts.joinToString(" · ")}이 오늘 예정되어 있어요."
                )
            )
        }

        // 2) 마감이 지났거나, 지금 페이스면 마감을 못 맞추는 업무.
        if (settings.scheduleEnabled) {
            val overdueTask = snapshot.tasks.firstOrNull { it.quantity > 0.0 && it.daysLeft < 0 && it.progress < it.quantity }
            val willMissTask = snapshot.tasks
                .filter { it.quantity > 0.0 && it.daysLeft >= 0 }
                .map { it to shortfallAtCurrentPace(it) }
                .filter { it.second > 0.0 }
                .maxByOrNull { it.second }
            when {
                overdueTask != null -> alerts.add(
                    Alert(
                        Kind.SCHEDULE_DELAYED,
                        "📉 마감이 지난 공부가 있어요",
                        "'${overdueTask.name}'이(가) ${percent(remainingRatio(overdueTask))}% 남은 채 마감일을 넘겼어요."
                    )
                )
                willMissTask != null -> alerts.add(
                    Alert(
                        Kind.SCHEDULE_DELAYED,
                        "📉 목표 일정에 차질이 예상돼요",
                        "'${willMissTask.first.name}'은 지금 페이스면 마감까지 ${round1(willMissTask.second)}만큼 모자랍니다."
                    )
                )
                snapshot.calendarOverdue > 0 -> alerts.add(
                    Alert(
                        Kind.SCHEDULE_DELAYED,
                        "📉 밀린 일정이 쌓이고 있어요",
                        "완료하지 못한 채 날짜가 지난 일정이 ${snapshot.calendarOverdue}건 있어요."
                    )
                )
            }
        }

        // 3) 계획한 양에 비해 진행이 뒤처진 업무(페이스 지연).
        if (settings.paceEnabled) {
            val behind = snapshot.tasks
                .filter { it.quantity > 0.0 && it.daysElapsed > 0 && it.daysLeft >= 0 }
                .map { it to (elapsedRatio(it) - progressRatio(it)) }
                .filter { it.second >= PACE_TOLERANCE }
                .maxByOrNull { it.second }
            if (behind != null) {
                val task = behind.first
                alerts.add(
                    Alert(
                        Kind.PACE_BEHIND,
                        "⏳ 학습 페이스가 계획보다 느려요",
                        "'${task.name}' 진행 ${percent(progressRatio(task))}% / 기간 ${percent(elapsedRatio(task))}% — " +
                            "남은 ${task.daysLeft}일 동안 하루 ${round1(dailyNeeded(task))}씩 해야 맞출 수 있어요."
                    )
                )
            }
        }

        return alerts
    }

    /** 알림 가능 시간대 안인지. start > end면 자정을 넘기는 구간(예: 22시~6시)으로 해석한다. */
    fun isWithinWindow(hourOfDay: Int, startHour: Int, endHour: Int): Boolean =
        if (startHour == endHour) true
        else if (startHour < endHour) hourOfDay in startHour until endHour
        else hourOfDay >= startHour || hourOfDay < endHour

    private fun progressRatio(task: TaskProgress): Double =
        if (task.quantity > 0.0) (task.progress / task.quantity).coerceIn(0.0, 1.0) else 1.0

    private fun remainingRatio(task: TaskProgress): Double = 1.0 - progressRatio(task)

    private fun elapsedRatio(task: TaskProgress): Double {
        val total = task.daysElapsed + task.daysLeft.coerceAtLeast(0)
        return if (total > 0) (task.daysElapsed.toDouble() / total).coerceIn(0.0, 1.0) else 1.0
    }

    /** 남은 양을 남은 일수로 나눈 "이제부터 하루에 해야 하는 양". */
    private fun dailyNeeded(task: TaskProgress): Double {
        val remaining = (task.quantity - task.progress).coerceAtLeast(0.0)
        val days = task.daysLeft.coerceAtLeast(1)
        return remaining / days
    }

    /** 지금까지의 평균 진행 속도가 그대로 이어진다고 볼 때 마감 시점에 모자랄 양(0 이하면 여유 있음). */
    private fun shortfallAtCurrentPace(task: TaskProgress): Double {
        if (task.daysElapsed <= 0) return 0.0
        val perDay = task.progress / task.daysElapsed
        val projected = task.progress + perDay * task.daysLeft.coerceAtLeast(0)
        return task.quantity - projected
    }

    private fun percent(ratio: Double): Int = Math.round(ratio * 100).toInt()

    private fun round1(value: Double): String {
        val rounded = Math.round(value * 10) / 10.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }
}
