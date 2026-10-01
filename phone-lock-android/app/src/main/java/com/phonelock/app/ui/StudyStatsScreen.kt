package com.phonelock.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.phonelock.app.data.CalendarTask
import com.phonelock.app.data.*
import com.phonelock.app.data.PhoneLockRepository
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import com.phonelock.app.ui.components.BigNumber
import com.phonelock.app.ui.components.Hairline
import com.phonelock.app.ui.components.Overline
import com.phonelock.app.ui.components.StatBlock
import com.phonelock.app.ui.components.StatRow
import com.phonelock.app.ui.theme.LocalPhoneLockPalette
import com.phonelock.app.ui.theme.Spacing
import com.phonelock.shared.study.StudyStats
import kotlinx.coroutines.launch
import java.time.LocalDate

private data class DayStat(val date: LocalDate, val cnt: Int, val done: Int)

/**
 * 네이티브 통계(5단계). 웹앱 index.html "통계" 탭(`renderStats()`)을 이식 — 별도 데이터 모델 없이
 * 캘린더 일정(`repository.getAllCalendarTasksOnce()`)만 집계하는 읽기 전용 파생 뷰. 51차: "전체 일정/완료/
 * 완료율" 타일을 전체 누적이 아니라 오늘 하루 기준으로 바꾸고, 회독 단계별 완료 현황 카드는 제거(사용자
 * 요청, 데스크탑판과 대칭). 플랫폼 공유 모듈 없어 대칭 복제(계산기/일정표와 같은 패턴) — DECISIONS.md 참고.
 * 142차: 연속 기록과 평균 공부 시간은 캘린더가 아니라 날짜별 공부 시간에서 계산한다([StudyStats]).
 */
@Composable
fun StudyStatsScreen(repository: PhoneLockRepository) {
    var allTasks by remember { mutableStateOf<List<CalendarTask>>(emptyList()) }
    var allStudyLog by remember { mutableStateOf<List<com.phonelock.app.data.StudyLogEntry>>(emptyList()) }
    // 142차: 연속 기록·평균은 캘린더 완료율이 아니라 날짜별 공부 시간(모든 기기 합산)으로 계산한다.
    var studyDays by remember { mutableStateOf(StudyStats.DayTotals.EMPTY) }

    suspend fun load() {
        repository.syncCalendarFromFirebase()
        allTasks = repository.getAllCalendarTasksOnce()
        allStudyLog = repository.getAllStudyLogOnce()
        studyDays = repository.loadStudyDayTotals()
    }

    LaunchedEffect(Unit) { load() }

    // 당겨서 새로고침 추가(사용자 요청, 98차 6개 화면과 같은 패턴).
    com.phonelock.app.ui.components.PullToRefreshBox(onRefresh = { load() }) {
        StudyStatsContent(allTasks, allStudyLog, studyDays, LocalDate.parse(repository.todayCalendarDateKey()))
    }
}

