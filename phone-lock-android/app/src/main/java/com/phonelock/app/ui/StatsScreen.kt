package com.phonelock.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.ui.components.formatHms
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import com.phonelock.app.ui.components.BigNumber
import com.phonelock.app.ui.components.DurationHero
import com.phonelock.app.ui.components.Hairline
import com.phonelock.app.ui.components.NoticeStrip
import com.phonelock.app.ui.components.NoticeTone
import com.phonelock.app.ui.components.Overline
import com.phonelock.app.ui.components.ProgressLine
import com.phonelock.app.ui.theme.LocalPhoneLockPalette
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class GroupUsage(
    val name: String, val usedSeconds: Int, val limitSeconds: Int?,
    val confirmCountToday: Int = 0, val confirmCountYesterday: Int = 0,
    val recentAverageSeconds: Int = 0
)

/** 오늘 사용량이 최근 7일 평균의 1.5배를 넘으면 이상 사용으로 간주(전문가 종합분석 보고서 #34). */
private const val ANOMALY_MULTIPLIER = 1.5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(repository: PhoneLockRepository) {
    var rows by remember { mutableStateOf(emptyList<GroupUsage>()) }
    var quoteOutcomes by remember { mutableStateOf(emptyList<com.phonelock.app.data.QuoteOutcome>()) }
    // 84차: 태블릿 좌(목록 요약)/우(선택 그룹 상세) 분할에서 어느 그룹이 선택됐는지 — 데스크탑판
    // StatsScreen.kt의 selectedName과 동일한 역할.
    var selectedName by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val csv = repository.exportUsageCsv()
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                Toast.makeText(context, "CSV 내보내기 완료", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 탭을 떠났다 와야만 새로고침되던 문제를 없애기 위해, 이 화면을 보고 있는 동안 주기적으로 다시 읽어온다.
    LaunchedEffect(Unit) {
        while (true) {
            val groups = repository.getAllEnabledGroups()
            rows = groups.map { group ->
                val usedSeconds = repository.getTodayUsageSeconds(group.id)
                GroupUsage(
                    group.name, usedSeconds, group.dailyLimitSeconds,
                    confirmCountToday = repository.getConfirmCountToday(group.id),
                    confirmCountYesterday = repository.getConfirmCountYesterday(group.id),
                    recentAverageSeconds = repository.getRecentAverageUsageSeconds(group.id)
                )
            }
            delay(2000)
        }
    }

    // 82차(§9/§11 "회유 멘트 성공률 통계") — 목록처럼 자주 갱신될 필요 없어 1회만 로드.
    LaunchedEffect(Unit) {
        quoteOutcomes = repository.getAllQuoteOutcomesOnce()
    }

    // 144차: 상단 앱바 대신 "오늘 사용한 시간 합계"를 히어로로, 규칙마다 사용량 막대 한 줄(한도에 가까우면 경고색, 넘으면
    // 오류색) — 카드 없이 가는 선으로 나눈다. CSV 내보내기는 히어로 오른쪽 글자 버튼.
    val totalUsed = rows.sumOf { it.usedSeconds }.toLong()
    val hero: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Overline("오늘 차단 규칙 앱 사용")
                DurationHero(totalUsed, numberStyle = MaterialTheme.typography.displayMedium)
            }
            TextButton(onClick = { csvExportLauncher.launch("usage_${java.time.LocalDate.now()}.csv") }) {
                Text("CSV 내보내기", maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Hairline()
    }
    if (rows.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(horizontal = Spacing.gutter, vertical = Spacing.md)) {
            hero()
            Spacer(Modifier.height(Spacing.md))
            Text("아직 기록된 사용 데이터가 없습니다.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else if (com.phonelock.app.ui.components.isTabletWidth()) {
        // 84차: 데스크탑 StatsScreen.kt와 같은 좌(규칙별 요약 목록, 선택 가능)/우(선택한 규칙 상세) 분할.
        com.phonelock.app.ui.components.ResponsiveSplit(
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lg, vertical = Spacing.md),
            left = {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { hero() }
                    items(rows, key = { it.name }) { row ->
                        val selected = row.name == selectedName
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { selectedName = row.name }
                                .padding(horizontal = Spacing.sm)
                        ) { UsageRow(row, compact = true) }
                        Hairline()
                    }
                    if (quoteOutcomes.isNotEmpty()) {
                        item { QuoteOutcomesSection(quoteOutcomes) }
                    }
                }
            },
            right = {
                val detail = rows.firstOrNull { it.name == selectedName }
                if (detail == null) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "차단 규칙을 고르면 상세 사용량을 볼 수 있습니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(Spacing.md)) {
                        Overline("오늘 사용")
                        Text(detail.name, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(Spacing.md))
                        UsageRow(detail, compact = false)
                    }
                }
            }
        )
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md)
        ) {
            item { hero() }
            items(rows, key = { it.name }) { row ->
                UsageRow(row, compact = false)
                Hairline()
            }
            if (quoteOutcomes.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(Spacing.md))
                    QuoteOutcomesSection(quoteOutcomes)
                }
            }
        }
    }
}

