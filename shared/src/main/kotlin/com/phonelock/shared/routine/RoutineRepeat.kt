package com.phonelock.shared.routine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 루틴 반복 규칙(134차, 사용자 요청 "루틴앱을 리마인더로 활용 가능하게 — 며칠마다 반복, 월초/월말마다 반복,
 * 특정 일에만 반복") — 그전까지 루틴은 요일 마스크(월~일) 하나로만 반복할 수 있어서 "3일마다", "매월 25일",
 * "월말"처럼 요일과 무관한 리마인더를 만들 수 없었다.
 *
 * 세 가지 모드로 넓혔고, 판정은 전부 이 순수 함수가 한다 — 안드로이드/데스크탑의 RoutineEngine, 알림 예약,
 * 위젯, 스트릭 계산이 모두 같은 규칙을 쓰도록(플랫폼별로 복제하면 언젠가 어긋난다).
 *
 * - [MODE_WEEKLY]   기존 방식. 요일 마스크에 든 요일마다.
 * - [MODE_INTERVAL] 기준일부터 N일마다(2~365).
 * - [MODE_MONTHLY]  매월 정해진 날짜마다. 숫자(1~31)와 "그 달의 마지막 날"([LAST_DAY])을 섞어 고를 수 있다
 *                   — 월초는 1, 월말은 [LAST_DAY].
 *
 * 기간 설정(startDate/endDate)은 세 모드 모두에 그대로 얹힌다(범위 밖이면 예정 없음).
 */
object RoutineRepeat {

    const val MODE_WEEKLY = "WEEKLY"
    const val MODE_INTERVAL = "INTERVAL"
    const val MODE_MONTHLY = "MONTHLY"

    /** 매월 반복에서 "그 달의 마지막 날"(월말)을 뜻하는 표시. */
    const val LAST_DAY = "L"

    const val MIN_INTERVAL_DAYS = 2
    const val MAX_INTERVAL_DAYS = 365

    /** 알림 예약처럼 "다음 실행일"을 찾을 때 훑어야 하는 최대 일수 — 가장 긴 주기(365일)를 한 번은 만나도록. */
    const val MAX_LOOKAHEAD_DAYS = 366

    /** 기준일(시작일)을 안 정한 N일마다 루틴이 쓰는 고정 기준일 — 기기가 달라도 같은 날 돌아오게 하기 위함. */
    private val FALLBACK_ANCHOR: LocalDate = LocalDate.of(2000, 1, 1)

    fun isScheduled(
        mode: String,
        daysMask: Int,
        intervalDays: Int,
        monthDaysCsv: String,
        anchorDate: LocalDate?,
        date: LocalDate
    ): Boolean = when (mode) {
        MODE_INTERVAL -> isIntervalDay(intervalDays, anchorDate, date)
        MODE_MONTHLY -> isMonthlyDay(monthDaysCsv, date)
        else -> isWeekdayInMask(daysMask, date)
    }

    fun isWeekdayInMask(daysMask: Int, date: LocalDate): Boolean =
        (daysMask shr (date.dayOfWeek.value - 1)) and 1 == 1

    /** 기준일 당일도 실행일이고, 그 뒤로 N일 간격. 기준일 이전 날짜는 예정 없음. */
    fun isIntervalDay(intervalDays: Int, anchorDate: LocalDate?, date: LocalDate): Boolean {
        val n = intervalDays.coerceIn(1, MAX_INTERVAL_DAYS)
        if (n == 1) return true
        val anchor = anchorDate ?: FALLBACK_ANCHOR
        val diff = ChronoUnit.DAYS.between(anchor, date)
        if (anchorDate != null && diff < 0) return false
        return Math.floorMod(diff, n.toLong()) == 0L
    }

    /** 고른 날짜(와 월말)에 해당하면 실행일. 31일처럼 그 달에 없는 날짜는 그 달 마지막 날로 당겨서 실행한다
     *  — 리마인더가 2월에만 통째로 사라지지 않게 하기 위함. */
    fun isMonthlyDay(monthDaysCsv: String, date: LocalDate): Boolean {
        val tokens = parseMonthDays(monthDaysCsv)
        if (tokens.isEmpty()) return false
        val lastDay = date.lengthOfMonth()
        return tokens.any { token ->
            if (token == LAST_DAY) date.dayOfMonth == lastDay
            else token.toIntOrNull()?.let { day -> date.dayOfMonth == day.coerceAtMost(lastDay) } ?: false
        }
    }

    /** "15, 1, L, 99" → ["1", "15", "L"] — 숫자는 1~31만 남기고 정렬, 월말은 맨 뒤, 중복 제거. */
    fun parseMonthDays(csv: String): List<String> {
        val raw = csv.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val days = raw.mapNotNull { it.toIntOrNull() }.filter { it in 1..31 }.distinct().sorted()
        val hasLast = raw.any { it == LAST_DAY }
        return days.map { it.toString() } + if (hasLast) listOf(LAST_DAY) else emptyList()
    }

    fun toMonthDaysCsv(tokens: Collection<String>): String =
        parseMonthDays(tokens.joinToString(",")).joinToString(",")

    /** 반복 규칙 한 줄 요약 — 목록과 편집 화면에 그대로 쓴다. */
    fun describe(mode: String, daysMask: Int, intervalDays: Int, monthDaysCsv: String): String = when (mode) {
        MODE_INTERVAL -> "${intervalDays.coerceIn(1, MAX_INTERVAL_DAYS)}일마다"
        MODE_MONTHLY -> {
            val tokens = parseMonthDays(monthDaysCsv)
            if (tokens.isEmpty()) "매월 (날짜 미지정)"
            else "매월 " + tokens.joinToString(" · ") { if (it == LAST_DAY) "말일" else "${it}일" }
        }
        else -> describeWeekdays(daysMask)
    }

    private val WEEKDAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

    fun describeWeekdays(daysMask: Int): String {
        val picked = (0..6).filter { (daysMask shr it) and 1 == 1 }
        return when {
            picked.isEmpty() -> "요일 미지정"
            picked.size == 7 -> "매일"
            picked == listOf(0, 1, 2, 3, 4) -> "주중(월~금)"
            picked == listOf(5, 6) -> "주말(토·일)"
            else -> picked.joinToString(" · ") { WEEKDAY_NAMES[it] }
        }
    }
}
