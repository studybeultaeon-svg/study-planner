package com.phonelock.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phonelock.app.service.AccessibilityServiceChecker
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.*
import com.phonelock.app.data.CalcTask
import com.phonelock.app.data.CalendarTask
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.StudyLogEntry
import com.phonelock.app.data.TimerRunState
import com.phonelock.app.service.PomodoroSyncClient
import com.phonelock.app.ui.components.SectionCard
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

private val GREEN = Color(0xFF34D399)
private val YELLOW = Color(0xFFFBBF24)

/** 다른 기기가 write한 신호가 이보다 오래되면(그 기기가 정지 없이 앱을 꺼서 갱신이 끊긴 경우 등) 화면에 보여주지 않는다. */
private const val REMOTE_STALE_MS = 20 * 60 * 1000L

/**
 * 네이티브 공부 타이머 탭(1단계). 웹앱 index.html "타이머" 탭(`renderTimer()`)을 실제 CSS/JS 소스
 * 기준으로 재현했다(2026-08-07, 색상/스타일 전면 재검토 세션) — 데스크탑판 StudyTimerScreen.kt와
 * 동일한 색 규칙(공부=파랑/휴식=초록, 타이머 숫자는 실행 중이면 항상 파랑, 전환 버튼은 파랑 틴트
 * 아웃라인)을 대칭으로 유지한다.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StudyTimerScreen(repository: PhoneLockRepository) {
    var run by remember { mutableStateOf(repository.getTimerRun()) }
    var todayTasks by remember { mutableStateOf(listOf<CalendarTask>()) }
    var taskName by remember { mutableStateOf(todayTasks.firstOrNull { it.name.isNotBlank() }?.name ?: "") }
    // 93차(사용자 요청): "해당 없음"을 골라 taskName을 일부러 비웠는데, 아래 자동 채움 로직이
    // "비어있으면 첫 일정으로 채운다"는 규칙 때문에 다음 목록 갱신 때 도로 채워버리는 문제가 있었다 —
    // 사용자가 한 번이라도 직접 고르거나 입력했으면 그 뒤로는 자동 채움을 하지 않는다.
    var taskNameTouchedByUser by remember { mutableStateOf(false) }
    var taskDropdownExpanded by remember { mutableStateOf(false) }
    var pomodoroEnabled by remember { mutableStateOf(repository.pomodoroModeEnabled) }
    var studyMinText by remember { mutableStateOf(repository.pomodoroStudyMinutes.toString()) }
    var breakMinText by remember { mutableStateOf(repository.pomodoroBreakMinutes.toString()) }
    var todayLog by remember { mutableStateOf(listOf<StudyLogEntry>()) }
    var calcTasksForSummary by remember { mutableStateOf(listOf<CalcTask>()) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var remoteStudying by remember { mutableStateOf(false) }
    var remoteResting by remember { mutableStateOf(false) }
    var remotePhaseStartedAt by remember { mutableStateOf(0L) }
    var remotePhaseEndAt by remember { mutableStateOf(0L) }
    var remoteTaskName by remember { mutableStateOf("") }
    var remoteMode by remember { mutableStateOf("plain") }
    var tickCount by remember { mutableStateOf(0) }
    var showStopNoteDialog by remember { mutableStateOf(false) }
    var stopNoteText by remember { mutableStateOf("") }
    var stopTagText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 91차(90차 지정 9번): 접근성 서비스는 관리(차단) 그룹뿐 아니라 공부 잠금(checkStudyLock())도
    // 이 서비스로 동작하는데, 지금까지 이 경고는 GroupListScreen.kt에만 있었다 — 관리 그룹을 하나도
    // 안 쓰고 공부 타이머만 쓰는 사용자는 접근성이 꺼져도 알 방법이 없어서 여기도 같은 배너를 추가한다.
    var accessibilityEnabled by remember { mutableStateOf(AccessibilityServiceChecker.isEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = AccessibilityServiceChecker.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val accessibilityBanner: @Composable () -> Unit = {
        if (!accessibilityEnabled) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Column(Modifier.padding(Spacing.md)) {
                    Text(
                        "⚠ 접근성 서비스가 꺼져 있습니다 — 지금 공부 잠금이 동작하지 않습니다",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Button(
                        onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("설정에서 켜기")
                    }
                }
            }
        }
    }

    fun refreshLog() {
        scope.launch { todayLog = repository.getTodayStudyLog() }
    }

    LaunchedEffect(Unit) {
        todayLog = repository.getTodayStudyLog()
        todayTasks = repository.getCalendarTasks(repository.todayCalendarDateKey())
        calcTasksForSummary = repository.getCalcTasks()
        while (true) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
            tickCount++
            run = repository.getTimerRun()
            if (run == null) {
                todayTasks = repository.getCalendarTasks(repository.todayCalendarDateKey())
                // 잠금 화면(StudyLockActivity)에서 정지/전환했을 수도 있으니 매번 다시 읽는다.
                todayLog = repository.getTodayStudyLog()
                // 이 기기 타이머가 꺼져 있을 때만 "다른 기기" 상태를 읽어와 그대로 미러링해서 보여준다
                // (사용자 요청: 다른 기기가 재고 있으면 이 기기도 시작 없이 같은 숫자를 보여줄 것).
                val url = repository.fbDatabaseUrl; val key = repository.fbApiKey
                val updatedAt = PomodoroSyncClient.remoteUpdatedAtMillis(url, key)
                val fresh = updatedAt > 0 && nowMillis - updatedAt < REMOTE_STALE_MS
                remoteStudying = fresh && PomodoroSyncClient.isStudyTimerActive(url, key)
                remoteResting = fresh && PomodoroSyncClient.isBreakActive(url, key)
                remotePhaseStartedAt = PomodoroSyncClient.remotePhaseStartedAt(url, key)
                remotePhaseEndAt = PomodoroSyncClient.currentPhaseEndAt(url, key)
                remoteTaskName = PomodoroSyncClient.remoteTaskName(url, key)
                remoteMode = if (PomodoroSyncClient.isPomodoroMode(url, key)) "pomodoro" else "plain"
            }
            // 5초마다 다른 기기가 올린 "오늘의 공부 기록"을 읽어와 합친다 — 이 기기가 실행 중이어도
            // 다른 기기의 기록은 별도로 계속 갱신돼야 하므로 run 상태와 무관하게 돈다.
            if (tickCount % 5 == 0) {
                repository.syncStudyLogFromFirebase(repository.todayCalendarDateKey())
                // 93차(사용자 요청): 다른 기기에서 오늘 캘린더 일정을 새로 추가해도 이 탭은
                // CalendarScreen/StudyStatsScreen과 달리 진입 시 동기화를 한 번도 안 해서 로컬 데이터가
                // 오래된 채로 남아있었다 — 새 일정이 드롭다운에 안 보이던 원인. 여기서도 동기화한다.
                repository.syncCalendarFromFirebase()
                todayLog = repository.getTodayStudyLog()
                calcTasksForSummary = repository.getCalcTasks()
                if (run == null) todayTasks = repository.getCalendarTasks(repository.todayCalendarDateKey())
            }
        }
    }

    // 1초마다 todayTasks를 새로 불러오는데, 그때마다 taskName을 무조건 첫 항목으로 되돌리면
    // 사용자가 고른 값이 계속 리셋된다. 현재 선택값이 여전히 목록에 유효할 때만 유지한다.
    LaunchedEffect(todayTasks) {
        if (!taskNameTouchedByUser && (taskName.isBlank() || todayTasks.none { it.name == taskName })) {
            taskName = todayTasks.firstOrNull { it.name.isNotBlank() }?.name ?: ""
        }
    }

    if (showStopNoteDialog) {
        AlertDialog(
            onDismissRequest = { showStopNoteDialog = false },
            title = { Text("공부 종료") },
            text = {
                Column {
                    Text(
                        "짧은 회고를 남기고 싶다면 적어주세요(선택).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = stopNoteText,
                        onValueChange = { stopNoteText = it },
                        placeholder = { Text("예: 3장까지 풀었다, 집중이 잘 됐다") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = stopTagText,
                        onValueChange = { stopTagText = it },
                        label = { Text("태그(과목 등, 선택)") },
                        placeholder = { Text("예: 수학, 영어") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val recentTags = todayLog.map { it.tag }.filter { it.isNotBlank() }.distinct()
                    if (recentTags.isNotEmpty()) {
                        Spacer(Modifier.height(Spacing.xs))
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            recentTags.forEach { t ->
                                androidx.compose.material3.AssistChip(onClick = { stopTagText = t }, label = { Text(t) })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    repository.timerStop(stopNoteText.trim(), stopTagText.trim())
                    run = repository.getTimerRun()
                    refreshLog()
                    stopNoteText = ""
                    stopTagText = ""
                    showStopNoteDialog = false
                }) { Text("정지") }
            },
            dismissButton = {
                TextButton(onClick = { showStopNoteDialog = false }) { Text("취소") }
            }
        )
    }

    // 태블릿은 데스크탑 StudyTimerScreen.kt와 같은 좌(타이머 본체)/우(허용 앱·사이트+오늘 기록) 분할이라
    // 두 영역을 각각 재사용 가능한 람다로 뽑아 phone/tablet 두 분기에서 그대로 호출한다(83차 이후 패턴).
    val timerCardContent: @Composable () -> Unit = {
        // 이 기기 타이머가 꺼져 있어도 다른 기기가 재고 있으면(신선한 신호일 때만) 그 값을 그대로
        // 미러링해서 보여준다 — 사용자 요청: 데스크탑에서 시작하면 모바일도 시작 없이 같은 숫자를 보여줄 것.
        val remoteActive = remoteStudying || remoteResting
        val mirrorFromRemote = run == null && remoteActive
        SectionCard("⏱️ 공부 타이머") {
            if (run == null && !mirrorFromRemote) {
                // 92차(사용자 요청): 91차에 "일정 없으면 자유 입력"으로 바꿨더니 일정이 있을 때도
                // 드롭다운 선택 기능이 없어진 것처럼 보인다는 피드백 — 실제로는 남아있었지만,
                // 아예 항상 "골라도 되고 직접 입력해도 되는" 입력칸으로 통합해 헷갈릴 여지를 없앤다.
                // 이제 readOnly를 걸지 않아 일정이 있어도 자유롭게 고쳐 쓸 수 있고, 일정이 있으면
                // 드롭다운 아이콘으로 목록에서 고를 수도 있다.
                ExposedDropdownMenuBox(
                    expanded = taskDropdownExpanded,
                    onExpandedChange = { taskDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = taskName,
                        onValueChange = { taskName = it; taskNameTouchedByUser = true },
                        readOnly = false,
                        label = { Text("오늘 캘린더 일정") },
                        placeholder = { Text("예: 수학 (선택, 비워둬도 됩니다)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = taskDropdownExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = taskDropdownExpanded,
                        onDismissRequest = { taskDropdownExpanded = false }
                    ) {
                        // 93차(사용자 요청): 빈칸으로 지우는 방법을 모르는 사용자를 위해 목록에서도
                        // 명시적으로 고를 수 있는 "해당 없음" 항목을 항상 맨 위에 둔다.
                        DropdownMenuItem(
                            text = { Text("해당 없음") },
                            onClick = { taskName = ""; taskNameTouchedByUser = true; taskDropdownExpanded = false }
                        )
                        todayTasks.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(taskDropdownLabel(t)) },
                                onClick = { taskName = t.name; taskNameTouchedByUser = true; taskDropdownExpanded = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🍅 뽀모도로 모드",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    PomoToggleButton(
                        checked = pomodoroEnabled,
                        onClick = {
                            pomodoroEnabled = !pomodoroEnabled
                            repository.pomodoroModeEnabled = pomodoroEnabled
                        }
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                if (pomodoroEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedTextField(
                            value = studyMinText,
                            onValueChange = { text ->
                                studyMinText = text
                                text.toIntOrNull()?.let { if (it > 0) repository.pomodoroStudyMinutes = it }
                            },
                            label = { Text("공부(분)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = breakMinText,
                            onValueChange = { text ->
                                breakMinText = text
                                text.toIntOrNull()?.let { if (it > 0) repository.pomodoroBreakMinutes = it }
                            },
                            label = { Text("휴식(분)") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
                Button(
                    onClick = {
                        repository.timerStart(taskName, pomodoroEnabled)
                        run = repository.getTimerRun()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Filled.PlayArrow, contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    // 아이콘과 글자가 붙어 있어 "▶시작"처럼 한 덩어리로 보였다.
                    Spacer(Modifier.width(Spacing.xs))
                    Text("시작")
                }
            } else {
                val isMirror = run == null
                val current = run ?: TimerRunState(
                    taskName = remoteTaskName,
                    mode = remoteMode,
                    phase = if (remoteResting) "break" else "study",
                    phaseStartedAt = remotePhaseStartedAt,
                    phaseEndAt = remotePhaseEndAt,
                    cycleCount = 0,
                    breakExtraUsed = false
                )
                val isBreak = current.phase == "break"
                PomoPhaseBadge(isBreak = isBreak)
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    current.taskName.ifBlank { "이름 없는 공부" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Spacing.sm))

                val displaySec = if (current.mode == "pomodoro") {
                    ((current.phaseEndAt - nowMillis) / 1000L).coerceAtLeast(0L)
                } else {
                    ((nowMillis - current.phaseStartedAt) / 1000L).coerceAtLeast(0L)
                }
                val timedUp = current.mode == "pomodoro" && displaySec <= 0L
                if (timedUp) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = YELLOW.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, YELLOW.copy(alpha = 0.35f))
                    ) {
                        Text(
                            "⏰ 시간이 다 됐어요 — " + if (isBreak) {
                                if (current.breakExtraUsed) "준비되면 아래에서 공부 모드로 전환하세요" else "아래에서 5분만 더 쉬거나 공부 모드로 전환하세요"
                            } else "아래 전환 버튼을 눌러주세요",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = YELLOW,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
                Text(
                    formatHmsLog(displaySec),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.md))
                if (isMirror) {
                    // 다른 기기가 시작한 세션을 미러링하는 중 — 이 기기에서 시작하지 않았으므로
                    // 정지/전환은 그 기기에서만 가능하다(19차 세션에서 겪은 remoteCommand 왕복 문제를
                    // 재현하지 않도록 여기선 표시만 하고 제어는 하지 않는다 — DECISIONS.md 참고).
                    Text(
                        "📡 다른 기기에서 실행 중입니다 — 정지·전환은 그 기기에서 해주세요.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showStopNoteDialog = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("■ 정지") }
                        if (current.mode == "pomodoro") {
                            val canSwitch = current.phase == "break" || nowMillis >= current.phaseEndAt
                            if (canSwitch) {
                                OutlinedButton(
                                    onClick = {
                                        repository.timerSwitchPhase()
                                        run = repository.getTimerRun()
                                        refreshLog()
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                    modifier = Modifier.weight(1f)
                                ) { Text("🔁 ${if (current.phase == "study") "휴식으로 전환" else "공부로 전환"}") }
                            }
                            if (current.phase == "break" && nowMillis >= current.phaseEndAt && !current.breakExtraUsed) {
                                OutlinedButton(
                                    onClick = {
                                        repository.timerExtendBreak()
                                        run = repository.getTimerRun()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("⏰ 5분만 더") }
                            }
                        }
                    }
                }
            }
        }
    }
    // 90차(사용자 요청): 허용 앱/사이트 편집은 설정 > 공부 탭으로 옮겼다 — 매번 보는 화면이 아니라
    // 한 번 정해두는 설정이기 때문(데스크탑판과 동일한 이동). 여기엔 오늘 기록만 남는다.
    val extrasContent: @Composable () -> Unit = {
        SectionCard("📊 오늘의 공부 기록") {
            if (todayLog.isEmpty()) {
                Text("아직 오늘 기록된 공부 시간이 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val byTask = todayLog.groupBy { it.taskName }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    byTask.entries.sortedByDescending { (_, entries) -> entries.sumOf { it.seconds } }.forEach { (name, entries) ->
                        val lastEntry = entries.maxByOrNull { it.startedAt }
                        StudyLogRow(name = name.ifBlank { "이름 없는 공부" }, seconds = entries.sumOf { it.seconds }.toLong(), note = lastEntry?.note.orEmpty(), tag = lastEntry?.tag.orEmpty())
                    }
                    StudyLogRow(name = "합계", seconds = todayLog.sumOf { it.seconds }.toLong(), isTotal = true)
                }
            }
        }
    }

    if (com.phonelock.app.ui.components.isTabletWidth()) {
        // 태블릿은 데스크탑 StudyTimerScreen.kt와 같은 좌(타이머 본체)/우(허용 앱·사이트+오늘 기록)
        // 분할 — 데스크탑도 넓은 화면에서 세로로 다 쌓지 않고 역할별로 좌우로 나눠 쓴다.
        Column(Modifier.fillMaxSize().padding(Spacing.md)) {
            Text("⏱️ 시간 측정", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.md))
            accessibilityBanner()
            TodaySummaryCard(todayTasks = todayTasks, calcTasks = calcTasksForSummary, todayLogSeconds = todayLog.sumOf { it.seconds }.toLong())
            Spacer(Modifier.height(Spacing.md))
            com.phonelock.app.ui.components.ResponsiveSplit(
                modifier = Modifier.weight(1f),
                left = { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { timerCardContent() } },
                right = { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { extrasContent() } }
            )
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.md)
        ) {
            Text("⏱️ 시간 측정", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.md))
            accessibilityBanner()

            TodaySummaryCard(todayTasks = todayTasks, calcTasks = calcTasksForSummary, todayLogSeconds = todayLog.sumOf { it.seconds }.toLong())
            Spacer(Modifier.height(Spacing.md))

            timerCardContent()
            Spacer(Modifier.height(Spacing.md))

            extrasContent()
        }
    }
}

/**
 * "오늘 한눈에" 요약 카드 — 새 데이터/API 없이 이미 화면에 있는 캘린더/계산기/공부기록 3개 소스를
 * 상단에 나란히 보여주기만 한다(전문가 종합분석 보고서 #11, 순수 UI 집계, 판정 로직과 무관, 데스크탑판과 대칭).
 */
