package com.phonelock.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.TimerRunState
import com.phonelock.desktop.monitor.PomodoroSyncClient
import com.phonelock.desktop.monitor.StudyLockStatus
import com.phonelock.desktop.ui.components.MediaControlCard
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 공부앱 타이머가 켜져 있는 동안 데스크탑 전체화면을 덮는 잠금 화면. 92차 세션(사용자 요청)에
 * "디자인이 밋밋하다/정보가 부족하다/허용 앱 UX가 별로다"는 지적을 받고, `StudyTimerScreen`의
 * 디자인 언어(PomoPhaseBadge/TimerIllustration류 큰 원형 진행률, primary 강조색, 카드형 목록)를
 * 그대로 가져와 전면 재구성했다. 판정 로직(잠금 유지 여부)은 그대로, 오직 표시만 바꾼다.
 */
@Composable
fun StudyLockScreen(
    status: StudyLockStatus,
    repository: Repository,
    onLaunchApp: (String) -> Unit,
    onStopTimer: () -> Unit,
    onSwitchToBreak: () -> Unit,
    toastMessage: String? = null,
    onToastShown: () -> Unit = {}
) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var run by remember { mutableStateOf(repository.getTimerRun()) }
    var todayLogSeconds by remember { mutableStateOf(repository.getTodayStudyLog().sumOf { it.seconds }.toLong()) }
    var remoteTaskName by remember { mutableStateOf("") }
    var remotePhaseStartedAt by remember { mutableStateOf(0L) }
    var remotePhaseEndAt by remember { mutableStateOf(0L) }
    var remoteMode by remember { mutableStateOf(if (status.isPomodoroMode) "pomodoro" else "plain") }
    var tickCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
            tickCount++
            run = repository.getTimerRun()
            // 원격 잠금(다른 기기가 재는 중)일 땐 로컬에 TimerRunState가 없으므로, 큰 원형 표시에 쓸
            // phaseEndAt/taskName을 StudyTimerScreen과 같은 방식으로 원격에서 읽어온다(정지/전환은
            // 여전히 안 함 — 표시만 목적, DECISIONS.md "타이머 크로스디바이스 미러" 참고).
            if (status.isRemote && run == null) {
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
        }
    }

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(4000)
            onToastShown()
        }
    }

    val current = run ?: if (status.isRemote) TimerRunState(
        taskName = remoteTaskName,
        mode = remoteMode,
        phase = "study",
        phaseStartedAt = if (remotePhaseStartedAt > 0) remotePhaseStartedAt else status.studyStartedAtMillis,
        phaseEndAt = remotePhaseEndAt,
        cycleCount = 0,
        breakExtraUsed = false
    ) else null
    val isPomodoro = (current?.mode ?: if (status.isPomodoroMode) "pomodoro" else "plain") == "pomodoro"
    val phaseStartedAt = current?.phaseStartedAt ?: status.studyStartedAtMillis
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
        radius = 900f
    )

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize()) {
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
                modifier = Modifier.weight(1.1f).fillMaxWidth().padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                LockPhaseBadge(isPomodoro = isPomodoro, isRemote = status.isRemote)
                Spacer(Modifier.height(Spacing.lg))
                LockRing(
                    progress = progress,
                    secondHandAngle = secondHandAngle,
                    bigText = if (remainingSec != null) formatHms(remainingSec) else formatHms(elapsedSec),
                    smallLabel = if (remainingSec != null) "남은 시간" else "경과 시간"
                )
                if (taskName.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.lg))
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
                Spacer(Modifier.height(Spacing.md))
                Text(
                    "오늘 누적 공부시간 · ${formatHms(todayLogSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.lg))
                if (status.isRemote) {
                    Text(
                        "📡 다른 기기에서 공부 타이머가 실행 중이라 이 기기도 함께 잠겼습니다. 정지·전환은 그 기기에서 해주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedButton(onClick = onStopTimer) { Text("■ 타이머 정지") }
                        if (isPomodoro) {
                            Spacer(Modifier.width(Spacing.xs))
                            Button(
                                onClick = onSwitchToBreak,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) { Text("☕ 휴식으로 전환") }
                        }
                    }
                    if (isPomodoro) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "공부 시간을 다 채우기 전엔 전환이 적용되지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                // 125차(사용자 요청): 허용 프로그램이 아니라서 열 수 없는 음악 앱(Spotify 등)이 백그라운드에서
                // 재생 중이면 여기서 바로 제어한다.
                MediaControlCard(
                    targetProcess = null,
                    excludedProcesses = status.allowedApps,
                    modifier = Modifier.padding(bottom = Spacing.md)
                )
                Text(
                    "허용된 프로그램",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(Spacing.sm))
                if (status.allowedApps.isEmpty()) {
                    Text(
                        "공부앱 타이머 탭에서 허용 프로그램을 등록할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    AllowedAppsFlow(names = status.allowedApps, onLaunchApp = onLaunchApp)
                }
            }
        }
    }
}

@Composable
private fun LockPhaseBadge(isPomodoro: Boolean, isRemote: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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

/** StudyTimerScreen의 TimerIllustration을 잠금 화면 크기(더 크고, 중앙에 시간을 직접 표시)로 확장. */
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

/** 아이콘 파이프라인이 없는 데스크탑에선 첫 글자 원형 아바타로 대신한다(안드로이드는 실제 앱 아이콘 사용). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllowedAppsFlow(names: List<String>, onLaunchApp: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        names.forEach { name ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.clickable { onLaunchApp(name) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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
