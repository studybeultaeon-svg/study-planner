package com.phonelock.app.ui

import androidx.compose.ui.unit.sp
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.TimerRunState
import com.phonelock.app.data.getCalendarTasks
import com.phonelock.app.service.IntentExtras
import com.phonelock.app.service.PomodoroSyncClient
import com.phonelock.app.ui.theme.PhoneLockTheme
import com.phonelock.app.ui.theme.applyThemeWindowBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

        val prefs = AppPreferences(applicationContext)
        applyThemeWindowBackground(prefs)
        setContent {
            PhoneLockTheme(prefs.themeMode, prefs.customThemeBackground, prefs.customThemeAccent, prefs.fontScale, performanceMode = prefs.minimalMode) {
                StudyLockScreen(
                    allowedPackages = allowedPackages,
                    studyStartedAt = studyStartedAt,
                    isPomodoroMode = isPomodoroMode,
                    isRemote = isRemote,
                    repository = repository,
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
                    onStopTimer = { note, tag -> repository.timerStop(note, tag) },
                    onSwitchToBreak = { repository.timerSwitchPhase() },
                    // 126차(사용자 요청): 공부 중에 일정이 바뀌어도 타이머를 끄지 않아도 되게 —
                    // 지금까지 잰 구간은 바꾸기 전 이름으로 기록에 적립된다(timerChangeTask).
                    onChangeTask = { repository.timerChangeTask(it) },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudyLockScreen(
    allowedPackages: List<String>,
    studyStartedAt: Long,
    isPomodoroMode: Boolean,
    isRemote: Boolean,
    repository: PhoneLockRepository,
    onLaunchApp: (String) -> Unit,
    onStopTimer: (String, String) -> Unit,
    onSwitchToBreak: () -> Unit,
    onChangeTask: (String) -> Unit,
    isStillActive: suspend () -> Boolean,
    onInactive: () -> Unit
) {
    val context = LocalContext.current
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    // 92차(사용자 요청): 39차 정지 시 회고 입력이 타이머 탭 정지 버튼에만 있고 이 잠금 화면의
    // "타이머 정지" 버튼엔 빠져있었다 — 같은 다이얼로그를 여기서도 띄운 뒤 timerStop(note, tag)를 부른다.
    var showStopNoteDialog by remember { mutableStateOf(false) }
    var stopNoteText by remember { mutableStateOf("") }
    var stopTagText by remember { mutableStateOf("") }
    // 126차(사용자 요청): 허용된 앱 목록이 화면 아래 절반을 늘 차지해서 위쪽 타이머·정지 버튼까지
    // 스크롤해야 닿았다 — 목록은 버튼을 눌렀을 때 뜨는 다이얼로그로 옮기고 화면은 타이머만 쓴다.
    var showAllowedAppsDialog by remember { mutableStateOf(false) }
    // 126차(사용자 요청): 타이머를 끄지 않고 공부 일정만 바꾸기.
    var showTaskChangeDialog by remember { mutableStateOf(false) }
    var todayTasks by remember { mutableStateOf(listOf<com.phonelock.app.data.CalendarTask>()) }
    // 92차(사용자 요청, "디자인이 밋밋하다/정보가 부족하다"): StudyTimerScreen과 같은 방식으로
    // TimerRunState/원격 신호/오늘 누적 공부시간을 읽어와 큰 원형 진행률+숫자로 보여준다.
    var run by remember { mutableStateOf(repository.getTimerRun()) }
    var todayLogSeconds by remember { mutableStateOf(0L) }
    var remoteTaskName by remember { mutableStateOf("") }
    var remotePhaseStartedAt by remember { mutableStateOf(0L) }
    var remotePhaseEndAt by remember { mutableStateOf(0L) }
    var remoteMode by remember { mutableStateOf(if (isPomodoroMode) "pomodoro" else "plain") }
    var tickCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
            tickCount++
            run = repository.getTimerRun()
            if (isRemote && run == null) {
                withContext(Dispatchers.IO) {
                    val url = repository.fbDatabaseUrl; val key = repository.fbApiKey
                    remoteTaskName = PomodoroSyncClient.remoteTaskName(url, key)
                    remotePhaseStartedAt = PomodoroSyncClient.remotePhaseStartedAt(url, key)
                    remotePhaseEndAt = PomodoroSyncClient.currentPhaseEndAt(url, key)
                    remoteMode = if (PomodoroSyncClient.isPomodoroMode(url, key)) "pomodoro" else "plain"
                }
            }
            if (tickCount % 5 == 0) {
                todayLogSeconds = repository.getTodayStudyLog().sumOf { it.seconds }.toLong()
                // 일정 변경 다이얼로그에서 고를 오늘 일정 목록 — 같은 5초 주기에 얹어 따로 루프를 만들지 않는다.
                todayTasks = repository.getCalendarTasks(repository.todayCalendarDateKey())
            }
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

    val current = run ?: if (isRemote) TimerRunState(
        taskName = remoteTaskName,
        mode = remoteMode,
        phase = "study",
        phaseStartedAt = if (remotePhaseStartedAt > 0) remotePhaseStartedAt else studyStartedAt,
        phaseEndAt = remotePhaseEndAt,
        cycleCount = 0,
        breakExtraUsed = false
    ) else null
    val isPomodoro = (current?.mode ?: if (isPomodoroMode) "pomodoro" else "plain") == "pomodoro"
    val phaseStartedAt = current?.phaseStartedAt ?: studyStartedAt
    val phaseEndAt = current?.phaseEndAt ?: 0L
    val taskName = current?.taskName.orEmpty()
    val elapsedSec = ((nowMillis - phaseStartedAt) / 1000L).coerceAtLeast(0L)
    val progress = if (isPomodoro && phaseEndAt > phaseStartedAt) {
        ((nowMillis - phaseStartedAt).toFloat() / (phaseEndAt - phaseStartedAt).toFloat()).coerceIn(0f, 1f)
    } else null
    val remainingSec = if (isPomodoro && phaseEndAt > 0) ((phaseEndAt - nowMillis) / 1000L).coerceAtLeast(0L) else null
    val secondHandAngle = if (!isPomodoro) (elapsedSec % 60) * 6f else null

    // 144차 리디자인: 원형 링 대신 화면 폭을 채우는 큰 시간 숫자 + 진행 막대(뽀모도로), 위에 상태 줄과 일정 이름,
    // 버튼은 엄지가 닿는 아래쪽. 표준 모드는 강조색이 옅게 번지는 바탕, 성능 모드는 단색.
    val performance = com.phonelock.app.ui.theme.LocalPerformanceMode.current
    val bgModifier = if (performance) Modifier.background(MaterialTheme.colorScheme.background)
    else Modifier.background(
        Brush.radialGradient(
            colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), MaterialTheme.colorScheme.background),
            radius = 1400f
        )
    )

    Box(Modifier.fillMaxSize().then(bgModifier)) {
        // 96차: 세로 폭이 좁은 화면(태블릿 가로 등)에서도 정지 버튼까지 닿도록 스크롤 가능.
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.phonelock.app.ui.components.LiveDot(MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                com.phonelock.app.ui.components.Overline(
                    (if (isPomodoro) "뽀모도로 · 집중 잠금" else "집중 잠금") + if (isRemote) " · 다른 기기" else "",
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                taskName.ifBlank { "이름 없는 집중" },
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2
            )
            Spacer(Modifier.height(20.dp))
            com.phonelock.app.ui.components.Overline(if (remainingSec != null) "남은 시간" else "경과 시간")
            com.phonelock.app.ui.components.FitText(
                if (remainingSec != null) formatHms(remainingSec) else formatHms(elapsedSec),
                style = MaterialTheme.typography.displayLarge,
                maxSize = 88.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                com.phonelock.app.ui.components.ProgressLine(progress)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "오늘 합계 ${formatHms(todayLogSeconds)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(40.dp))
            if (isRemote) {
                Text(
                    "다른 기기에서 집중 타이머가 실행 중이라 이 기기도 함께 잠겼습니다. 정지·전환은 그 기기에서 해주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showStopNoteDialog = true }, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text("타이머 정지", maxLines = 1, softWrap = false)
                    }
                    if (isPomodoro) {
                        Button(onClick = onSwitchToBreak, modifier = Modifier.weight(1f).height(56.dp)) {
                            Text("휴식으로 전환", maxLines = 1, softWrap = false)
                        }
                    }
                }
                if (isPomodoro) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "집중 시간을 다 채우기 전엔 전환이 적용되지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showAllowedAppsDialog = true }) {
                    Text("허용된 앱" + if (allowedApps.isNotEmpty()) " ${allowedApps.size}개" else "", maxLines = 1, softWrap = false)
                }
                // 원격 신호로 잠긴 경우엔 이 기기에 제어할 타이머가 없다(정지/전환과 같은 규칙).
                if (!isRemote && run != null) {
                    TextButton(onClick = { showTaskChangeDialog = true }) { Text("일정 변경", maxLines = 1, softWrap = false) }
                }
            }
        }
    }

    if (showAllowedAppsDialog) {
        AlertDialog(
            onDismissRequest = { showAllowedAppsDialog = false },
            title = { Text("허용된 앱") },
            text = {
                if (allowedApps.isEmpty()) {
                    Text(
                        "설정 > 집중 탭에서 집중 잠금 허용 앱을 등록할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // 허용 앱이 많아도 다이얼로그가 화면을 넘지 않게 목록만 스크롤시킨다.
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AllowedAppsFlow(apps = allowedApps, onLaunchApp = onLaunchApp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAllowedAppsDialog = false }) { Text("닫기" ) } }
        )
    }

    // 타이머가 그사이 멈췄으면(정지/다른 기기 신호 종료) 바꿀 대상이 없으므로 그냥 안 띄운다.
    run?.takeIf { showTaskChangeDialog }?.let { currentRun ->
        StudyTaskChangeDialog(
            todayTasks = todayTasks,
            currentTaskName = currentRun.taskName,
            onDismiss = { showTaskChangeDialog = false },
            onConfirm = { newName ->
                onChangeTask(newName)
                run = repository.getTimerRun()
                showTaskChangeDialog = false
            }
        )
    }

    if (showStopNoteDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("집중 종료") },
            text = {
                Column {
                    Text(
                        "짧은 회고를 남기고 싶다면 적어주세요(선택).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = stopNoteText,
                        onValueChange = { stopNoteText = it },
                        placeholder = { Text("예: 3장까지 읽었다, 집중이 잘 됐다") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = stopTagText,
                        onValueChange = { stopTagText = it },
                        label = { Text("태그(분야 등, 선택)") },
                        placeholder = { Text("예: 독서, 업무, 운동") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onStopTimer(stopNoteText.trim(), stopTagText.trim())
                    stopNoteText = ""
                    stopTagText = ""
                    showStopNoteDialog = false
                }) { Text("정지") }
            },
            dismissButton = {
                TextButton(onClick = {
                    stopNoteText = ""
                    stopTagText = ""
                    showStopNoteDialog = false
                }) { Text("취소") }
            }
        )
    }
}

/** 안드로이드는 실제 앱 아이콘(AppIcon.kt)을 쓸 수 있어 데스크탑의 첫 글자 아바타보다 한 단계 더 구체적이다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AllowedAppsFlow(apps: List<AppInfo>, onLaunchApp: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        apps.forEach { app ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.clickable { onLaunchApp(app.packageName) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    AppIcon(packageName = app.packageName, modifier = Modifier.size(28.dp))
                    Text(app.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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