@Composable
private fun StudyStatsContent(
    allTasks: List<CalendarTask>,
    allStudyLog: List<com.phonelock.app.data.StudyLogEntry>,
    studyDays: StudyStats.DayTotals,
    today: LocalDate
) {
    if (allTasks.isEmpty() && studyDays.studiedDates.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                "타이머로 집중 시간을 기록하거나 캘린더 일정을 완료하면\n통계가 여기에 표시됩니다",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val byDate = allTasks.groupBy { it.dateKey }

    // 51차: 전체 누적이 아니라 오늘 하루 일정 기준으로 바꿈(사용자 요청, 데스크탑판과 대칭).
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

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val collapsedCalcNames = remember { mutableStateOf(setOf<String>()) }

    val palette = LocalPhoneLockPalette.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter).padding(top = Spacing.md, bottom = Spacing.xl)
    ) {
        // 144차: 현재 연속 기록을 화면에서 가장 큰 숫자로(51차 "가장 위, 가장 크게"를 편집형으로), 리포트 저장은 오른쪽 위 글자 버튼.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Overline("현재 연속 기록")
                BigNumber(
                    "$streak",
                    unit = "일",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp, lineHeight = 90.sp, letterSpacing = (-3).sp),
                    unitStyle = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // 82차(§9 "월간 통계 리포트(이미지)"): 지금 보이는 통계 화면 그대로를 PNG로 저장한다.
            androidx.compose.material3.TextButton(onClick = {
                val activity = context as? android.app.Activity ?: return@TextButton
                scope.launch {
                    val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                    val file = java.io.File(dir, "study_report_${System.currentTimeMillis()}.png")
                    val saved = com.phonelock.app.util.ScreenCapture.captureWindowToFile(activity, file)
                    Toast.makeText(
                        context,
                        if (saved != null) "저장됨: ${saved.name}" else "저장 실패",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }) { Text("리포트 저장", maxLines = 1, softWrap = false) }
        }
        Text(
            "집중 시간이 조금이라도 기록된 날이 이어진 일수",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.lg))
        Hairline()
        Spacer(Modifier.height(Spacing.md))
        StatRow {
            StatBlock("오늘 일정", "$doneCount/$totalCount", Modifier.weight(1f))
            StatBlock("완료율", "$completionRate", Modifier.weight(1f), unit = "%")
            StatBlock("최고 기록", "$bestStreak", Modifier.weight(1f), unit = "일")
        }
        Spacer(Modifier.height(Spacing.lg))

        StudyAverageCard(study)

        WeekOverWeekCard(allTasks = allTasks, today = today)

        // 태블릿(sw600dp 이상)은 폭이 넉넉해 30개 막대를 굳이 스크롤로 몰아넣지 않아도 된다 —
        // 데스크탑과 동일하게 weight(1f) 균등분할로 폭을 꽉 채운다(InterstitialScreen.kt의 태블릿
        // 분기와 같은 기준, 사용자 요청으로 53차 추가). 폰은 기존 고정폭+가로스크롤 유지.
        val isTablet = LocalConfiguration.current.screenWidthDp >= 600
        Hairline()
        Spacer(Modifier.height(Spacing.md))
        Overline("최근 30일 일정 완료")
        Spacer(Modifier.height(4.dp))
        Text(
            if (isTablet) "막대 높이 = 일정 개수, 색상 = 완료율" else "막대 높이 = 일정 개수, 색상 = 완료율 · 좌우로 스크롤됩니다",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.sm))
        val barRowModifier = if (isTablet) {
            Modifier.fillMaxWidth().height(90.dp)
        } else {
            // 30개 막대를 폰 폭에 욱여넣으면 짓눌려 보이던 문제(사용자 실기기 확인) — 막대 하나 폭을
            // 고정하고 가로 스크롤로 바꿨다.
            Modifier.fillMaxWidth().height(90.dp).horizontalScroll(rememberScrollState(Int.MAX_VALUE))
        }
        Row(barRowModifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            dayStats.forEach { ds ->
                val pct = if (ds.cnt > 0) Math.round(ds.done * 100.0 / ds.cnt).toInt() else 0
                val barColor = when {
                    ds.cnt == 0 -> MaterialTheme.colorScheme.outlineVariant
                    pct == 100 -> palette.fillGood
                    pct > 0 -> palette.fillPartial
                    else -> palette.fillBad
                }
                val isToday = ds.date == today
                val columnModifier = if (isTablet) Modifier.weight(1f) else Modifier.width(20.dp)
                Column(columnModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.fillMaxWidth().height(60.dp), verticalArrangement = Arrangement.Bottom) {
                        val heightPct = (ds.cnt.toFloat() / maxDayCnt).coerceIn(if (ds.cnt > 0) 0.08f else 0.03f, 1f)
                        Row(Modifier.fillMaxWidth().height((60 * heightPct).dp).background(barColor, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))) {}
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

        // 82차(§9 "포모도로 세션 태그"): 태그별 누적 공부시간.
        val taggedSeconds = allStudyLog.filter { it.tag.isNotBlank() }.groupBy { it.tag }.mapValues { (_, v) -> v.sumOf { it.seconds } }
        if (taggedSeconds.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.lg))
            Hairline()
            Spacer(Modifier.height(Spacing.md))
            Overline("분야(태그)별 누적")
            Spacer(Modifier.height(Spacing.sm))
            val maxTagSeconds = maxOf(1, taggedSeconds.values.max())
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                taggedSeconds.entries.sortedByDescending { it.value }.forEach { (tag, seconds) ->
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(tag, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.W600, modifier = Modifier.weight(1f))
                            Text(formatHmsLog(seconds.toLong()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, softWrap = false)
                        }
                        Spacer(Modifier.height(2.dp))
                        val widthFraction = (seconds.toFloat() / maxTagSeconds).coerceIn(0.03f, 1f)
                        Row(
                            Modifier.fillMaxWidth(widthFraction).height(6.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                        ) {}
                    }
                }
            }
        }

        // 82차(§9 "일정표-계산기 진행량 그래프"): 계산기 연동 일정의 최근 30일 목표 대비 실제 완료량.
        // 85차: 과목(계산기 업무)별 상세 통계를 한 번에 접었다 펼 수 있게 요청 — 계산기 입력 탭의
        // "모두 펴기/모두 접기"와 같은 패턴(collapsedKeys Set), 여기선 id 대신 calcName으로 키를 잡는다.
        val linkedByTaskAndDate = allTasks.filter { it.linkedCalc != null }.groupBy { it.linkedCalc!! }
        if (linkedByTaskAndDate.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.lg))
            Hairline()
            Spacer(Modifier.height(Spacing.sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Overline("계산기 연동 진행량 · 최근 30일", Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    androidx.compose.material3.TextButton(onClick = { collapsedCalcNames.value = emptySet() }) { Text("모두 펴기") }
                    androidx.compose.material3.TextButton(onClick = { collapsedCalcNames.value = linkedByTaskAndDate.keys.toSet() }) { Text("모두 접기") }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            linkedByTaskAndDate.forEach { (calcName, tasks) ->
                val collapsed = calcName in collapsedCalcNames.value
                Row(
                    Modifier.fillMaxWidth().clickable {
                        collapsedCalcNames.value = if (collapsed) collapsedCalcNames.value - calcName else collapsedCalcNames.value + calcName
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.phonelock.app.ui.components.IconChip(
                        if (collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                        onClick = { collapsedCalcNames.value = if (collapsed) collapsedCalcNames.value - calcName else collapsedCalcNames.value + calcName }
                    )
                    Spacer(Modifier.width(4.dp))
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
                    Row(
                        Modifier.fillMaxWidth().height(70.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        series.forEach { (d, pair) ->
                            val (target, done) = pair
                            val achieved = target > 0 && done >= target
                            val barColor = when {
                                target <= 0 -> MaterialTheme.colorScheme.outlineVariant
                                achieved -> palette.fillGood
                                done > 0 -> palette.fillPartial
                                else -> palette.fillBad
                            }
                            Column(Modifier.width(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
 * 나눠 완료율을 비교만 한다(판정 로직과 무관, 순수 UI 집계, 데스크탑판과 대칭).
 */
@Composable
private fun WeekOverWeekCard(allTasks: List<CalendarTask>, today: LocalDate) {
    val (thisDone, thisTotal) = completionRateInRange(allTasks, today.minusDays(6).toString(), today.toString())
    val (lastDone, lastTotal) = completionRateInRange(allTasks, today.minusDays(13).toString(), today.minusDays(7).toString())
    if (thisTotal == 0 && lastTotal == 0) return

    val thisRate = if (thisTotal > 0) Math.round(thisDone * 100.0 / thisTotal).toInt() else 0
    val palette = LocalPhoneLockPalette.current
    com.phonelock.app.ui.components.LedgerSection("최근 7일 일정 완료율") {
        Row(verticalAlignment = Alignment.Bottom) {
            BigNumber("$thisRate", unit = "%", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(Spacing.md))
            if (lastTotal == 0) {
                Text("지난주 일정이 없어 비교할 수 없어요", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
            } else {
                val lastRate = Math.round(lastDone * 100.0 / lastTotal).toInt()
                val diff = thisRate - lastRate
                val diffColor = when {
                    diff > 0 -> palette.success
                    diff < 0 -> palette.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    "지난주 대비 " + if (diff > 0) "+$diff%p" else "$diff%p",
                    style = MaterialTheme.typography.titleMedium,
                    color = diffColor,
                    modifier = Modifier.alignByBaseline()
                )
            }
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

/**
 * 하루 평균 공부 시간(142차) — 기준은 [StudyStats] 주석 참고. 타일 대신 "이름 … 값" 줄로 쌓아서 폰 폭에서도
 * "1시간 23분" 같은 값이 잘리지 않는다.
 */
@Composable
private fun StudyAverageCard(summary: StudyStats.Summary) {
    com.phonelock.app.ui.components.LedgerSection("하루 평균 집중 시간") {
        StudyAverageRow("오늘", StudyStats.durationLabel(summary.todaySeconds))
        StudyAverageRow("최근 ${StudyStats.SHORT_WINDOW_DAYS}일 평균", summary.shortAverageSeconds?.let { StudyStats.durationLabel(it) } ?: "—")
        StudyAverageRow("최근 ${StudyStats.LONG_WINDOW_DAYS}일 평균", summary.longAverageSeconds?.let { StudyStats.durationLabel(it) } ?: "—")
        StudyAverageRow("집중한 날 평균", summary.activeDayAverageSeconds?.let { StudyStats.durationLabel(it) } ?: "—")
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "쉰 날도 0으로 셉니다. \"집중한 날 평균\"은 기록 있는 날만으로 나눕니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.lg))
    }
}

@Composable
private fun StudyAverageRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, softWrap = false)
    }
}
