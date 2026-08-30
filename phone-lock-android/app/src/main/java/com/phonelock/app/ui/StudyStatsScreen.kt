package com.phonelock.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import com.phonelock.app.data.CalendarTask
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.ui.theme.Spacing
import java.time.LocalDate

private data class DayStat(val date: LocalDate, val cnt: Int, val done: Int)

/**
 * 네이티브 통계(5단계). 웹앱 index.html "통계" 탭(`renderStats()`)을 이식 — 별도 데이터 모델 없이
 * 캘린더 일정(`repository.getAllCalendarTasksOnce()`)만 집계하는 읽기 전용 파생 뷰. 51차: "전체 일정/완료/
 * 완료율" 타일을 전체 누적이 아니라 오늘 하루 기준으로 바꾸고, 회독 단계별 완료 현황 카드는 제거(사용자
 * 요청, 데스크탑판과 대칭). 플랫폼 공유 모듈 없어 대칭 복제(계산기/일정표와 같은 패턴) — DECISIONS.md 참고.
 */
@Composable
fun StudyStatsScreen(repository: PhoneLockRepository) {
    var allTasks by remember { mutableStateOf<List<CalendarTask>>(emptyList()) }

    LaunchedEffect(Unit) {
        repository.syncCalendarFromFirebase()
        allTasks = repository.getAllCalendarTasksOnce()
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

    // 51차: 전체 누적이 아니라 오늘 하루 일정 기준으로 바꿈(사용자 요청, 데스크탑판과 대칭).
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

    // 최고 스트릭(51차, 루틴 통계와 같은 톤, 데스크탑판과 대칭): 과거→현재로 훑으며 가장 길었던 연속 완료 구간을 찾는다.
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

    Column(Modifier.fillMaxSize().padding(Spacing.md).verticalScroll(rememberScrollState())) {
        Text("📈 통계", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text("캘린더 회독 진행 기준", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.sm))

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
        }
        Spacer(Modifier.height(Spacing.sm))
        StatTile("최고 스트릭", "${bestStreak}일" + if (bestStreak > 0) "🔥" else "", Modifier.fillMaxWidth(), accentColor = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(Spacing.md))

        WeekOverWeekCard(allTasks = allTasks, today = today)
        Spacer(Modifier.height(Spacing.md))

        // 태블릿(sw600dp 이상)은 폭이 넉넉해 30개 막대를 굳이 스크롤로 몰아넣지 않아도 된다 —
        // 데스크탑과 동일하게 weight(1f) 균등분할로 폭을 꽉 채운다(InterstitialScreen.kt의 태블릿
        // 분기와 같은 기준, 사용자 요청으로 53차 추가). 폰은 기존 고정폭+가로스크롤 유지.
        val isTablet = LocalConfiguration.current.screenWidthDp >= 600
        Text("최근 30일 완료 추이", style = MaterialTheme.typography.titleMedium)
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
            Modifier.fillMaxWidth().height(90.dp).horizontalScroll(rememberScrollState())
        }
        Row(barRowModifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            dayStats.forEach { ds ->
                val pct = if (ds.cnt > 0) Math.round(ds.done * 100.0 / ds.cnt).toInt() else 0
                val barColor = when {
                    ds.cnt == 0 -> MaterialTheme.colorScheme.outlineVariant
                    pct == 100 -> Color(0xFF34D399)
                    pct > 0 -> Color(0xFFFBBF24)
                    else -> Color(0xFFF87171)
                }
                val isToday = ds.date == today
                val columnModifier = if (isTablet) Modifier.weight(1f) else Modifier.width(20.dp)
                Column(columnModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.fillMaxWidth().height(60.dp), verticalArrangement = Arrangement.Bottom) {
                        val heightPct = (ds.cnt.toFloat() / maxDayCnt).coerceIn(if (ds.cnt > 0) 0.08f else 0.03f, 1f)
                        Row(Modifier.fillMaxWidth().height((60 * heightPct).dp).background(barColor)) {}
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
    Text("최근 7일 vs 지난 7일 완료율", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(Spacing.xs))
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
