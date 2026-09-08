package com.phonelock.app.ui

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
import com.phonelock.app.service.IntentExtras
import com.phonelock.app.service.PomodoroSyncClient
import com.phonelock.app.ui.theme.PhoneLockTheme
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

        setContent {
            val prefs = AppPreferences(applicationContext)
            PhoneLockTheme(prefs.themeMode, prefs.customThemeBackground, prefs.customThemeAccent, prefs.fontScale) {
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
    repository: PhoneLockRepository,
    onLaunchApp: (String) -> Unit,
    onStopTimer: (String, String) -> Unit,
    onSwitchToBreak: () -> Unit,
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

    val bg = Brush.radialGradient(
        colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), MaterialTheme.colorScheme.background),
        radius = 1400f
    )

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 96차 버그 수정: 태블릿(특히 가로 모드처럼 세로 폭이 좁은 화면)에서 이 위쪽 Column이
            // 배지+타이머 원형+태스크 칩+"타이머 정지" 버튼까지 다 그리기엔 세로 공간이 부족한데,
            // 예전엔 스크롤이 없어 넘치는 만큼 그냥 화면 밖으로 잘려 정지 버튼이 안 보였다(사용자 지적).
            // verticalScroll을 추가해 안 잘리고 스크롤해서라도 항상 버튼에 닿을 수 있게 한다.
            Column(
                modifier = Modifier.weight(1.1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                LockPhaseBadge(isPomodoro = isPomodoro, isRemote = isRemote)
                Spacer(Modifier.height(20.dp))
                LockRing(
                    progress = progress,
                    secondHandAngle = secondHandAngle,
                    bigText = if (remainingSec != null) formatHms(remainingSec) else formatHms(elapsedSec),
                    smallLabel = if (remainingSec != null) "남은 시간" else "경과 시간"
                )
                if (taskName.isNotBlank()) {
                    Spacer(Modifier.height(20.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text(
                            "📖 $taskName",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "오늘 누적 공부시간 · ${formatHms(todayLogSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                if (isRemote) {
                    Text(
                        "📡 다른 기기에서 공부 타이머가 실행 중이라 이 기기도 함께 잠겼습니다. 정지·전환은 그 기기에서 해주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showStopNoteDialog = true }) { Text("⏹ 타이머 정지") }
                        if (isPomodoro) {
                            Spacer(Modifier.width(4.dp))
                            Button(
                                onClick = onSwitchToBreak,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) { Text("☕ 휴식으로 전환") }
                        }
                    }
                    if (isPomodoro) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "공부 시간을 다 채우기 전엔 전환이 적용되지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    "허용된 앱",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                if (allowedApps.isEmpty()) {
                    Text(
                        "설정 탭에서 공부 잠금 허용 앱을 등록할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    AllowedAppsFlow(apps = allowedApps, onLaunchApp = onLaunchApp)
                }
            }
        }
    }

    if (showStopNoteDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("공부 종료") },
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
                        placeholder = { Text("예: 3장까지 풀었다, 집중이 잘 됐다") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = stopTagText,
                        onValueChange = { stopTagText = it },
                        label = { Text("태그(과목 등, 선택)") },
                        placeholder = { Text("예: 수학, 영어") },
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

@Composable
private fun LockPhaseBadge(isPomodoro: Boolean, isRemote: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        ) {
            Text(
                if (isPomodoro) "🍅 뽀모도로 · 공부 중" else "🔒 공부 중",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        if (isRemote) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    "📡 원격",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** 데스크탑 StudyLockScreen의 LockRing과 대칭 — 큰 원형 진행률/시계 바늘 안에 시간을 직접 표시. */
@Composable
private fun LockRing(progress: Float?, secondHandAngle: Float?, bigText: String, smallLabel: String) {
    val trackColor = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 14.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
            drawArc(
                color = trackColor.copy(alpha = 0.4f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = strokeWidth)
            )
            if (progress != null) {
                drawArc(
                    color = accent,
                    startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            } else {
                drawArc(
                    color = accent.copy(alpha = 0.5f),
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeWidth)
                )
                if (secondHandAngle != null) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val rad = Math.toRadians(secondHandAngle - 90.0)
                    val length = diameter * 0.42f
                    drawLine(
                        color = accent,
                        start = center,
                        end = Offset(center.x + (length * Math.cos(rad)).toFloat(), center.y + (length * Math.sin(rad)).toFloat()),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                bigText,
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(smallLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 안드로이드는 실제 앱 아이콘(AppIcon.kt)을 쓸 수 있어 데스크탑의 첫 글자 아바타보다 한 단계 더 구체적이다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllowedAppsFlow(apps: List<AppInfo>, onLaunchApp: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        apps.forEach { app ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
