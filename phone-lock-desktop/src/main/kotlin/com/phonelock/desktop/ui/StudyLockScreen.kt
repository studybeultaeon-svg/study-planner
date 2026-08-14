package com.phonelock.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.monitor.StudyLockStatus
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * 공부앱 타이머가 켜져 있는 동안 데스크탑 전체화면을 덮는 잠금 화면. 화면 중앙을 기준으로 위쪽엔
 * 지금 공부/휴식 중인지와 경과·남은 시간을, 아래쪽엔 예외로 허용된 프로그램을 바로 열 수 있는
 * 버튼 목록을 보여준다.
 */
@Composable
fun StudyLockScreen(
    status: StudyLockStatus,
    onLaunchApp: (String) -> Unit,
    onStopTimer: () -> Unit,
    onSwitchToBreak: () -> Unit,
    toastMessage: String? = null,
    onToastShown: () -> Unit = {}
) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
        }
    }

    // 계측용 임시 토스트(BUGS.md "타이머 정지/전환/허용앱 실행 버튼 무반응") — 실패 원인을
    // debug.log뿐 아니라 화면에서도 바로 볼 수 있게 4초간 노출 후 스스로 사라진다.
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(4000)
            onToastShown()
        }
    }

    Surface {
        Column(modifier = Modifier.fillMaxSize()) {
            if (toastMessage != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        toastMessage,
                        modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("🔒 공부 중입니다", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(Spacing.md))
                val elapsed = ((nowMillis - status.studyStartedAtMillis) / 1000L).coerceAtLeast(0L)
                Text("📚 공부 중 · 경과 시간 ${formatHms(elapsed)}", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Spacing.md))
                Text(
                    "허용된 프로그램 외에는 열 수 없습니다. 아래에서 허용된 프로그램을 실행하세요.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (status.isRemote) {
                    Spacer(Modifier.height(Spacing.lg))
                    Text(
                        "다른 기기에서 공부 타이머가 실행 중이라 이 기기도 함께 잠겼습니다. 정지/전환은 그 기기에서 해주세요.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Spacer(Modifier.height(Spacing.lg))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedButton(onClick = onStopTimer) { Text("⏹ 타이머 정지") }
                        if (status.isPomodoroMode) {
                            Spacer(Modifier.width(Spacing.xs))
                            Button(onClick = onSwitchToBreak) { Text("☕ 휴식으로 전환") }
                        }
                    }
                    if (status.isPomodoroMode) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "공부 시간을 다 채우기 전엔 전환이 적용되지 않습니다.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Text("허용된 프로그램", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.sm))
                if (status.allowedApps.isEmpty()) {
                    Text("공부앱 타이머 탭에서 허용 프로그램을 등록할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        items(status.allowedApps) { appName ->
                            Button(onClick = { onLaunchApp(appName) }) {
                                Text(appName)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatHms(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d".format(h, m, s)
}
