package com.phonelock.desktop.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Group
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.ui.components.formatHms
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.delay

private data class GroupUsage(
    val name: String, val usedSeconds: Int, val limitSeconds: Int?,
    val confirmCountToday: Int = 0, val confirmCountYesterday: Int = 0,
    val recentAverageSeconds: Int = 0
)

/** 오늘 사용량이 최근 7일 평균의 1.5배를 넘으면 이상 사용으로 간주(전문가 종합분석 보고서 #34). */
private const val ANOMALY_MULTIPLIER = 1.5

/** 저장 위치를 물어보는 표준 AWT 파일 다이얼로그(Compose Desktop엔 내장 파일 선택기가 없어 이 방식이 통상적). */
private fun exportUsageCsvToFile(repository: Repository) {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "사용 기록 CSV 저장", java.awt.FileDialog.SAVE)
    dialog.file = "usage_${java.time.LocalDate.now()}.csv"
    dialog.isVisible = true
    val dir = dialog.directory ?: return
    val name = dialog.file ?: return
    val fileName = if (name.endsWith(".csv", ignoreCase = true)) name else "$name.csv"
    java.io.File(dir, fileName).writeText(repository.exportUsageCsv())
}

/**
 * 32차 아이디어("관리앱도 데스크탑 좌우 분할") 적용 — 왼쪽은 그룹별 요약(이름+진행바만),
 * 오른쪽은 선택한 그룹의 상세(같은 데이터를 크게). 통계엔 그래프 등 별도 상세 데이터가 없어서
 * 새 데이터를 만들어내는 대신 있는 데이터를 선택 기반으로 확대해서 보여주는 정도로 범위를 제한했다.
 */
@Composable
fun StatsScreen(repository: Repository) {
    var rows by remember { mutableStateOf(emptyList<GroupUsage>()) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var quoteOutcomes by remember { mutableStateOf(emptyList<com.phonelock.desktop.data.QuoteOutcome>()) }

    // 82차(§9/§11 "회유 멘트 성공률 통계") — 목록처럼 자주 갱신될 필요 없어 1회만 로드.
    LaunchedEffect(Unit) {
        quoteOutcomes = repository.getAllQuoteOutcomesOnce()
    }

    LaunchedEffect(Unit) {
        while (true) {
            val groups: List<Group> = repository.getEnabledGroups()
            rows = groups.map { group ->
                GroupUsage(
                    group.name, repository.getTodayUsageSeconds(group.id), group.dailyLimitSeconds,
                    confirmCountToday = repository.getConfirmCountToday(group.id),
                    confirmCountYesterday = repository.getConfirmCountYesterday(group.id),
                    recentAverageSeconds = repository.getRecentAverageUsageSeconds(group.id)
                )
            }
            delay(2000)
        }
    }

    // 144차(안드로이드판과 같은 언어): "오늘 사용한 시간 합계"를 히어로로, 규칙마다 사용량 막대 한 줄(한도에 가까우면 경고색,
    // 넘으면 오류색) — 카드 없이 가는 선으로. 오른쪽은 고른 규칙의 상세(마스터-디테일).
    val palette = com.phonelock.desktop.ui.theme.LocalPhoneLockPalette.current
    val totalUsed = rows.sumOf { it.usedSeconds }.toLong()
    Row(Modifier.fillMaxSize().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    com.phonelock.desktop.ui.components.Overline("오늘 차단 규칙 프로그램 사용")
                    com.phonelock.desktop.ui.components.DurationHero(totalUsed, numberStyle = MaterialTheme.typography.displayMedium)
                }
                androidx.compose.material3.TextButton(onClick = { exportUsageCsvToFile(repository) }) { Text("CSV 내보내기", maxLines = 1, softWrap = false) }
            }
            Spacer(Modifier.height(Spacing.md))
            com.phonelock.desktop.ui.components.Hairline()
            if (rows.isEmpty()) {
                Spacer(Modifier.height(Spacing.md))
                Text("아직 기록된 사용 데이터가 없습니다.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(rows, key = { it.name }) { row ->
                        val selected = row.name == selectedName
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                .clickable { selectedName = row.name }
                                .padding(horizontal = Spacing.sm, vertical = 14.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                                Text(row.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
                                Text(
                                    if (row.limitSeconds != null) "${formatHms(row.usedSeconds)} / ${formatHms(row.limitSeconds)}" else formatHms(row.usedSeconds),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            if (row.limitSeconds != null) {
                                val progress = (row.usedSeconds.toFloat() / row.limitSeconds).coerceIn(0f, 1f)
                                Spacer(Modifier.height(Spacing.sm))
                                com.phonelock.desktop.ui.components.ProgressLine(
                                    progress,
                                    color = when {
                                        row.usedSeconds >= row.limitSeconds -> palette.error
                                        progress >= 0.8f -> palette.warning
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )
                            }
                        }
                        com.phonelock.desktop.ui.components.Hairline()
                    }
                }
            }
            if (quoteOutcomes.isNotEmpty()) {
                val overallStop = quoteOutcomes.count { it.choice == "STOP" }
                val overallRate = Math.round(overallStop * 100.0 / quoteOutcomes.size).toInt()
                com.phonelock.desktop.ui.components.LedgerSection("확인 질문에서 멈춘 비율") {
                    com.phonelock.desktop.ui.components.BigNumber("$overallRate", unit = "%", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "문구가 뜬 상태에서 \"중단\"(저항)을 고른 비율 · ${overallStop}/${quoteOutcomes.size}회",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        androidx.compose.material3.VerticalDivider(Modifier.padding(horizontal = Spacing.lg), color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val detail = rows.firstOrNull { it.name == selectedName }
            if (detail == null) {
                Column(Modifier.fillMaxSize().padding(top = Spacing.xxl)) {
                    com.phonelock.desktop.ui.components.Overline("상세")
                    Text("차단 규칙을 고르면\n상세 사용량을 볼 수 있습니다.", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(Modifier.padding(top = Spacing.sm)) {
                    com.phonelock.desktop.ui.components.Overline("오늘 사용")
                    Text(detail.name, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(Spacing.md))
                    com.phonelock.desktop.ui.components.DurationHero(detail.usedSeconds.toLong(), numberStyle = MaterialTheme.typography.displaySmall)
                    if (detail.limitSeconds != null) {
                        val progress = (detail.usedSeconds.toFloat() / detail.limitSeconds).coerceIn(0f, 1f)
                        Spacer(Modifier.height(Spacing.sm))
                        com.phonelock.desktop.ui.components.ProgressLine(
                            progress,
                            color = if (detail.usedSeconds >= detail.limitSeconds) palette.error else if (progress >= 0.8f) palette.warning else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text("하루 한도 ${formatHms(detail.limitSeconds)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        "열기 전 확인 통과 오늘 ${detail.confirmCountToday}회 · 어제 ${detail.confirmCountYesterday}회",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (detail.recentAverageSeconds > 0 && detail.usedSeconds > detail.recentAverageSeconds * ANOMALY_MULTIPLIER) {
                        Spacer(Modifier.height(Spacing.md))
                        com.phonelock.desktop.ui.components.NoticeStrip(
                            "오늘 사용이 최근 7일 평균(${formatHms(detail.recentAverageSeconds)})보다 눈에 띄게 많아요",
                            tone = com.phonelock.desktop.ui.components.NoticeTone.Warning
                        )
                    }
                }
            }
        }
    }
}
