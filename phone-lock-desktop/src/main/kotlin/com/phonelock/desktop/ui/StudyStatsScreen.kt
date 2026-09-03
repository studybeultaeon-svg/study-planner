package com.phonelock.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.CalendarTask
import com.phonelock.desktop.data.*
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private data class DayStat(val date: LocalDate, val cnt: Int, val done: Int)

/**
 * 네이티브 통계(5단계). 웹앱 index.html "통계" 탭(`renderStats()`)을 이식 — 별도 데이터 모델 없이
 * 캘린더 일정(`repository.getAllCalendarTasks()`)만 집계하는 읽기 전용 파생 뷰. 51차: "전체 일정/완료/
 * 완료율" 타일을 전체 누적이 아니라 오늘 하루 기준으로 바꾸고, 회독 단계별 완료 현황 카드는 제거(사용자
 * 요청). 계산/저장 UI는 없다 — DECISIONS.md "4단계(일정표) 네이티브 재구현"과 같은 파생 뷰 원칙을 그대로
 * 따랐다.
 */
@Composable
fun StudyStatsScreen(repository: Repository) {
    var allTasks by remember { mutableStateOf(emptyList<CalendarTask>()) }
    var allStudyLog by remember { mutableStateOf(emptyList<com.phonelock.desktop.data.StudyLogEntry>()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { repository.syncCalendarFromFirebase() }
        allTasks = repository.getAllCalendarTasks()
        allStudyLog = repository.getAllStudyLogOnce()
    }

    if (allTasks.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                "캘린더에 일정을 추가하고 완료 체크를 하면\n통계가 여기에 표시됩니다",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val today = LocalDate.now()
    val byDate = allTasks.groupBy { it.dateKey }

    // 51차: 전체 누적이 아니라 오늘 하루 일정 기준으로 바꿈(사용자 요청).
    val todayTasks = byDate[today.toString()] ?: emptyList()
    val totalCount = todayTasks.size
    val doneCount = todayTasks.count { it.status == "O" }
    val completionRate = if (totalCount > 0) Math.round(doneCount * 100.0 / totalCount).toInt() else 0

    // 연속 완료일: 오늘부터 과거로, 일정 있는 날 중 전부 완료면 계속, 일정 없는 날은 중립(건너뜀), 미완료 있으면 중단
    var streak = 0
    for (i in 0 until 3650) {
        val key = today.minusDays(i.toLong()).toString()
        val dayTasks = byDate[key] ?: emptyList()
        if (dayTasks.isEmpty()) continue
        val done = dayTasks.count { it.status == "O" }
        if (done == dayTasks.size) streak++ else break
    }

    // 최고 스트릭(51차, 루틴 통계와 같은 톤): 과거→현재로 훑으며 가장 길었던 연속 완료 구간을 찾는다.
    var bestStreak = 0
    var runningStreak = 0
    for (i in 3650 downTo 0) {
        val key = today.minusDays(i.toLong()).toString()
        val dayTasks = byDate[key] ?: emptyList()
        if (dayTasks.isEmpty()) continue
        val done = dayTasks.count { it.status == "O" }
        if (done == dayTasks.size) {
            runningStreak++
            if (runningStreak > bestStreak) bestStreak = runningStreak
        } else {
            runningStreak = 0
        }
    }

    val dayStats = (0 until 30).map { i ->
        val d = today.minusDays((29 - i).toLong())
        val dayTasks = byDate[d.toString()] ?: emptyList()
        DayStat(d, dayTasks.size, dayTasks.count { it.status == "O" })
    }
    val maxDayCnt = maxOf(1, dayStats.maxOf { it.cnt })
    val collapsedCalcNames = remember { mutableStateOf(setOf<String>()) }

    Column(Modifier.fillMaxSize().padding(Spacing.md).verticalScroll(rememberScrollState())) {
        Text("📈 통계", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text("캘린더 회독 진행 기준", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.md))

        // 현재 스트릭을 가장 위, 가장 크게(51차) — 최고 스트릭은 아래 타일 중 하나로.
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("현재 스트릭", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${streak}일" + if (streak > 0) " 🔥" else "",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            StatTile("오늘 완료", "$doneCount / $totalCount", Modifier.weight(1f), accentColor = Color(0xFF34D399))
            StatTile("오늘 완료율", "$completionRate%", Modifier.weight(1f), accentColor = Color(0xFFFBBF24))
            StatTile("최고 스트릭", "${bestStreak}일" + if (bestStreak > 0) "🔥" else "", Modifier.weight(1f), accentColor = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(Spacing.md))

        WeekOverWeekCard(allTasks = allTasks, today = today)
        Spacer(Modifier.height(Spacing.md))

        SectionCard("최근 30일 완료 추이 (막대 높이 = 일정 개수, 색상 = 완료율)") {
            Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                dayStats.forEach { ds ->
                    val pct = if (ds.cnt > 0) Math.round(ds.done * 100.0 / ds.cnt).toInt() else 0
                    val barColor = when {
                        ds.cnt == 0 -> MaterialTheme.colorScheme.outlineVariant
                        pct == 100 -> Color(0xFF34D399)
                        pct > 0 -> Color(0xFFFBBF24)
                        else -> Color(0xFFF87171)
                    }
                    val isToday = ds.date == today
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(
                            Modifier.fillMaxWidth().height(60.dp),
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            val heightPct = (ds.cnt.toFloat() / maxDayCnt).coerceIn(if (ds.cnt > 0) 0.08f else 0.03f, 1f)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height((60 * heightPct).dp)
                                    .background(barColor)
                            ) {}
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${ds.date.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 82차(§9 "포모도로 세션 태그"): 태그별 누적 공부시간.
        val taggedSeconds = allStudyLog.filter { it.tag.isNotBlank() }.groupBy { it.tag }.mapValues { (_, v) -> v.sumOf { it.seconds } }
        if (taggedSeconds.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            SectionCard("태그별 누적 공부시간") {
                val maxTagSeconds = maxOf(1, taggedSeconds.values.max())
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    taggedSeconds.entries.sortedByDescending { it.value }.forEach { (tag, seconds) ->
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(tag, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(formatHmsLog(seconds.toLong()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(2.dp))
                            val widthFraction = (seconds.toFloat() / maxTagSeconds).coerceIn(0.03f, 1f)
                            Row(
                                Modifier.fillMaxWidth(widthFraction).height(8.dp)
                                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                            ) {}
                        }
                    }
                }
            }
        }

        // 82차(§9 "일정표-계산기 진행량 그래프"): 계산기 연동 일정의 최근 30일 목표 대비 실제 완료량.
        // 85차: 과목(계산기 업무)별 상세 통계를 한 번에 접었다 펼 수 있게 요청(안드로이드판과 대칭) —
        // 계산기 입력 탭의 "모두 펴기/모두 접기"와 같은 패턴(collapsedKeys Set), calcName을 키로 잡는다.
        val linkedByTask = allTasks.filter { it.linkedCalc != null }.groupBy { it.linkedCalc!! }
        if (linkedByTask.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("계산기 연동 진행량 (최근 30일)", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    androidx.compose.material3.TextButton(onClick = { collapsedCalcNames.value = emptySet() }) { Text("모두 펴기") }
                    androidx.compose.material3.TextButton(onClick = { collapsedCalcNames.value = linkedByTask.keys.toSet() }) { Text("모두 접기") }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            linkedByTask.forEach { (calcName, tasks) ->
                val collapsed = calcName in collapsedCalcNames.value
                Row(
                    Modifier.fillMaxWidth().clickable {
                        collapsedCalcNames.value = if (collapsed) collapsedCalcNames.value - calcName else collapsedCalcNames.value + calcName
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.phonelock.desktop.ui.components.IconChip(
                        if (collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                        onClick = { collapsedCalcNames.value = if (collapsed) collapsedCalcNames.value - calcName else collapsedCalcNames.value + calcName }
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(calcName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                if (!collapsed) {
                    val byDateForTask = tasks.groupBy { it.dateKey }
                    val series = (0 until 30).map { i ->
                        val d = today.minusDays((29 - i).toLong())
                        val dayTasks = byDateForTask[d.toString()] ?: emptyList()
                        val target = dayTasks.sumOf { it.progressStep?.toDoubleOrNull() ?: 0.0 }
                        val done = dayTasks.filter { it.status == "O" }.sumOf { it.progressStep?.toDoubleOrNull() ?: 0.0 }
                        d to (target to done)
                    }
                    val maxAmount = maxOf(1.0, series.maxOf { it.second.first })
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        series.forEach { (d, pair) ->
                            val (target, done) = pair
                            val achieved = target > 0 && done >= target
                            val barColor = when {
                                target <= 0 -> MaterialTheme.colorScheme.outlineVariant
                                achieved -> Color(0xFF34D399)
                                done > 0 -> Color(0xFFFBBF24)
                                else -> Color(0xFFF87171)
                            }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Column(Modifier.fillMaxWidth().height(50.dp), verticalArrangement = Arrangement.Bottom) {
                                    val heightPct = (target / maxAmount).toFloat().coerceIn(if (target > 0) 0.08f else 0.03f, 1f)
                                    Row(Modifier.fillMaxWidth().height((50 * heightPct).dp).background(barColor)) {}
                                }
                                Spacer(Modifier.height(2.dp))
                                Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
            }
        }
    }
}

/** dateKey(yyyy-MM-dd)가 [fromInclusive, toInclusive] 범위(문자열 비교, ISO 형식이라 안전) 안의 일정만 골라 완료율을 계산. */
private fun completionRateInRange(tasks: List<CalendarTask>, fromInclusive: String, toInclusive: String): Pair<Int, Int> {
    val inRange = tasks.filter { it.dateKey in fromInclusive..toInclusive }
    val done = inRange.count { it.status == "O" }
    return done to inRange.size
}

/**
 * 전문가 종합분석 보고서 #31 — 새 조회 없이 이미 화면에 있는 allTasks를 두 구간(이번 7일/지난 7일)으로
 * 나눠 완료율을 비교만 한다(판정 로직과 무관, 순수 UI 집계).
 */
@Composable
private fun WeekOverWeekCard(allTasks: List<CalendarTask>, today: LocalDate) {
    val (thisDone, thisTotal) = completionRateInRange(allTasks, today.minusDays(6).toString(), today.toString())
    val (lastDone, lastTotal) = completionRateInRange(allTasks, today.minusDays(13).toString(), today.minusDays(7).toString())
    if (thisTotal == 0 && lastTotal == 0) return

    val thisRate = if (thisTotal > 0) Math.round(thisDone * 100.0 / thisTotal).toInt() else 0
    SectionCard("최근 7일 vs 지난 7일 완료율") {
        if (lastTotal == 0) {
            Text(
                "이번 주 완료율 $thisRate% (지난주 일정 없음, 비교 불가)",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            val lastRate = Math.round(lastDone * 100.0 / lastTotal).toInt()
            val diff = thisRate - lastRate
            val diffColor = when {
                diff > 0 -> Color(0xFF34D399)
                diff < 0 -> Color(0xFFF87171)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val diffLabel = if (diff > 0) "+$diff%p" else "$diff%p"
            Text(
                "이번 주 완료율 $thisRate% (지난주 대비 $diffLabel)",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                color = diffColor
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, accentColor: Color = Color.Unspecified) {
    val hasAccent = accentColor != Color.Unspecified
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (hasAccent) accentColor.copy(alpha = 0.07f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (hasAccent) accentColor.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(Spacing.sm), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(
                value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = if (accentColor != Color.Unspecified) accentColor else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
