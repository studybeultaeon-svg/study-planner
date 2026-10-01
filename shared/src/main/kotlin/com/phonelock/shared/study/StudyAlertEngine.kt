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

        /** 마감이 지났거나, 요일별 목표대로 해도 마감까지 다 못 끝낸다. */
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
        /** 시작일부터 오늘까지 흐른 일수(오늘 포함). 아직 시작 전이면 0. */
        val daysElapsed: Int,
        /** 오늘 이후 마감까지 남은 일수(오늘 제외). 음수면 마감이 지난 것. */
        val daysLeft: Int,
        /** 오늘 해야 할 양(요일별 할당량). 0이면 오늘은 예정 없음. */
        val todayQuota: Double,
        /** 오늘치 목표를 채웠는지(연동된 캘린더 일정 완료분 합 ≥ 오늘 할당량). */
        val todayAchieved: Boolean,
        /** 분량 단위(쪽/강/문제 …). 알림 문구의 숫자 뒤에 그대로 붙인다 — 비어 있으면 숫자만 쓴다. */
        val unit: String,
        /** 오늘(시작 전이면 시작일)부터 마감일까지 요일별 목표를 그대로 지켰을 때 해낼 수 있는 양(휴일 제외,
         *  `CalcEngine.planWindow`). 계산기 화면의 "필요 페이스"와 같은 기준이다. */
        val planCapacity: Double,
        /** 위 기간 중 목표가 잡힌(0보다 큰) 날 수. */
        val planDays: Int
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
                    "🎯 오늘 집중을 아직 시작하지 않았어요",
                    "${parts.joinToString(" · ")}이 오늘 예정되어 있어요."
                )
            )
        }

        // 2) 마감이 지났거나, 요일별 목표대로 해도 마감까지 다 못 끝내는 업무.
        if (settings.scheduleEnabled) {
            val overdueTask = snapshot.tasks.firstOrNull { it.quantity > 0.0 && it.daysLeft < 0 && it.progress < it.quantity }
            // 134차: 예전엔 "지금까지 평균 속도(진행량 ÷ 시작 후 지난 날짜)"를 마감까지 늘려 모자랄 양을 냈는데,
            // 시작 직후엔 며칠치 기록만으로 몇 달을 내다봐 숫자가 터무니없이 커지고(실사용 "223.6만큼 모자랍니다"),
            // 목표가 없는 요일·아직 시작 안 한 업무까지 "느리다"고 셌다. 이제는 사용자가 직접 정한 요일별 목표를
            // 기준으로 "그대로 해도 마감 때 얼마가 남는가"를 본다 — 계산기 화면의 필요 페이스와 같은 숫자다.
            val willMissTask = snapshot.tasks
                .filter { it.quantity > 0.0 && it.daysLeft >= 0 }
                .map { it to planShortfall(it) }
                .filter { (task, shortfall) -> shortfall > 0.0 && shortfall >= averagePlanPerDay(task) }
                .maxByOrNull { (task, shortfall) -> shortfallInDays(task, shortfall) }
            when {
                overdueTask != null -> alerts.add(
                    Alert(
                        Kind.SCHEDULE_DELAYED,
                        "📉 마감이 지난 업무가 있어요",
                        "'${overdueTask.name}': 마감일이 지났는데 아직 ${subject(amount(remaining(overdueTask), overdueTask.unit))} " +
                            "남아 있어요(전체의 ${percent(remainingRatio(overdueTask))}%)."
                    )
                )
                willMissTask != null -> {
                    val (task, shortfall) = willMissTask
                    alerts.add(
                        Alert(
                            Kind.SCHEDULE_DELAYED,
                            "📉 이대로면 마감을 못 맞춰요",
                            if (task.planDays <= 0) {
                                "'${task.name}': 마감(${ddayLabel(task.daysLeft)})까지 목표가 잡힌 날이 없어 " +
                                    "남은 ${objectOf(amount(remaining(task), task.unit))} 끝낼 수 없어요. 계산기에서 요일별 목표를 다시 잡아 주세요."
                            } else {
                                "'${task.name}': 요일별 목표대로 해도 마감(${ddayLabel(task.daysLeft)}) 때 " +
                                    "${subject(amount(shortfall, task.unit))} 남아요. 제때 끝내려면 하루 평균 " +
                                    "${amount(averagePlanPerDay(task), task.unit)} → ${towards(amount(ceil1(neededPerPlanDay(task)), task.unit))} 늘려야 해요."
                            }
                        )
                    )
                }
                snapshot.calendarOverdue > 0 -> alerts.add(
                    Alert(
                        Kind.SCHEDULE_DELAYED,
                        "📉 밀린 일정이 쌓이고 있어요",
                        "완료하지 못한 채 날짜가 지난 일정이 ${snapshot.calendarOverdue}건 있어요."
                    )
                )
            }
        }

        // 3) 계획한 양에 비해 진행이 뒤처진 업무(페이스 지연). 아직 시작 전(daysElapsed == 0)인 업무는 제외.
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
                        "⏳ 진행 페이스가 계획보다 느려요",
                        "'${task.name}': 기간은 ${percent(elapsedRatio(task))}% 지났는데 진행은 ${percent(progressRatio(task))}%예요. " +
                            "마감(${ddayLabel(task.daysLeft)})까지 계획한 날마다 평균 " +
                            "${amount(ceil1(neededPerPlanDay(task)), task.unit)}씩 해야 따라잡아요."
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

    private fun remaining(task: TaskProgress): Double = (task.quantity - task.progress).coerceAtLeast(0.0)

    private fun elapsedRatio(task: TaskProgress): Double {
        val total = task.daysElapsed + task.daysLeft.coerceAtLeast(0)
        return if (total > 0) (task.daysElapsed.toDouble() / total).coerceIn(0.0, 1.0) else 1.0
    }

    /** 요일별 목표를 그대로 지켰을 때 마감 때 남는 양(0 이하면 제때 끝남). */
    private fun planShortfall(task: TaskProgress): Double = remaining(task) - task.planCapacity

    /** 지금 계획의 "공부하는 날" 하루 평균 목표. 목표가 잡힌 날이 없으면 0. */
    private fun averagePlanPerDay(task: TaskProgress): Double =
        if (task.planDays > 0) task.planCapacity / task.planDays else 0.0

    /** 남은 양을 목표가 잡힌 날에 고르게 나눴을 때 하루에 해야 하는 양. 그런 날이 없으면 남은 날(최소 1일)로 나눈다. */
    private fun neededPerPlanDay(task: TaskProgress): Double {
        val days = if (task.planDays > 0) task.planDays else (task.daysLeft + 1).coerceAtLeast(1)
        return remaining(task) / days
    }

    /** 여러 업무 중 가장 급한 것을 고르는 기준 — 단위가 서로 달라(쪽/강) 양 대신 "지금 계획 기준 며칠치가
     *  모자란가"로 비교한다. 목표가 잡힌 날이 아예 없으면 가장 급한 것으로 친다. */
    private fun shortfallInDays(task: TaskProgress, shortfall: Double): Double {
        val perDay = averagePlanPerDay(task)
        return if (perDay > 0.0) shortfall / perDay else Double.MAX_VALUE
    }

    private fun ddayLabel(daysLeft: Int): String = if (daysLeft <= 0) "D-Day" else "D-$daysLeft"

    /** 숫자 + 단위(예: "22쪽"). 단위가 비어 있으면 숫자만. */
    private fun amount(value: Double, unit: String): String = round1(value) + unit.trim()

    /** 소수 첫째 자리에서 올림 — "하루 이만큼은 해야 한다"는 값이라 반올림으로 깎이면 안 된다. */
    private fun ceil1(value: Double): Double = Math.ceil(value * 10 - 1e-9) / 10.0

    /** 마지막 글자의 받침 번호(0 = 받침 없음, 8 = ㄹ). 숫자는 읽는 소리 기준(0은 십·백·천/영 모두 받침 있음). */
    private fun finalConsonant(word: String): Int {
        val c = word.trimEnd().lastOrNull() ?: return 0
        return when {
            c in '가'..'힣' -> (c - '가') % 28
            c == '0' -> 17
            c == '1' || c == '7' || c == '8' -> 8
            c == '3' -> 16
            c == '6' -> 1
            else -> 0
        }
    }

    /** 주격 조사(이/가)를 붙인다 — 단위가 "쪽"이면 "22쪽이", "문제"면 "3문제가". */
    private fun subject(word: String): String = word + if (finalConsonant(word) != 0) "이" else "가"

    /** 목적격 조사(을/를)를 붙인다. */
    private fun objectOf(word: String): String = word + if (finalConsonant(word) != 0) "을" else "를"

    /** 방향 조사(으로/로)를 붙인다 — ㄹ 받침 뒤는 "로". */
    private fun towards(word: String): String =
        word + finalConsonant(word).let { if (it != 0 && it != 8) "으로" else "로" }

    private fun percent(ratio: Double): Int = Math.round(ratio * 100).toInt()

    private fun round1(value: Double): String {
        val rounded = Math.round(value * 10) / 10.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }
}