@Composable
private fun TodaySummaryCard(todayTasks: List<CalendarTask>, calcTasks: List<CalcTask>, todayLogSeconds: Long) {
    val doneCount = todayTasks.count { it.status == "O" }
    val totalCount = todayTasks.size
    val todayCalcTargetTotal = calcTasks.sumOf { parseTodayCalcTarget(it) }
    SectionCard("📌 오늘 한눈에") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TodaySummaryStat("캘린더 일정", "${totalCount}개(완료 $doneCount)")
            TodaySummaryStat("일정표 오늘 목표", if (todayCalcTargetTotal > 0) fmtCalcSummaryNumber(todayCalcTargetTotal) else "-")
            TodaySummaryStat("오늘 누적 공부시간", formatHmsLog(todayLogSeconds))
        }
    }
}

@Composable
private fun TodaySummaryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

/** 오늘 요일에 해당하는 계산기 업무의 목표량(mon~sun 중 하나)을 숫자로 파싱, 비어있거나 잘못된 값은 0. */
private fun parseTodayCalcTarget(task: CalcTask): Double {
    val jsDow = LocalDate.now().dayOfWeek.value % 7 // java DayOfWeek: 월=1..일=7 -> js식 일=0..토=6로 변환
    val raw = when (jsDow) {
        0 -> task.sun; 1 -> task.mon; 2 -> task.tue; 3 -> task.wed
        4 -> task.thu; 5 -> task.fri; else -> task.sat
    }
    return raw.trim().toDoubleOrNull() ?: 0.0
}

