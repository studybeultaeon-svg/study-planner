package com.phonelock.shared.study

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 공부 통계 탭의 "연속 기록"과 "평균 공부 시간" 계산(142차) — 양 플랫폼 StudyStatsScreen이 함께 쓴다.
 *
 * **연속 기록은 공부 시간 기준**(142차, 사용자 요청): 그전엔 캘린더 일정을 전부 완료한 날만 이어졌는데,
 * 이제 그날 공부 시간이 조금이라도 기록되면 이어진다. 공부하지 않은 날이 하루라도 끼면 끊긴다. 오늘은 아직
 * 끝나지 않았으므로, 오늘 기록이 없으면 어제까지의 연속 일수를 그대로 보여준다(오늘 공부하면 +1).
 *
 * **평균의 기준**: 최근 N일(오늘 포함)의 합계를 N일로 나눈다 — 공부하지 않은 날도 0으로 센 "하루 평균"이다.
 * 다만 첫 기록이 그 기간 안에 있으면 첫 기록일부터 오늘까지의 일수로만 나눈다(쓰기 시작한 지 사흘인데 7로
 * 나누면 실제보다 훨씬 낮게 나온다). 쉬는 날에 끌려 내려가지 않는 값도 함께 보도록 "공부한 날 평균"(기록이
 * 있는 날만으로 나눔)을 따로 낸다.
 */
object StudyStats {

    const val SHORT_WINDOW_DAYS = 7
    const val LONG_WINDOW_DAYS = 30

    /**
     * @param secondsByDate 날짜(yyyy-MM-dd) -> 그날 공부한 초(모든 기기 합산). 최근 [LONG_WINDOW_DAYS]일만 있어도 된다.
     * @param studiedDates 공부 기록이 하나라도 있는 날짜 전체 — 연속 기록은 이 집합만 본다.
     */
    data class DayTotals(val secondsByDate: Map<String, Long>, val studiedDates: Set<String>) {
        companion object {
            val EMPTY = DayTotals(emptyMap(), emptySet())
        }
    }

    data class Summary(
        val currentStreak: Int,
        val bestStreak: Int,
        val todaySeconds: Long,
        /** 최근 7일 하루 평균(초). 기록이 전혀 없으면 null. */
        val shortAverageSeconds: Long?,
        /** 최근 30일 하루 평균(초). */
        val longAverageSeconds: Long?,
        /** 최근 30일 중 공부한 날만의 평균(초). */
        val activeDayAverageSeconds: Long?
    )

    fun summarize(totals: DayTotals, today: LocalDate): Summary {
        val studied = (totals.studiedDates + totals.secondsByDate.filterValues { it > 0 }.keys)
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .filter { !it.isAfter(today) }
            .toSet()
        val seconds = totals.secondsByDate.mapNotNull { (key, value) ->
            runCatching { LocalDate.parse(key) }.getOrNull()?.let { it to value }
        }.toMap()
        val first = studied.minOrNull()
        return Summary(
            currentStreak = currentStreak(studied, today),
            bestStreak = bestStreak(studied),
            todaySeconds = seconds[today] ?: 0L,
            shortAverageSeconds = dailyAverageSeconds(seconds, today, SHORT_WINDOW_DAYS, first),
            longAverageSeconds = dailyAverageSeconds(seconds, today, LONG_WINDOW_DAYS, first),
            activeDayAverageSeconds = activeDayAverageSeconds(seconds, today, LONG_WINDOW_DAYS)
        )
    }

    /** 오늘 기록이 있으면 오늘부터, 없으면 어제부터 거슬러 올라가며 끊기는 날 전까지 센다. */
    fun currentStreak(studiedDates: Set<LocalDate>, today: LocalDate): Int {
        var date = if (today in studiedDates) today else today.minusDays(1)
        var streak = 0
        while (date in studiedDates) {
            streak++
            date = date.minusDays(1)
        }
        return streak
    }

    fun bestStreak(studiedDates: Set<LocalDate>): Int {
        var best = 0
        for (start in studiedDates) {
            // 연속 구간의 첫날에서만 세기 시작한다 — 날짜 수에 비례하는 시간에 끝난다.
            if (start.minusDays(1) in studiedDates) continue
            var length = 1
            var next = start.plusDays(1)
            while (next in studiedDates) {
                length++
                next = next.plusDays(1)
            }
            if (length > best) best = length
        }
        return best
    }

    fun dailyAverageSeconds(
        secondsByDate: Map<LocalDate, Long>,
        today: LocalDate,
        windowDays: Int,
        firstStudyDate: LocalDate?
    ): Long? {
        if (firstStudyDate == null || firstStudyDate.isAfter(today)) return null
        val windowStart = today.minusDays((windowDays - 1).toLong())
        val from = if (firstStudyDate.isAfter(windowStart)) firstStudyDate else windowStart
        val days = ChronoUnit.DAYS.between(from, today) + 1
        val total = secondsByDate.filterKeys { !it.isBefore(from) && !it.isAfter(today) }.values.sum()
        return total / days
    }

    fun activeDayAverageSeconds(secondsByDate: Map<LocalDate, Long>, today: LocalDate, windowDays: Int): Long? {
        val windowStart = today.minusDays((windowDays - 1).toLong())
        val active = secondsByDate.filter { (date, seconds) -> seconds > 0 && !date.isBefore(windowStart) && !date.isAfter(today) }
        if (active.isEmpty()) return null
        return active.values.sum() / active.size
    }

    /** "2시간 5분" / "45분" / "1분 미만" — 통계 카드에 쓰는 짧은 표기. */
    fun durationLabel(seconds: Long): String {
        if (seconds <= 0L) return "0분"
        if (seconds < 60L) return "1분 미만"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours == 0L -> "${minutes}분"
            minutes == 0L -> "${hours}시간"
            else -> "${hours}시간 ${minutes}분"
        }
    }
}
