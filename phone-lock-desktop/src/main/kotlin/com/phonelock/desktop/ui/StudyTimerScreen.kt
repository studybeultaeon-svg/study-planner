package com.phonelock.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.desktop.data.CalcTask
import com.phonelock.desktop.data.CalendarTask
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.TimerRunState
import com.phonelock.desktop.monitor.PomodoroSyncClient
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 다른 기기가 write한 신호가 이보다 오래되면(그 기기가 정지 없이 앱을 꺼서 갱신이 끊긴 경우 등) 화면에 보여주지 않는다. */
private const val REMOTE_STALE_MS = 20 * 60 * 1000L

private val GREEN = Color(0xFF34D399)
private val YELLOW = Color(0xFFFBBF24)

/**
 * 네이티브 공부 타이머 탭(1단계). 웹앱 index.html "타이머" 탭(`renderTimer()`)을 실제 CSS/JS 소스
 * 기준으로 재현했다(2026-08-07, 색상/스타일 전면 재검토 세션) — 공부→휴식은 시간을 다 채워야 전환,
 * 휴식→공부는 언제든, 휴식 종료 시 "5분만 더" 1회. 항상 wall-clock 기준(phaseStartedAt/phaseEndAt)으로
 * 계산하고 1초 tick은 화면 갱신용일 뿐이다.
 *
 * 색 사용 규칙(`.pomo-phase-badge`/`.timer-display.running`/`.pomo-toggle-btn.on` 등 실제 CSS 확인):
 * 공부=파랑(accent), 휴식=초록(green) — 보라 아님. 타이머 숫자는 phase와 무관하게 실행 중이면 항상
 * 파랑. 전환 버튼은 채워진 버튼이 아니라 파랑 틴트 아웃라인(`.calc-btn-switch`).
 *
 * 데스크탑판(28차 세션)은 왼쪽에 타이머 본체, 오른쪽에 부가 설정(허용 프로그램/사이트)과 오늘 기록을
 * 두는 좌우 분할 — 모바일처럼 세로로 전부 쌓지 않고 넓은 화면을 역할별로 나눠 쓴다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyTimerScreen(repository: Repository) {
    var run by remember { mutableStateOf(repository.getTimerRun()) }
    var todayTasks by remember { mutableStateOf(repository.getCalendarTasks(repository.todayCalendarDateKey())) }
    var taskName by remember { mutableStateOf(todayTasks.firstOrNull { it.name.isNotBlank() }?.name ?: "") }
    var taskDropdownExpanded by remember { mutableStateOf(false) }

    // 1초마다 todayTasks를 새로 불러오는데, 그때마다 taskName을 무조건 첫 항목으로 되돌리면
    // 사용자가 고른 값이 계속 리셋된다. 현재 선택값이 여전히 목록에 유효할 때만 유지한다.
    LaunchedEffect(todayTasks) {
        if (taskName.isBlank() || todayTasks.none { it.name == taskName }) {
            taskName = todayTasks.firstOrNull { it.name.isNotBlank() }?.name ?: ""
        }
    }
    var pomodoroEnabled by remember { mutableStateOf(repository.pomodoroModeEnabled) }
    var studyMinText by remember { mutableStateOf(repository.pomodoroStudyMinutes.toString()) }
    var breakMinText by remember { mutableStateOf(repository.pomodoroBreakMinutes.toString()) }
    var allowedApps by remember { mutableStateOf(repository.studyLockAllowedApps) }
    var allowedSites by remember { mutableStateOf(repository.studyLockAllowedSites) }
    var todayLog by remember { mutableStateOf(repository.getTodayStudyLog()) }
    var calcTasksForSummary by remember { mutableStateOf(repository.getCalcTasks()) }
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

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
            tickCount++
            run = repository.getTimerRun()
            if (run == null) {
                todayTasks = repository.getCalendarTasks(repository.todayCalendarDateKey())
                // 잠금 화면(StudyLockScreen)에서 정지/전환했을 수도 있으니 매번 다시 읽는다 —
                // 이 탭 자체 버튼(refreshLog())만 믿으면 그 경로로 쌓인 기록이 안 보였다.
                todayLog = repository.getTodayStudyLog()
                // 이 기기 타이머가 꺼져 있을 때만 "다른 기기" 상태를 읽어와 그대로 미러링해서 보여준다
                // (사용자 요청: 다른 기기가 재고 있으면 이 기기도 시작 없이 같은 숫자를 보여줄 것).
                withContext(Dispatchers.IO) {
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
            }
            // 5초마다 다른 기기가 올린 "오늘의 공부 기록"을 읽어와 합친다 — 이 기기가 실행 중이어도
            // 다른 기기의 기록은 별도로 계속 갱신돼야 하므로 run 상태와 무관하게 돈다.
            if (tickCount % 5 == 0) {
                withContext(Dispatchers.IO) { repository.syncStudyLogFromFirebase(repository.todayCalendarDateKey()) }
                todayLog = repository.getTodayStudyLog()
                calcTasksForSummary = repository.getCalcTasks()
            }
        }
    }

    fun refreshLog() {
        todayLog = repository.getTodayStudyLog()
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
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    repository.timerStop(stopNoteText.trim())
                    run = repository.getTimerRun()
                    refreshLog()
                    stopNoteText = ""
                    showStopNoteDialog = false
                }) { Text("정지") }
            },
            dismissButton = {
                TextButton(onClick = { showStopNoteDialog = false }) { Text("취소") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(Spacing.md)) {
        Text("⏱️ 시간 측정", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.md))

        TodaySummaryCard(todayTasks = todayTasks, calcTasks = calcTasksForSummary, todayLogSeconds = todayLog.sumOf { it.seconds }.toLong())
        Spacer(Modifier.height(Spacing.md))

        // 79차: 창이 좁아지면 위아래로 쌓는 ResponsiveSplit(사용자 요청, CalendarScreen/CalculatorScreen과 동일 패턴).
        com.phonelock.desktop.ui.components.ResponsiveSplit(modifier = Modifier.weight(1f), left = {
            // 왼쪽: 타이머 본체
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                // 이 기기 타이머가 꺼져 있어도 다른 기기가 재고 있으면(신선한 신호일 때만) 그 값을 그대로
                // 미러링해서 보여준다 — 사용자 요청: 데스크탑에서 시작하면 모바일도 시작 없이 같은 숫자를 보여줄 것.
                val remoteActive = remoteStudying || remoteResting
                val mirrorFromRemote = run == null && remoteActive
                SectionCard("⏱️ 공부 타이머") {
                    if (run == null && todayTasks.isEmpty() && !mirrorFromRemote) {
                        Text(
                            "캘린더에 오늘 일정을 추가하면 타이머를 사용할 수 있습니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (run == null && !mirrorFromRemote) {
                        // ExposedDropdownMenuBox 사용 — 예전엔 OutlinedTextField(readOnly)에
                        // Modifier.clickable을 얹었는데, readOnly TextField가 자체 포인터 입력을
                        // 먼저 가로채는 경우가 있어 항목을 눌러도 선택이 안 바뀌는 버그가 있었다.
                        ExposedDropdownMenuBox(
                            expanded = taskDropdownExpanded,
                            onExpandedChange = { taskDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = taskName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("오늘 캘린더 일정") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = taskDropdownExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = taskDropdownExpanded,
                                onDismissRequest = { taskDropdownExpanded = false }
                            ) {
                                todayTasks.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(taskDropdownLabel(t)) },
                                        onClick = { taskName = t.name; taskDropdownExpanded = false }
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
                        ) { Text("▶ 시작") }
                    } else {
                        val isMirror = run == null
                        val current = run ?: TimerRunState(
                            taskName = remoteTaskName,
                            mode = remoteMode,
                            phase = if (remoteResting) "break" else "study",
                            phaseStartedAt = remotePhaseStartedAt,
                            phaseEndAt = remotePhaseEndAt
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
        }, right = {
            // 오른쪽: 부가 설정 + 오늘 기록
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SectionCard("🔒 공부 중 허용 프로그램") {
                    Text(
                        "공부 페이즈가 진행 중일 때만(휴식 중엔 아님) 데스크탑이 잠기고, 여기 등록한 프로그램만 열 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    LockListEditor(
                        items = allowedApps,
                        placeholder = "예: chrome.exe",
                        onAdd = { name -> allowedApps = allowedApps + name; repository.studyLockAllowedApps = allowedApps },
                        onRemove = { idx -> allowedApps = allowedApps.toMutableList().apply { removeAt(idx) }; repository.studyLockAllowedApps = allowedApps }
                    )
                }
                Spacer(Modifier.height(Spacing.md))

                SectionCard("🌐 공부 중 허용 사이트") {
                    Text(
                        "데스크탑·안드로이드가 공유하는 목록입니다. 공부 페이즈 중엔 브라우저를 열어도 여기 등록한 사이트만 접속할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    LockListEditor(
                        items = allowedSites,
                        placeholder = "예: google.com",
                        onAdd = { name -> allowedSites = allowedSites + name; repository.studyLockAllowedSites = allowedSites },
                        onRemove = { idx -> allowedSites = allowedSites.toMutableList().apply { removeAt(idx) }; repository.studyLockAllowedSites = allowedSites }
                    )
                }
                Spacer(Modifier.height(Spacing.md))

                SectionCard("📊 오늘의 공부 기록") {
                    if (todayLog.isEmpty()) {
                        Text("아직 오늘 기록된 공부 시간이 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val byTask = todayLog.groupBy { it.taskName }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            byTask.entries.sortedByDescending { (_, entries) -> entries.sumOf { it.seconds } }.forEach { (name, entries) ->
                                val lastNote = entries.maxByOrNull { it.startedAt }?.note.orEmpty()
                                StudyLogRow(name = name, seconds = entries.sumOf { it.seconds }.toLong(), note = lastNote)
                            }
                            StudyLogRow(name = "합계", seconds = todayLog.sumOf { it.seconds }.toLong(), isTotal = true)
                        }
                    }
                }
            }
        })
    }
}

/**
 * "오늘 한눈에" 요약 카드 — 새 데이터/API 없이 이미 화면에 있는 캘린더/계산기/공부기록 3개 소스를
 * 상단에 나란히 보여주기만 한다(전문가 종합분석 보고서 #11, 순수 UI 집계, 판정 로직과 무관).
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
internal fun StudyLogRow(name: String, seconds: Long, isTotal: Boolean = false, note: String = "") {
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
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(formatHmsLog(seconds), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = accent)
            }
            if (note.isNotBlank()) {
                Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 웹앱의 "입력창 + 추가 버튼 + 목록(항목마다 ✕ 삭제)" 패턴 — 허용 프로그램/사이트 둘 다 같은 UI. */
@Composable
private fun LockListEditor(items: List<String>, placeholder: String, onAdd: (String) -> Unit, onRemove: (Int) -> Unit) {
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

private val TIMER_COLOR_LABEL = mapOf("red" to "1회독", "yellow" to "2회독", "green" to "3회독")

private fun taskDropdownLabel(task: CalendarTask): String {
    val done = if (task.status == "O") " ✅" else ""
    val colorLabel = TIMER_COLOR_LABEL[task.color] ?: ""
    return "${task.name}$done · $colorLabel"
}

internal fun formatHmsLog(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d".format(h, m, s)
}
