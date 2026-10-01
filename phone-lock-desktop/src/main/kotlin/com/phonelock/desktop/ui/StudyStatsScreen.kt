package com.phonelock.desktop.ui

import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.phonelock.shared.study.StudyStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private data class DayStat(val date: LocalDate, val cnt: Int, val done: Int)

/**
 * 네이티브 통계(5단계). 웹앱 index.html "통계" 탭(`renderStats()`)을 이식 — 별도 데이터 모델 없이
 * 캘린더 일정(`repository.getAllCalendarTasks()`)만 집계하는 읽기 전용 파생 뷰. 51차: "전체 일정/완료/
 * 완료율" 타일을 전체 누적이 아니라 오늘 하루 기준으로 바꾸고, 회독 단계별 완료 현황 카드는 제거(사용자
 * 요청). 계산/저장 UI는 없다 — DECISIONS.md "4단계(일정표) 네이티브 재구현"과 같은 파생 뷰 원칙을 그대로
 * 따랐다. 142차: 연속 기록과 평균 공부 시간은 캘린더가 아니라 날짜별 공부 시간에서 계산한다([StudyStats]).
 */
@Composable
fun StudyStatsScreen(repository: Repository) {
    var allTasks by remember { mutableStateOf(emptyList<CalendarTask>()) }
    var allStudyLog by remember { mutableStateOf(emptyList<com.phonelock.desktop.data.StudyLogEntry>()) }
    // 142차: 연속 기록·평균은 캘린더 완료율이 아니라 날짜별 공부 시간(모든 기기 합산)으로 계산한다.
    var studyDays by remember { mutableStateOf(StudyStats.DayTotals.EMPTY) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        withContext(Dispatchers.IO) { repository.syncCalendarFromFirebase() }
        allTasks = repository.getAllCalendarTasks()
        allStudyLog = repository.getAllStudyLogOnce()
        studyDays = withContext(Dispatchers.IO) { repository.loadStudyDayTotals() }
    }

    LaunchedEffect(Unit) { refresh() }

    if (allTasks.isEmpty() && studyDays.studiedDates.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                "타이머로 집중 시간을 기록하거나 캘린더 일정을 완료하면\n통계가 여기에 표시됩니다",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(Spacing.sm))
            IconButton(onClick = { scope.launch { refresh() } }) { Text("🔄") }
        }
        return
    }

    // 141차: 캘린더와 같은 "하루 시작 기준"의 오늘(dailyResetHour).
    val today = LocalDate.parse(repository.todayCalendarDateKey())
    val byDate = allTasks.groupBy { it.dateKey }

    // 51차: 전체 누적이 아니라 오늘 하루 일정 기준으로 바꿈(사용자 요청).
    val todayTasks = byDate[today.toString()] ?: emptyList()
    val totalCount = todayTasks.size
    val doneCount = todayTasks.count { it.status == "O" }
    val completionRate = if (totalCount > 0) Math.round(doneCount * 100.0 / totalCount).toInt() else 0

    // 142차(사용자 요청): 연속 기록은 캘린더 일정을 전부 완료했는지가 아니라 "그날 공부 시간이 기록됐는지"로 센다.
    val study = remember(studyDays, today) { StudyStats.summarize(studyDays, today) }
    val streak = study.currentStreak
    val bestStreak = study.bestStreak

    val dayStats = (0 until 30).map { i ->
        val d = today.minusDays((29 - i).toLong())
        val dayTasks = byDate[d.toString()] ?: emptyList()
        DayStat(d, dayTasks.size, dayTasks.count { it.status == "O" })
    }
    val maxDayCnt = maxOf(1, dayStats.maxOf { it.cnt })
    val collapsedCalcNames = remember { mutableStateOf(setOf<String>()) }

    val palette = com.phonelock.desktop.ui.theme.LocalPhoneLockPalette.current
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {

        // 90차(사용자 요청): 넓은 데스크탑 창에서 세로 한 줄로만 쌓이던 걸 좌(요약 지표)/우(그래프·상세)
        // 로 나눴다 — 성격이 다른 두 종류라 타이머/캘린더 화면과 같은 ResponsiveSplit이 그대로 맞는다.
        // 창이 좁아지면 ResponsiveSplit이 알아서 위아래로 쌓는다(임계값 760dp).
        com.phonelock.desktop.ui.components.ResponsiveSplit(
            modifier = Modifier.weight(1f),
            leftWeight = 1f,
            rightWeight = 1.4f, // 막대 30개짜리 그래프가 있는 오른쪽에 폭을 조금 더 준다
            left = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 144차: 현재 연속 기록을 화면에서 가장 큰 숫자로, 새로고침은 오른쪽 위.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                com.phonelock.desktop.ui.components.Overline("현재 연속 기록")
                com.phonelock.desktop.ui.components.BigNumber(
                    "$streak",
                    unit = "일",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 96.sp, lineHeight = 98.sp, letterSpacing = (-3).sp),
                    unitStyle = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // 사용자 요청(안드로이드판은 당겨서 새로고침) — 데스크탑은 스와이프 제스처가 없어 버튼으로.
            IconButton(onClick = { scope.launch { refresh() } }) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Refresh, contentDescription = "새로고침", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("집중 시간이 조금이라도 기록된 날이 이어진 일수", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.lg))
        com.phonelock.desktop.ui.components.Hairline()
        Spacer(Modifier.height(Spacing.md))

        com.phonelock.desktop.ui.components.StatRow {
            com.phonelock.desktop.ui.components.StatBlock("오늘 일정", "$doneCount/$totalCount", Modifier.weight(1f))
            com.phonelock.desktop.ui.components.StatBlock("완료율", "$completionRate", Modifier.weight(1f), unit = "%")
            com.phonelock.desktop.ui.components.StatBlock("최고 기록", "$bestStreak", Modifier.weight(1f), unit = "일")
        }
        Spacer(Modifier.height(Spacing.md))

        StudyAverageCard(study)
        Spacer(Modifier.height(Spacing.md))

        WeekOverWeekCard(allTasks = allTasks, today = today)
        }
        }, right = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionCard("최근 30일 일정 완료 · 막대 높이 = 개수, 색 = 완료율") {
            Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                dayStats.forEach { ds ->
                    val pct = if (ds.cnt > 0) Math.round(ds.done * 100.0 / ds.cnt).toInt() else 0
                    val barColor = when {
                        ds.cnt == 0 -> MaterialTheme.colorScheme.outlineVariant
                        pct == 100 -> palette.fillGood
                        pct > 0 -> palette.fillPartial
                        else -> palette.fillBad
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
            SectionCard("태그별 누적 집중 시간") {
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
                                achieved -> palette.fillGood
                                done > 0 -> palette.fillPartial
                                else -> palette.fillBad
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
        })
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
    val palette = com.phonelock.desktop.ui.theme.LocalPhoneLockPalette.current
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
                diff > 0 -> palette.success
                diff < 0 -> palette.error
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

/**
 * 하루 평균 공부 시간(142차) — 기준은 [StudyStats] 주석 참고. 타일 대신 "이름 … 값" 줄로 쌓아서 폰 폭에서도
 * "1시간 23분" 같은 값이 잘리지 않는다.
 */
@Composable
private fun StudyAverageCard(summary: StudyStats.Summary) {
    SectionCard("하루 평균 집중 시간") {
        StudyAverageRow("오늘", StudyStats.durationLabel(summary.todaySeconds))
        StudyAverageRow("최근 ${StudyStats.SHORT_WINDOW_DAYS}일 평균", summary.shortAverageSeconds?.let { StudyStats.durationLabel(it) } ?: "—")
        StudyAverageRow("최근 ${StudyStats.LONG_WINDOW_DAYS}일 평균", summary.longAverageSeconds?.let { StudyStats.durationLabel(it) } ?: "—")
        StudyAverageRow("집중한 날 평균", summary.activeDayAverageSeconds?.let { StudyStats.durationLabel(it) } ?: "—")
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "평균은 오늘을 포함한 기간의 합계를 날짜 수로 나눈 값입니다(쉰 날도 0으로 포함, 쓰기 시작한 지 얼마 안 됐으면 첫 기록일부터). " +
                "\"집중한 날 평균\"은 최근 ${StudyStats.LONG_WINDOW_DAYS}일 중 기록이 있는 날만으로 나눕니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StudyAverageRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}
