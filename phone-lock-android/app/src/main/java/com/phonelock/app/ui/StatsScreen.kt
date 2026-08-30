package com.phonelock.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.ui.components.formatHms
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("오늘의 사용 통계") },
                actions = {
                    OutlinedButton(onClick = { csvExportLauncher.launch("usage_${java.time.LocalDate.now()}.csv") }) {
                        Text("📄 CSV")
                    }
                }
            )
        }
    ) { padding ->
        if (rows.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
                Text("아직 기록된 사용 데이터가 없습니다.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
                items(rows) { row ->
                    Text(row.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Spacing.xs))
                    if (row.limitSeconds != null) {
                        val progress = (row.usedSeconds.toFloat() / row.limitSeconds).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(Spacing.xs))
                        Text("${formatHms(row.usedSeconds)} / ${formatHms(row.limitSeconds)}")
                    } else {
                        Text("오늘 ${formatHms(row.usedSeconds)} 사용")
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "🔓 재확인 통과 횟수: 오늘 ${row.confirmCountToday}회 (어제 ${row.confirmCountYesterday}회)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (row.recentAverageSeconds > 0 && row.usedSeconds > row.recentAverageSeconds * ANOMALY_MULTIPLIER) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "⚠️ 오늘 사용이 최근 7일 평균(${formatHms(row.recentAverageSeconds)})보다 눈에 띄게 많아요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))
                }
            }
        }
    }
}
