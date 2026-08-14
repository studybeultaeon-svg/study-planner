package com.phonelock.desktop.ui

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

    Row(Modifier.fillMaxSize().padding(Spacing.md)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("오늘의 사용 통계", style = MaterialTheme.typography.headlineMedium)
                androidx.compose.material3.OutlinedButton(onClick = { exportUsageCsvToFile(repository) }) { Text("📄 CSV 내보내기") }
            }
            Spacer(Modifier.height(Spacing.md))
            if (rows.isEmpty()) {
                Text("아직 기록된 사용 데이터가 없습니다.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.name }) { row ->
                        val selected = row.name == selectedName
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)
                                .clickable { selectedName = row.name },
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Column(Modifier.padding(Spacing.sm)) {
                                Text(row.name, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(Spacing.xs))
                                if (row.limitSeconds != null) {
                                    val progress = (row.usedSeconds.toFloat() / row.limitSeconds).coerceIn(0f, 1f)
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                } else {
                                    Text("오늘 ${formatHms(row.usedSeconds)} 사용", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val detail = rows.firstOrNull { it.name == selectedName }
            if (detail == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "그룹을 선택하면 상세 사용량을 볼 수 있습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(Modifier.padding(Spacing.md)) {
                    Text(detail.name, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(Spacing.md))
                    if (detail.limitSeconds != null) {
                        val progress = (detail.usedSeconds.toFloat() / detail.limitSeconds).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(Spacing.sm))
                        Text("${formatHms(detail.usedSeconds)} / ${formatHms(detail.limitSeconds)}", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text("오늘 ${formatHms(detail.usedSeconds)} 사용", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        "🔓 재확인 통과 횟수: 오늘 ${detail.confirmCountToday}회 (어제 ${detail.confirmCountYesterday}회)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (detail.recentAverageSeconds > 0 && detail.usedSeconds > detail.recentAverageSeconds * ANOMALY_MULTIPLIER) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "⚠️ 오늘 사용이 최근 7일 평균(${formatHms(detail.recentAverageSeconds)})보다 눈에 띄게 많아요.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFBBF24)
                        )
                    }
                }
            }
        }
    }
}