/** 규칙 하나의 오늘 사용량 — 이름 · 사용/한도(오른쪽 굵은 숫자) · 막대 · 재확인 횟수 · 평소보다 많을 때 경고 띠. */
@Composable
private fun UsageRow(row: GroupUsage, compact: Boolean) {
    val palette = LocalPhoneLockPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(row.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                if (row.limitSeconds != null) "${formatHms(row.usedSeconds)} / ${formatHms(row.limitSeconds)}" else formatHms(row.usedSeconds),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                softWrap = false
            )
        }
        if (row.limitSeconds != null) {
            val progress = (row.usedSeconds.toFloat() / row.limitSeconds).coerceIn(0f, 1f)
            Spacer(Modifier.height(Spacing.sm))
            ProgressLine(
                progress,
                color = when {
                    row.usedSeconds >= row.limitSeconds -> palette.fillBad
                    progress >= 0.8f -> palette.fillPartial
                    else -> MaterialTheme.colorScheme.primary
                }
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "열기 전 확인 통과 오늘 ${row.confirmCountToday}회 · 어제 ${row.confirmCountYesterday}회",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!compact && row.recentAverageSeconds > 0 && row.usedSeconds > row.recentAverageSeconds * ANOMALY_MULTIPLIER) {
            Spacer(Modifier.height(Spacing.sm))
            NoticeStrip(
                "오늘 사용이 최근 7일 평균(${formatHms(row.recentAverageSeconds)})보다 눈에 띄게 많아요",
                tone = NoticeTone.Warning
            )
        }
    }
}

/** "회유 멘트 성공률" 섹션(82차) — 전체/티어별 성공률 + 가장 많이 굴복한 문구 3개. 폰의 LazyColumn
 * item과 태블릿 좌측 패널 둘 다에서 재사용한다. */
@Composable
private fun QuoteOutcomesSection(quoteOutcomes: List<com.phonelock.app.data.QuoteOutcome>) {
    com.phonelock.app.ui.components.LedgerSection("확인 질문에서 멈춘 비율") {
        val overallStop = quoteOutcomes.count { it.choice == "STOP" }
        val overallRate = Math.round(overallStop * 100.0 / quoteOutcomes.size).toInt()
        BigNumber("$overallRate", unit = "%", style = MaterialTheme.typography.displaySmall)
        Text(
            "문구가 뜬 상태에서 \"중단\"(저항)을 고른 비율 · ${overallStop}/${quoteOutcomes.size}회",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.sm))
        val byTier = quoteOutcomes.groupBy { it.tier }.toSortedMap()
        byTier.forEach { (tier, outcomes) ->
            val stopCount = outcomes.count { it.choice == "STOP" }
            val rate = Math.round(stopCount * 100.0 / outcomes.size).toInt()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("${tierLabel(tier)} 문구", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("$rate% · ${stopCount}/${outcomes.size}", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
            }
        }
        val hardestQuotes = quoteOutcomes.groupBy { it.quoteText }
            .filter { (_, v) -> v.size >= 2 }
            .mapValues { (_, v) -> v.count { it.choice == "PROCEED" } * 100.0 / v.size }
            .toList().sortedByDescending { it.second }.take(3)
        if (hardestQuotes.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Overline("가장 많이 넘어간 문구")
            Spacer(Modifier.height(4.dp))
            hardestQuotes.forEach { (quote, rate) ->
                Text("\"$quote\" — ${Math.round(rate)}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}


private fun tierLabel(tier: Int): String = when (tier) {
    0 -> "순한"; 1 -> "중간"; 2 -> "매운"; 3 -> "독한"; else -> "극한"
}