private fun fmtCalcSummaryNumber(n: Double): String =
    if (n == n.toLong().toDouble()) n.toLong().toString() else "%.1f".format(n)

@Composable
private fun PomoPhaseBadge(isBreak: Boolean) {
    val color = if (isBreak) GREEN else MaterialTheme.colorScheme.primary
    // 웹앱 .pomo-phase-badge .dot { animation: pulse 1s infinite } — 0%,100%=1, 50%=.3
    val transition = rememberInfiniteTransition(label = "pomoPulse")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(animation = tween(500, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "pomoPulseAlpha"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Box(Modifier.size(6.dp).background(color.copy(alpha = dotAlpha), CircleShape))
        Text(if (isBreak) "휴식 중" else "공부 중", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
    }
}

/** 웹앱 .pomo-toggle-btn — off는 회색 카드, on은 초록 틴트. */
@Composable
private fun PomoToggleButton(checked: Boolean, onClick: () -> Unit) {
    val bg = if (checked) GREEN.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface
    val fg = if (checked) GREEN else MaterialTheme.colorScheme.onSurfaceVariant
    val border = if (checked) GREEN.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            if (checked) "ON" else "OFF",
            color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

/** 웹앱 .study-log-row — 카드형 행, 합계 행은 파랑 틴트로 강조. note가 있으면 이름 아래 회고를 작게 덧붙인다. */
@Composable
internal fun StudyLogRow(name: String, seconds: Long, isTotal: Boolean = false, note: String = "", tag: String = "") {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (isTotal) accent.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (isTotal) accent else MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (tag.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(50), color = accent.copy(alpha = 0.12f)) {
                            Text(tag, style = MaterialTheme.typography.labelSmall, color = accent, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                }
                Text(formatHmsLog(seconds), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = accent)
            }
            if (note.isNotBlank()) {
                Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * 웹앱의 "입력창 + 추가 버튼 + 목록(항목마다 ✕ 삭제)" 패턴. 90차부터 실제 사용처는
 * 설정 > 공부 탭의 "공부 잠금 허용 사이트" 하나뿐이지만, 컴포넌트는 원래 자리에 그대로 둔다.
 */
@Composable
internal fun LockListEditor(items: List<String>, placeholder: String, onAdd: (String) -> Unit, onRemove: (Int) -> Unit) {
    var input by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text(placeholder) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        Button(
            onClick = { if (input.isNotBlank()) { onAdd(input.trim()); input = "" } },
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp)
        ) { Text("+") }
    }
    Spacer(Modifier.height(Spacing.sm))
    if (items.isEmpty()) {
        Text("등록된 항목이 없습니다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEachIndexed { idx, name ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { onRemove(idx) }, contentPadding = PaddingValues(4.dp)) {
                            Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun taskDropdownLabel(task: CalendarTask): String {
    val done = if (task.status == "O") " ✅" else ""
    return "${task.name}$done · ${task.passIndex + 1}회독"
}

internal fun formatHmsLog(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d".format(h, m, s)
}
