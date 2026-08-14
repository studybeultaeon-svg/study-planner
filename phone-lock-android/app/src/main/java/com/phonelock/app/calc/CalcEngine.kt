package com.phonelock.app.calc

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 네이티브 계산기(3단계) 계산 로직 — 웹앱 index.html의 calculate()/simulateFinish()/
 * calcRequiredPace()/countDaysInRange()를 그대로 이식했다(데스크탑 CalcEngine.kt와 동일 로직,
 * 플랫폼 간 공유 모듈이 없어 그대로 대칭 복제). 요일 인덱스는 JS Date.getDay()와 동일하게
 * 0=일 ~ 6=토를 쓴다(LocalDate.dayOfWeek는 1=월~7=일이라 변환 필요).
 */
object CalcEngine {

    fun jsDow(date: LocalDate): Int = date.dayOfWeek.value % 7

    data class CalcInput(
        val name: String,
        val qty: String,
        val unit: String,
        val progress: String,
        val start: String,
        val dday: String,
        val mon: String, val tue: String, val wed: String, val thu: String,
        val fri: String, val sat: String, val sun: String,
        val holidays: List<String>
    )

    data class CalcResult(
        val name: String,
        val unit: String,
        val qty: Double,
        val progress: Double,
        val remaining: Double,
        val progressPct: Int,
        val startDate: LocalDate,
        val ddayDate: LocalDate,
        val totalDays: Int,
        val holidayCount: Int,
        val weekdays: Int,
        val weekends: Int,
        val ddayNum: Int,
        val ddayLabel: String,
        val dayGoals: Map<Int, Double>,
        val reqGoals: Map<Int, Double>,
        val enough: Boolean,
        val multiplier: Double,
        val totalCapacity: Double,
        val finishDate: LocalDate?,
        val finishDiffDays: Int?
    )

    sealed class CalcOutcome {
        data class Success(val result: CalcResult) : CalcOutcome()
        data class Error(val message: String) : CalcOutcome()
    }

    fun parseHolidaysInput(str: String): List<String> =
        str.split(",").map { it.trim() }.filter { Regex("""^\d{4}-\d{2}-\d{2}$""").matches(it) }

    private fun countDaysInRange(startDate: LocalDate, endDate: LocalDate, holidays: Set<String>): Triple<Int, Int, Int> {
        var weekdays = 0; var weekends = 0; var holidayCount = 0
        var cur = startDate
        while (!cur.isAfter(endDate)) {
            if (holidays.contains(cur.toString())) {
                holidayCount++
            } else {
                val dow = jsDow(cur)
                if (dow == 0 || dow == 6) weekends++ else weekdays++
            }
            cur = cur.plusDays(1)
        }
        return Triple(weekdays, weekends, holidayCount)
    }

    private fun simulateFinish(remaining: Double, startDate: LocalDate, dayGoals: Map<Int, Double>, holidays: Set<String>): LocalDate? {
        var acc = 0.0
        var sim = startDate
        val limit = startDate.plusYears(10)
        while (!sim.isAfter(limit)) {
            if (!holidays.contains(sim.toString())) acc += dayGoals[jsDow(sim)] ?: 0.0
            if (acc >= remaining) return sim
            sim = sim.plusDays(1)
        }
        return null
    }

    data class RequiredPace(val multiplier: Double, val totalCapacity: Double, val enough: Boolean)

    private fun calcRequiredPace(remaining: Double, startDate: LocalDate, endDate: LocalDate, dayGoals: Map<Int, Double>, holidays: Set<String>): RequiredPace {
        val dayCounts = IntArray(7)
        var cur = startDate
        while (!cur.isAfter(endDate)) {
            if (!holidays.contains(cur.toString())) dayCounts[jsDow(cur)]++
            cur = cur.plusDays(1)
        }
        var totalCapacity = 0.0
        for (d in 0..6) totalCapacity += dayCounts[d] * (dayGoals[d] ?: 0.0)
        val multiplier = if (totalCapacity > 0) remaining / totalCapacity else Double.POSITIVE_INFINITY
        return RequiredPace(multiplier, totalCapacity, multiplier <= 1.0)
    }

    fun calculate(input: CalcInput, today: LocalDate = LocalDate.now()): CalcOutcome {
        val name = input.name.trim()
        val qty = input.qty.toDoubleOrNull() ?: 0.0
        val unit = input.unit.trim()
        val progress = input.progress.toDoubleOrNull() ?: 0.0
        val dayGoals = mapOf(
            1 to (input.mon.toDoubleOrNull() ?: 0.0), 2 to (input.tue.toDoubleOrNull() ?: 0.0),
            3 to (input.wed.toDoubleOrNull() ?: 0.0), 4 to (input.thu.toDoubleOrNull() ?: 0.0),
            5 to (input.fri.toDoubleOrNull() ?: 0.0), 6 to (input.sat.toDoubleOrNull() ?: 0.0),
            0 to (input.sun.toDoubleOrNull() ?: 0.0)
        )
        val holidays = input.holidays.toSet()

        if (name.isEmpty()) return CalcOutcome.Error("업무 이름을 입력해주세요")
        if (qty <= 0) return CalcOutcome.Error("\"$name\": 총 할당량을 입력해주세요")
        if (input.dday.isBlank()) return CalcOutcome.Error("\"$name\": 마감 날짜를 입력해주세요")
        if (dayGoals.values.all { it <= 0 }) return CalcOutcome.Error("\"$name\": 최소 하나의 요일 목표를 입력해주세요")

        val dday = runCatching { LocalDate.parse(input.dday) }.getOrNull()
            ?: return CalcOutcome.Error("\"$name\": 마감 날짜 형식이 올바르지 않습니다")
        var startDate = if (input.start.isNotBlank()) {
            runCatching { LocalDate.parse(input.start) }.getOrNull() ?: today
        } else today
        if (startDate.isBefore(today)) startDate = today

        val remaining = (qty - progress).coerceAtLeast(0.0)
        val progressPct = if (qty > 0) (progress / qty * 100).roundToInt() else 0

        val finishDate = simulateFinish(remaining, startDate, dayGoals, holidays)
        val finishDiffDays = finishDate?.let { ChronoUnit.DAYS.between(dday, it).toInt() }
        val req = calcRequiredPace(remaining, startDate, dday, dayGoals, holidays)
        val (weekdays, weekends, holidayCount) = countDaysInRange(startDate, dday, holidays)
        val totalDays = ChronoUnit.DAYS.between(startDate, dday).toInt() + 1
        val ddayNum = ChronoUnit.DAYS.between(today, dday).toInt()
        val ddayLabel = if (ddayNum == 0) "D-Day" else "D-$ddayNum"
        val reqGoals = dayGoals.mapValues { (_, v) -> ceil(v * req.multiplier) }

        return CalcOutcome.Success(
            CalcResult(
                name = name, unit = unit, qty = qty, progress = progress, remaining = remaining, progressPct = progressPct,
                startDate = startDate, ddayDate = dday, totalDays = totalDays, holidayCount = holidayCount,
                weekdays = weekdays, weekends = weekends, ddayNum = ddayNum, ddayLabel = ddayLabel,
                dayGoals = dayGoals, reqGoals = reqGoals, enough = req.enough, multiplier = req.multiplier,
                totalCapacity = req.totalCapacity, finishDate = finishDate, finishDiffDays = finishDiffDays
            )
        )
    }
}
