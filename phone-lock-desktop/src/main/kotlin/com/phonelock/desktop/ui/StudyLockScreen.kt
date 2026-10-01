package com.phonelock.desktop.ui

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.TimerRunState
import com.phonelock.desktop.data.getCalendarTasks
import com.phonelock.desktop.monitor.PomodoroSyncClient
import com.phonelock.desktop.monitor.StudyLockStatus
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudyLockScreen(
    status: StudyLockStatus,
    repository: Repository,
    onLaunchApp: (String) -> Unit,
    onStopTimer: () -> Unit,
    onSwitchToBreak: () -> Unit,
    onChangeTask: (String) -> Unit = { repository.timerChangeTask(it) },
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
    // 126차(사용자 요청): 허용된 프로그램 목록이 화면 아래 절반을 늘 차지해서 위쪽 타이머·정지 버튼이
    // 밀려 있었다 — 목록은 버튼을 눌렀을 때 뜨는 다이얼로그로 옮기고 화면은 타이머만 쓴다.
    var showAllowedAppsDialog by remember { mutableStateOf(false) }
    // 126차(사용자 요청): 타이머를 끄지 않고 공부 일정만 바꾸기.
    var showTaskChangeDialog by remember { mutableStateOf(false) }
    var todayTasks by remember { mutableStateOf(repository.getCalendarTasks(repository.todayCalendarDateKey())) }

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
                // 일정 변경 다이얼로그에서 고를 오늘 일정 목록 — 같은 5초 주기에 얹어 따로 루프를 만들지 않는다.
                todayTasks = repository.getCalendarTasks(repository.todayCalendarDateKey())
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

    // 144차 리디자인(안드로이드판과 같은 언어): 원형 링 대신 폭을 채우는 큰 시간 숫자 + 진행 막대(뽀모도로), 위에 상태 줄과
    // 일정 이름, 버튼은 아래. 표준 모드는 강조색이 옅게 번지는 바탕, 성능 모드는 단색.
    val performance = com.phonelock.desktop.ui.theme.LocalPerformanceMode.current
    val bgModifier = if (performance) Modifier.background(MaterialTheme.colorScheme.background)
    else Modifier.background(
        Brush.radialGradient(
            colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), MaterialTheme.colorScheme.background),
            radius = 900f
        )
    )

    Box(Modifier.fillMaxSize().then(bgModifier), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize()) {
            if (toastMessage != null) {
                com.phonelock.desktop.ui.components.NoticeStrip(toastMessage, modifier = Modifier.padding(Spacing.md))
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl, vertical = Spacing.xl)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.phonelock.desktop.ui.components.LiveDot(MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    com.phonelock.desktop.ui.components.Overline(
                        (if (isPomodoro) "뽀모도로 · 집중 잠금" else "집중 잠금") + if (status.isRemote) " · 다른 기기" else "",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(taskName.ifBlank { "이름 없는 집중" }, style = MaterialTheme.typography.headlineMedium, maxLines = 2)
                Spacer(Modifier.height(Spacing.lg))
                com.phonelock.desktop.ui.components.Overline(if (remainingSec != null) "남은 시간" else "경과 시간")
                com.phonelock.desktop.ui.components.FitText(
                    if (remainingSec != null) formatHms(remainingSec) else formatHms(elapsedSec),
                    style = MaterialTheme.typography.displayLarge,
                    maxSize = 104.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (progress != null) {
                    Spacer(Modifier.height(8.dp))
                    com.phonelock.desktop.ui.components.ProgressLine(progress)
                }
                Spacer(Modifier.height(12.dp))
                Text("오늘 합계 ${formatHms(todayLogSeconds)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.xl))
                if (status.isRemote) {
                    Text(
                        "다른 기기에서 집중 타이머가 실행 중이라 이 기기도 함께 잠겼습니다. 정지·전환은 그 기기에서 해주세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(Modifier.widthIn(max = 520.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedButton(onClick = onStopTimer, modifier = Modifier.weight(1f).height(52.dp)) { Text("타이머 정지", maxLines = 1, softWrap = false) }
                        if (isPomodoro) {
                            Button(onClick = onSwitchToBreak, modifier = Modifier.weight(1f).height(52.dp)) { Text("휴식으로 전환", maxLines = 1, softWrap = false) }
                        }
                    }
                    if (isPomodoro) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text("집중 시간을 다 채우기 전엔 전환이 적용되지 않습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TextButton(onClick = { showAllowedAppsDialog = true }) {
                        Text("허용된 프로그램" + if (status.allowedApps.isNotEmpty()) " ${status.allowedApps.size}개" else "", maxLines = 1, softWrap = false)
                    }
                    // 원격 신호로 잠긴 경우엔 이 기기에 제어할 타이머가 없다(정지/전환과 같은 규칙).
                    if (!status.isRemote && run != null) {
                        TextButton(onClick = { showTaskChangeDialog = true }) { Text("일정 변경", maxLines = 1, softWrap = false) }
                    }
                }
            }
            }
        }
    }

    if (showAllowedAppsDialog) {
        AlertDialog(
            onDismissRequest = { showAllowedAppsDialog = false },
            title = { Text("허용된 프로그램") },
            text = {
                if (status.allowedApps.isEmpty()) {
                    Text(
                        "설정 > 집중 탭에서 허용 프로그램을 등록할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // 허용 프로그램이 많아도 다이얼로그가 화면을 넘지 않게 목록만 스크롤시킨다.
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        AllowedAppsFlow(names = status.allowedApps, onLaunchApp = onLaunchApp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAllowedAppsDialog = false }) { Text("닫기") } }
        )
    }

    // 타이머가 그사이 멈췄으면 바꿀 대상이 없으므로 그냥 안 띄운다.
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
}

/** 아이콘 파이프라인이 없는 데스크탑에선 첫 글자 원형 아바타로 대신한다(안드로이드는 실제 앱 아이콘 사용). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AllowedAppsFlow(names: List<String>, onLaunchApp: (String) -> Unit) {
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
