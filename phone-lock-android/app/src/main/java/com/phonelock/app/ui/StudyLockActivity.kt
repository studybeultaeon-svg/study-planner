package com.phonelock.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.service.IntentExtras
import com.phonelock.app.service.PomodoroSyncClient
import com.phonelock.app.ui.theme.PhoneLockTheme
import kotlinx.coroutines.delay

// AppMonitorAccessibilityService.REMOTE_STUDY_SIGNAL_STALE_MS와 같은 값.
private const val REMOTE_STUDY_SIGNAL_STALE_MS = 20 * 60 * 1000L

/** 이 기기의 로컬 타이머뿐 아니라, 다른 기기가 공부 페이즈를 실행 중이라는 신호가 아직 유효한지. */
private suspend fun isRemoteStudyTimerActive(repository: PhoneLockRepository): Boolean {
    val url = repository.fbDatabaseUrl
    val key = repository.fbApiKey
    if (!PomodoroSyncClient.isStudyTimerActive(url, key)) return false
    val updatedAt = PomodoroSyncClient.remoteUpdatedAtMillis(url, key)
    return updatedAt > 0 && System.currentTimeMillis() - updatedAt < REMOTE_STUDY_SIGNAL_STALE_MS
}

/**
 * 공부앱 타이머가 "공부" 페이즈로 진행 중일 때(휴식 중엔 뜨지 않음) 허용 목록 외 앱이 감지되면
 * 뜨는 전체화면. BlockActivity와 같은 "탈출구 없음" 톤이되, 아래쪽에 허용된 앱을 바로 열 수 있는
 * 버튼을 둔다.
 */
class StudyLockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val allowedPackages = intent.getStringArrayExtra(IntentExtras.EXTRA_STUDY_LOCK_ALLOWED_PACKAGES)?.toList() ?: emptyList()
        val studyStartedAt = intent.getLongExtra(IntentExtras.EXTRA_STUDY_LOCK_STARTED_AT, System.currentTimeMillis())
        val isPomodoroMode = intent.getBooleanExtra(IntentExtras.EXTRA_STUDY_LOCK_IS_POMODORO, false)
        val isRemote = intent.getBooleanExtra(IntentExtras.EXTRA_STUDY_LOCK_IS_REMOTE, false)
        val repository = PhoneLockRepository(applicationContext)

        setContent {
            val prefs = AppPreferences(applicationContext)
            PhoneLockTheme(prefs.themeMode, prefs.customThemeBackground, prefs.customThemeAccent) {
                StudyLockScreen(
                    allowedPackages = allowedPackages,
                    studyStartedAt = studyStartedAt,
                    isPomodoroMode = isPomodoroMode,
                    isRemote = isRemote,
                    onLaunchApp = { packageName ->
                        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                            finish()
                        }
                    },
                    // 로컬 저장소를 직접 호출 — 예전엔 웹뷰 evaluateJavascript + Firebase remoteCommand
                    // 비동기 왕복이라 실패해도 신호가 없었다. 지금은 즉시 반영되고, 잠금화면은 다음
                    // 접근성 서비스 tick(최대 2초)에서 checkStudyLock()이 로컬 상태를 다시 읽어 닫힌다.
                    onStopTimer = { repository.timerStop() },
                    onSwitchToBreak = { repository.timerSwitchPhase() },
                    // 이 기기의 로컬 타이머뿐 아니라 다른 기기의 원격 신호로 잠긴 경우도 그 신호가
                    // 꺼지면 같이 풀려야 한다(checkStudyLock과 같은 OR 판정).
                    isStillActive = { repository.isStudyLockActive() || isRemoteStudyTimerActive(repository) },
                    onInactive = { finish() }
                )
            }
        }
    }

    // 뒤로가기로 이 화면을 벗어나면(=허용 안 된 앱으로 되돌아가면) 다음 tick(최대 2초)에서 다시
    // 감지돼 재차단되므로, 여기서는 그냥 홈으로 보낸다(BlockActivity와 동일한 처리).
    override fun onBackPressed() {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(homeIntent)
        finish()
    }

}

@Composable
private fun StudyLockScreen(
    allowedPackages: List<String>,
    studyStartedAt: Long,
    isPomodoroMode: Boolean,
    isRemote: Boolean,
    onLaunchApp: (String) -> Unit,
    onStopTimer: () -> Unit,
    onSwitchToBreak: () -> Unit,
    isStillActive: suspend () -> Boolean,
    onInactive: () -> Unit
) {
    val context = LocalContext.current
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
            // 정지/전환 버튼이 로컬 상태를 즉시 바꾸므로, 여기서 바로 반영해 화면을 닫는다 — 예전엔
            // 접근성 서비스의 다음 tick(최대 2초)까지 기다려야 닫혔다("잠금화면 안 닫힘" 버그).
            if (!isStillActive()) {
                onInactive()
                return@LaunchedEffect
            }
        }
    }
    val allowedApps = remember(allowedPackages) {
        val pm = context.packageManager
        allowedPackages.map { pkg ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            AppInfo(label, pkg)
        }
    }

    Surface {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("🔒 공부 중입니다", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                val elapsed = ((nowMillis - studyStartedAt) / 1000L).coerceAtLeast(0L)
                Text("📚 공부 중 · 경과 시간 ${formatHms(elapsed)}", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Text(
                    "허용된 앱 외에는 열 수 없습니다. 아래에서 허용된 앱을 실행하세요.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (isRemote) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "다른 기기에서 공부 타이머가 실행 중이라 이 기기도 함께 잠겼습니다. 정지/전환은 그 기기에서 해주세요.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onStopTimer) { Text("⏹ 타이머 정지") }
                        if (isPomodoroMode) {
                            Spacer(Modifier.width(4.dp))
                            Button(onClick = onSwitchToBreak) { Text("☕ 휴식으로 전환") }
                        }
                    }
                    if (isPomodoroMode) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "공부 시간을 다 채우기 전엔 전환이 적용되지 않습니다.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Text("허용된 앱", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (allowedApps.isEmpty()) {
                    Text("설정 탭에서 공부 잠금 허용 앱을 등록할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(allowedApps) { app ->
                            Button(onClick = { onLaunchApp(app.packageName) }) {
                                Text(app.label)
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
