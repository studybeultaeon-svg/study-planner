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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.phonelock.desktop.data.*
import com.phonelock.desktop.data.CalendarTask
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.TimerRunState
import com.phonelock.desktop.monitor.PomodoroSyncClient
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.shared.StudyProgressQuotes
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * 데스크탑판(28차 세션)은 왼쪽에 타이머 본체, 오른쪽에 부가 정보를 두는 좌우 분할 — 모바일처럼 세로로
 * 전부 쌓지 않고 넓은 화면을 역할별로 나눠 쓴다. 90차에 오른쪽에 있던 허용 프로그램/사이트 편집은
 * 설정 > 공부 탭으로 옮기고(한 번 정해두는 설정이라 매번 보는 타이머 탭에 있을 이유가 없음), 그 자리는
 * 타이머 그림(TimerIllustration)으로 채웠다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyTimerScreen(repository: Repository) {
    var run by remember { mutableStateOf(repository.getTimerRun()) }
    var todayTasks by remember { mutableStateOf(repository.getCalendarTasks(repository.todayCalendarDateKey())) }
    var taskName by remember { mutableStateOf(todayTasks.firstOrNull { it.name.isNotBlank() }?.name ?: "") }
    // 93차(사용자 요청): "해당 없음"을 골라 taskName을 일부러 비웠는데, 아래 자동 채움 로직이
    // "비어있으면 첫 일정으로 채운다"는 규칙 때문에 다음 목록 갱신 때 도로 채워버리는 문제가 있었다 —
    // 사용자가 한 번이라도 직접 고르거나 입력했으면 그 뒤로는 자동 채움을 하지 않는다.
    var taskNameTouchedByUser by remember { mutableStateOf(false) }
    var taskDropdownExpanded by remember { mutableStateOf(false) }

    // 1초마다 todayTasks를 새로 불러오는데, 그때마다 taskName을 무조건 첫 항목으로 되돌리면
    // 사용자가 고른 값이 계속 리셋된다. 사용자가 아직 손대지 않았을 때만 자동으로 채운다.
    LaunchedEffect(todayTasks) {
        if (!taskNameTouchedByUser && (taskName.isBlank() || todayTasks.none { it.name == taskName })) {
            taskName = todayTasks.firstOrNull { it.name.isNotBlank() }?.name ?: ""
        }
    }
    var pomodoroEnabled by remember { mutableStateOf(repository.pomodoroModeEnabled) }
    var studyMinText by remember { mutableStateOf(repository.pomodoroStudyMinutes.toString()) }
    var breakMinText by remember { mutableStateOf(repository.pomodoroBreakMinutes.toString()) }
    // 99차+(사용자 요청): 목표 시간/사이클 대비 진행률에 따라 응원 문구를 보여주기 위한 선택 입력값 —
    // 0/빈 칸이면 목표 미설정으로 취급해 문구를 아예 안 띄운다(기존 동작 보존).
    var studyGoalText by remember { mutableStateOf(repository.studyGoalMinutes.let { if (it > 0) it.toString() else "" }) }
    var pomodoroTargetCyclesText by remember { mutableStateOf(repository.pomodoroTargetCycles.let { if (it > 0) it.toString() else "" }) }
    var todayLog by remember { mutableStateOf(repository.getTodayStudyLog()) }
    // 92차(사용자 요청, "타이머 화면이 여전히 비어보인다"): 스트릭/주간 그래프 2개를 채우려고 추가.
    // `getAllStudyLogOnce()`는 이 기기 로컬 기록만 반환해서(다른 기기가 그날 올린 기록은
    // `remoteStudyLogCache`에만 있고 안 섞여 있음, `getTodayStudyLog()`/`getStudyLogForDate()`만
    // 그 캐시를 합쳐 반환함) 그대로 쓰면 다른 기기에서만 공부한 날이 0으로 보이는 동기화 버그가 된다 —
    // 그래서 날짜별로 `syncStudyLogFromFirebase(dateKey)` 동기화 후 `getStudyLogForDate(dateKey)`로
    // 합산해야 한다(아래 `daySeconds()`/`refreshStreakAndWeek()` 참고).
    var last7Days by remember { mutableStateOf(listOf<Pair<LocalDate, Long>>()) }
    var studyStreak by remember { mutableStateOf(0) }
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
    var stopTagText by remember { mutableStateOf("") }

    // 92차: 특정 날짜의 "모든 기기 합산" 공부시간 — 오늘은 이미 5초마다 동기화되는 todayLog를 그대로
    // 쓰고(더 자주 갱신돼 신선함), 과거 날짜만 그 자리에서 동기화 후 합산한다.
    suspend fun daySecondsSynced(dateKey: String): Long {
        if (dateKey == repository.todayCalendarDateKey()) return todayLog.sumOf { it.seconds }.toLong()
        return withContext(Dispatchers.IO) {
            repository.syncStudyLogFromFirebase(dateKey)
            repository.getStudyLogForDate(dateKey).sumOf { it.seconds }.toLong()
        }
    }
    suspend fun refreshStreakAndWeek() {
        val today = LocalDate.parse(repository.todayCalendarDateKey())
        val week = (6 downTo 0).map { offset ->
            val d = today.minusDays(offset.toLong())
            d to daySecondsSynced(d.toString())
        }
        last7Days = week
        // 스트릭: 오늘부터 거슬러 올라가며 끊기는 지점까지 — 위에서 이미 동기화한 최근 7일은 재사용하고,
        // 그보다 더 길면 하루씩 추가로 동기화(무한 네트워크 호출 방지용 60일 상한).
        var streak = 0
        var d = today
        while (streak < 60) {
            val seconds = if (streak < week.size) week[week.size - 1 - streak].second else daySecondsSynced(d.toString())
            if (seconds <= 0) break
            streak++
            d = d.minusDays(1)
        }
        studyStreak = streak
    }

    LaunchedEffect(Unit) {
        refreshStreakAndWeek()
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
                withContext(Dispatchers.IO) {
                    repository.syncStudyLogFromFirebase(repository.todayCalendarDateKey())
                    // 93차(사용자 요청): 다른 기기(안드로이드 등)에서 오늘 캘린더 일정을 새로 추가해도
                    // 이 탭은 CalendarScreen/StudyStatsScreen과 달리 진입 시 동기화를 한 번도 안 해서
                    // 로컬 데이터가 오래된 채로 남아있었다 — "생과"/"항생제" 같은 새 일정이 드롭다운에
                    // 안 보이던 원인. 여기서도 같은 주기로 동기화한다.
                    repository.syncCalendarFromFirebase()
                }
                todayLog = repository.getTodayStudyLog()
                calcTasksForSummary = repository.getCalcTasks()
                if (run == null) todayTasks = repository.getCalendarTasks(repository.todayCalendarDateKey())
            }
            // 30초마다 스트릭/주간 그래프 갱신 — 과거 날짜 동기화는 매 5초씩 하기엔 비용이 커서 더 낮은 주기로.
            if (tickCount % 30 == 0) {
                refreshStreakAndWeek()
            }
        }
    }

    fun refreshLog() {
        todayLog = repository.getTodayStudyLog()
    }

    // 당겨서 새로고침(사용자 요청, 안드로이드판은 스와이프) — 이 탭은 이미 5초/30초 주기로 자동
    // 동기화되지만, 계산기 동기화는 자동 루프에 없어서 수동으로 즉시 최신화하고 싶을 때를 위해 추가한다.
    val scope = rememberCoroutineScope()
    suspend fun refreshNow() {
        withContext(Dispatchers.IO) {
            repository.syncCalendarFromFirebase()
            repository.syncCalculatorFromFirebase()
        }
        todayTasks = repository.getCalendarTasks(repository.todayCalendarDateKey())
        calcTasksForSummary = repository.getCalcTasks()
        todayLog = repository.getTodayStudyLog()
        refreshStreakAndWeek()
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

    Column(Modifier.fillMaxSize().padding(Spacing.md)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("⏱️ 시간 측정", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            IconButton(onClick = { scope.launch { refreshNow() } }) { Text("🔄") }
        }
        Spacer(Modifier.height(Spacing.md))

        TodaySummaryCard(todayTasks = todayTasks, calcTasks = calcTasksForSummary, todayLogSeconds = todayLog.sumOf { it.seconds }.toLong())
        Spacer(Modifier.height(Spacing.md))

        // 오른쪽 일러스트에 넘길 값 — 실행 중(또는 다른 기기 미러링 중)이면 뽀모도로 진행률을 그리고,
        // 대기 중이면 정적인 시계 모양만 그린다. 판정 로직과 무관한 순수 표시값이다.
        val illustrationRun = run ?: if (remoteStudying || remoteResting) TimerRunState(
            taskName = remoteTaskName,
            mode = remoteMode,
            phase = if (remoteResting) "break" else "study",
            phaseStartedAt = remotePhaseStartedAt,
            phaseEndAt = remotePhaseEndAt
        ) else null
        val illustrationProgress = illustrationRun
            ?.takeIf { it.mode == "pomodoro" && it.phaseEndAt > it.phaseStartedAt }
            ?.let { ((nowMillis - it.phaseStartedAt).toFloat() / (it.phaseEndAt - it.phaseStartedAt).toFloat()).coerceIn(0f, 1f) }
        // 92차(사용자 요청): 뽀모도로가 아닌 일반 스톱워치 모드는 목표 시간이 없어 진행률(%)을 못 그리는데,
        // 그렇다고 실행 중에도 대기 중과 똑같이 정지된 시계로 보이면 "지금 흐르고 있다"는 느낌이 없다 —
        // 목표 없이도 그릴 수 있는 값인 "경과 초"로 초침만 계속 돌려서 살아있는 느낌을 준다.
        val illustrationSecondHandAngle = illustrationRun
            ?.takeIf { it.mode != "pomodoro" }
            ?.let { ((nowMillis - it.phaseStartedAt) / 1000 % 60) * 6f }
        val illustrationCaption = when {
            illustrationRun == null -> "공부를 시작하면 여기에 진행 상황이 표시됩니다"
            illustrationRun.phase == "break" -> "휴식 중 — 잠시 쉬어가세요"
            else -> "공부 중 — 이 시간이 아래 기록으로 쌓입니다"
        }

        // studyStreak/last7Days는 위 refreshStreakAndWeek()가 채우는 state — 다른 기기 기록까지
        // 합산해야 해서(위 주석 참고) 여기서 파생 계산하지 않는다.
        // 92차: 오늘 과목(태그)별 공부시간 — 태그를 안 남겼으면 업무 이름으로 대신 묶는다. 이건
        // todayLog(이미 getTodayStudyLog()가 다른 기기 기록과 합쳐 반환) 그대로 써도 정확하다.
        val todaySubjects = remember(todayLog) {
            todayLog.groupBy { it.tag.ifBlank { it.taskName.ifBlank { "기타" } } }
                .mapValues { (_, entries) -> entries.sumOf { it.seconds }.toLong() }
                .toList()
                .sortedByDescending { it.second }
        }

        // 79차: 창이 좁아지면 위아래로 쌓는 ResponsiveSplit(사용자 요청, CalendarScreen/CalculatorScreen과 동일 패턴).
        com.phonelock.desktop.ui.components.ResponsiveSplit(modifier = Modifier.weight(1f), left = {
            // 왼쪽: 타이머 본체
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
                        // 99차+(사용자 요청): 목표(뽀모도로=사이클 수, 일반=시간) 설정, 선택 입력 —
                        // 비워두면 진행률 문구를 안 띄우던 기존 동작 그대로 유지.
                        if (pomodoroEnabled) {
                            OutlinedTextField(
                                value = pomodoroTargetCyclesText,
                                onValueChange = { text ->
                                    pomodoroTargetCyclesText = text
                                    val n = text.toIntOrNull()
                                    repository.pomodoroTargetCycles = if (n != null && n > 0) n else 0
                                },
                                label = { Text("목표 사이클 수(선택)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            OutlinedTextField(
                                value = studyGoalText,
                                onValueChange = { text ->
                                    studyGoalText = text
                                    val n = text.toIntOrNull()
                                    repository.studyGoalMinutes = if (n != null && n > 0) n else 0
                                },
                                label = { Text("목표 시간(분, 선택)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(Spacing.sm))
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
                            Text("시작")
                        }
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

                        // 99차+(사용자 요청): 목표(뽀모도로=사이클 수, 일반=시간) 대비 진행률에 따른 응원
                        // 문구 — 목표 미설정(0)이면 아예 표시 안 함(기존 동작 보존).
                        val progress: Double? = if (current.mode == "pomodoro") {
                            val targetCycles = repository.pomodoroTargetCycles
                            if (targetCycles > 0) {
                                val phaseFraction = if (current.phase == "study" && current.phaseEndAt > current.phaseStartedAt) {
                                    ((nowMillis - current.phaseStartedAt).toDouble() / (current.phaseEndAt - current.phaseStartedAt)).coerceIn(0.0, 1.0)
                                } else 0.0
                                (current.cycleCount + phaseFraction) / targetCycles
                            } else null
                        } else {
                            val goalMinutes = repository.studyGoalMinutes
                            if (goalMinutes > 0) (nowMillis - current.phaseStartedAt).toDouble() / (goalMinutes * 60_000.0) else null
                        }
                        if (progress != null) {
                            val tier = StudyProgressQuotes.tierFor(progress)
                            val quote = remember(tier) { StudyProgressQuotes.forProgress(progress) }
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = GREEN.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, GREEN.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    quote,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = GREEN,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                            Spacer(Modifier.height(Spacing.sm))
                        }
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
            // 오른쪽: 타이머 일러스트 + 오늘 기록
            // (90차: 허용 프로그램/사이트 편집은 설정 > 공부 탭으로 옮겨서 여기 없다 — 매번 보는 화면이
            //  아니라 한 번 정해두는 설정이기 때문. 그 자리는 아래 TimerIllustration이 채운다.)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                TimerIllustration(
                    progress = illustrationProgress,
                    secondHandAngle = illustrationSecondHandAngle,
                    caption = illustrationCaption
                )
                Spacer(Modifier.height(Spacing.md))

                StreakCard(streak = studyStreak)
                Spacer(Modifier.height(Spacing.md))

                SectionCard("📈 최근 7일 공부시간") {
                    WeekBarChart(days = last7Days)
                }
                Spacer(Modifier.height(Spacing.md))

                if (todaySubjects.isNotEmpty()) {
                    SectionCard("🥧 오늘 과목별 공부시간") {
                        SubjectPieChart(subjects = todaySubjects)
                    }
                    Spacer(Modifier.height(Spacing.md))
                }

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
 * 90차(사용자 요청): 허용 프로그램/사이트 설정을 설정 탭으로 옮기면서 비게 된 오른쪽 컬럼 위쪽을 채우는
 * 담백한 타이머 그림. 이 프로젝트엔 이미지 자산 파이프라인이 없어(DECISIONS.md 89차) 전부 Compose로
 * 직접 그린다 — 대기 중이면 정적인 시계(테두리 링 + 두 바늘), 뽀모도로 실행 중이면 그 자리에 진행률
 * 호(arc)를 덧그린다. 92차: 일반 스톱워치(뽀모도로 아님) 실행 중엔 목표 시간이 없어 호를 못 그리는 대신
 * 경과 초에 맞춰 도는 초침(secondHandAngle, 12시=0도·시계방향)을 덧그려 "지금 흐르고 있다"는 걸 보여준다.
 */
@Composable
private fun TimerIllustration(progress: Float?, secondHandAngle: Float? = null, caption: String) {
    val trackColor = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(132.dp)) {
                val strokeWidth = 10.dp.toPx()
                val diameter = size.minDimension - strokeWidth
                val topLeft = androidx.compose.ui.geometry.Offset(
                    (size.width - diameter) / 2f,
                    (size.height - diameter) / 2f
                )
                val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
                drawArc(
                    color = trackColor.copy(alpha = 0.5f),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                )
                if (progress != null) {
                    drawArc(
                        color = accent,
                        startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = strokeWidth,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    )
                } else {
                    // 대기 중이든 일반 스톱워치 실행 중이든 — 10시 10분을 가리키는 정적인 시계 바늘
                    // 두 개(시계 아이콘 관례)는 항상 그린다.
                    val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                    val handStroke = 4.dp.toPx()
                    listOf(
                        (diameter * 0.34f) to 60.0,   // 분침(2시 방향)
                        (diameter * 0.24f) to 300.0   // 시침(10시 방향)
                    ).forEach { (length, clockDegrees) ->
                        // 시계 각도(12시=0, 시계방향) → 화면 좌표계 각도(+x축 기준, y는 아래로 증가)
                        val rad = Math.toRadians(clockDegrees - 90.0)
                        drawLine(
                            color = accent.copy(alpha = 0.7f),
                            start = center,
                            end = androidx.compose.ui.geometry.Offset(
                                center.x + (length * Math.cos(rad)).toFloat(),
                                center.y + (length * Math.sin(rad)).toFloat()
                            ),
                            strokeWidth = handStroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                    // 92차: 일반 스톱워치 실행 중엔 경과 초에 맞춰 도는 초침을 하나 더 그려서
                    // 정지된 대기 화면과 구분되는 "지금 흐르고 있다"는 느낌을 준다.
                    if (secondHandAngle != null) {
                        val rad = Math.toRadians(secondHandAngle - 90.0)
                        val length = diameter * 0.4f
                        drawLine(
                            color = accent,
                            start = center,
                            end = androidx.compose.ui.geometry.Offset(
                                center.x + (length * Math.cos(rad)).toFloat(),
                                center.y + (length * Math.sin(rad)).toFloat()
                            ),
                            strokeWidth = handStroke * 0.5f,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 92차(사용자 요청, "타이머 화면이 여전히 비어보인다"): 오늘부터 거슬러 센 연속 공부일 카드. */
@Composable
private fun StreakCard(streak: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (streak > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (streak > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(if (streak > 0) "🔥" else "💤", style = MaterialTheme.typography.headlineMedium)
            Column {
                Text(
                    if (streak > 0) "연속 공부 ${streak}일째" else "오늘부터 연속 기록을 시작해보세요",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (streak > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("하루라도 공부 시간이 기록되면 이어집니다", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 92차: 최근 7일(오늘 포함) 공부시간 막대그래프 — 요일 라벨 + 시간 툴팁 없이 값 자체를 막대 위에 표기. */
@Composable
private fun WeekBarChart(days: List<Pair<LocalDate, Long>>) {
    val accent = MaterialTheme.colorScheme.primary
    val maxSeconds = (days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val dowLabels = listOf("월", "화", "수", "목", "금", "토", "일")
    Row(
        Modifier.fillMaxWidth().height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        days.forEach { (date, seconds) ->
            val fraction = (seconds.toFloat() / maxSeconds.toFloat()).coerceIn(0f, 1f)
            val isToday = date == LocalDate.now()
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    if (seconds > 0) formatHmsShort(seconds) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .fillMaxHeight(0.72f * fraction.coerceAtLeast(0.03f))
                        .background(
                            if (isToday) accent else accent.copy(alpha = 0.55f),
                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    dowLabels[date.dayOfWeek.value - 1],
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) accent else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (maxSeconds <= 1L && days.all { it.second == 0L }) {
        Spacer(Modifier.height(Spacing.sm))
        Text("최근 7일간 기록된 공부 시간이 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 92차: 오늘 과목(태그)별 공부시간 도넛 차트 + 범례. */
@Composable
private fun SubjectPieChart(subjects: List<Pair<String, Long>>) {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        SUBJECT_COLOR_3, SUBJECT_COLOR_4, SUBJECT_COLOR_5, SUBJECT_COLOR_6
    )
    val total = subjects.sumOf { it.second }.coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(100.dp)) {
            val strokeWidth = 18.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = androidx.compose.ui.geometry.Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
            var startAngle = -90f
            subjects.forEachIndexed { idx, (_, seconds) ->
                val sweep = 360f * (seconds.toFloat() / total.toFloat())
                drawArc(
                    color = palette[idx % palette.size],
                    startAngle = startAngle, sweepAngle = sweep, useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                )
                startAngle += sweep
            }
        }
        Spacer(Modifier.width(Spacing.md))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            subjects.take(6).forEachIndexed { idx, (name, seconds) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).background(palette[idx % palette.size], CircleShape))
                    Text(name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    Text(formatHmsShort(seconds), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val SUBJECT_COLOR_3 = Color(0xFFF59E0B)
private val SUBJECT_COLOR_4 = Color(0xFFA78BFA)
private val SUBJECT_COLOR_5 = Color(0xFF34D399)
private val SUBJECT_COLOR_6 = Color(0xFFEC4899)

private fun formatHmsShort(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** 웹앱의 "입력창 + 추가 버튼 + 목록(항목마다 ✕ 삭제)" 패턴 — 허용 프로그램/사이트 둘 다 같은 UI. */
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
    return "${task.name}$done · ${task.passIndex + 1}회 복습"
}

internal fun formatHmsLog(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d".format(h, m, s)
}
