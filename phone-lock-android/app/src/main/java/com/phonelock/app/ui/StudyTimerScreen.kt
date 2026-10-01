package com.phonelock.app.ui

import com.phonelock.app.ui.components.LedgerAlertDialog
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phonelock.app.service.AccessibilityServiceChecker
import com.phonelock.app.data.*
import com.phonelock.app.data.CalcTask
import com.phonelock.app.data.CalendarTask
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.StudyLogEntry
import com.phonelock.app.data.TimerRunState
import com.phonelock.app.service.PomodoroSyncClient
import com.phonelock.app.ui.components.DurationHero
import com.phonelock.app.ui.components.FitText
import com.phonelock.app.ui.components.Hairline
import com.phonelock.app.ui.components.LedgerSection
import com.phonelock.app.ui.components.LiveDot
import com.phonelock.app.ui.components.NoticeStrip
import com.phonelock.app.ui.components.NoticeTone
import com.phonelock.app.ui.components.Overline
import com.phonelock.app.ui.components.ProgressLine
import com.phonelock.app.ui.components.StatBlock
import com.phonelock.app.ui.components.StatRow
import com.phonelock.app.ui.components.VerticalHairline
import com.phonelock.app.ui.theme.LocalAppMotion
import com.phonelock.app.ui.theme.LocalPhoneLockPalette
import com.phonelock.app.ui.theme.Spacing
import com.phonelock.app.ui.theme.pressScale
import com.phonelock.shared.StudyProgressQuotes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

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
    // 99차+(사용자 요청): 목표 시간/사이클 대비 진행률에 따라 응원 문구를 보여주기 위한 선택 입력값 —
    // 0/빈 칸이면 목표 미설정으로 취급해 문구를 아예 안 띄운다(기존 동작 보존, 데스크탑판과 대칭).
    var studyGoalText by remember { mutableStateOf(repository.studyGoalMinutes.let { if (it > 0) it.toString() else "" }) }
    var pomodoroTargetCyclesText by remember { mutableStateOf(repository.pomodoroTargetCycles.let { if (it > 0) it.toString() else "" }) }
    var todayLog by remember { mutableStateOf(listOf<StudyLogEntry>()) }
    // 92차(사용자 요청, "타이머 화면이 여전히 비어보인다"): 스트릭/주간 그래프 2개를 채우려고 추가.
    // `getAllStudyLogOnce()`는 이 기기 로컬 기록만 반환해서(다른 기기가 그날 올린 기록은
    // `remoteStudyLogCache`에만 있고 안 섞여 있음, `getTodayStudyLog()`/`getStudyLogForDate()`만
    // 그 캐시를 합쳐 반환함) 그대로 쓰면 다른 기기에서만 공부한 날이 0으로 보이는 동기화 버그가 된다 —
    // 그래서 날짜별로 `syncStudyLogFromFirebase(dateKey)` 동기화 후 `getStudyLogForDate(dateKey)`로
    // 합산해야 한다(아래 `daySecondsSynced()`/`refreshStreakAndWeek()` 참고, 데스크탑판과 대칭).
    var last7Days by remember { mutableStateOf(listOf<Pair<LocalDate, Long>>()) }
    var studyStreak by remember { mutableStateOf(0) }
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
    // 126차(사용자 요청): 실행 중에도 일정을 바꿀 수 있게 — 예전엔 일정을 바꾸려면 타이머를 정지했다
    // 다시 시작해야 했다.
    var showTaskChangeDialog by remember { mutableStateOf(false) }
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
            NoticeStrip(
                "접근성 서비스가 꺼져 있어 지금 집중 잠금이 동작하지 않습니다",
                modifier = Modifier.padding(bottom = Spacing.lg),
                actionLabel = "켜기",
                onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            )
        }
    }

    fun refreshLog() {
        scope.launch { todayLog = repository.getTodayStudyLog() }
    }

    // 92차: 특정 날짜의 "모든 기기 합산" 공부시간 — 오늘은 이미 5초마다 동기화되는 todayLog를 그대로
    // 쓰고(더 자주 갱신돼 신선함), 과거 날짜만 그 자리에서 동기화 후 합산한다(데스크탑판과 대칭).
    suspend fun daySecondsSynced(dateKey: String): Long {
        if (dateKey == repository.todayCalendarDateKey()) return todayLog.sumOf { it.seconds }.toLong()
        repository.syncStudyLogFromFirebase(dateKey)
        return repository.getStudyLogForDate(dateKey).sumOf { it.seconds }.toLong()
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
        // 142차: 오늘 아직 기록이 없으면 어제부터 센다(통계 탭 StudyStats.currentStreak과 같은 규칙) — 하루가
        // 끝나기 전에는 연속 기록이 0으로 보이지 않고, 오늘 공부하면 +1 된다.
        val skip = if (week.last().second <= 0) 1 else 0
        var streak = 0
        while (streak < 60) {
            val back = streak + skip
            val seconds = if (back < week.size) week[week.size - 1 - back].second
            else daySecondsSynced(today.minusDays(back.toLong()).toString())
            if (seconds <= 0) break
            streak++
        }
        studyStreak = streak
    }

    // 당겨서 새로고침(사용자 요청) — 이 탭은 이미 5초/30초 주기로 자동 동기화되지만, 계산기 동기화는
    // 자동 루프에 없어서(캘린더만 있음) 수동으로 즉시 최신화하고 싶을 때를 위해 추가한다.
    suspend fun refresh() {
        repository.syncCalendarFromFirebase()
        repository.syncCalculatorFromFirebase()
        // 138차: 새로고침이 오늘 공부 기록은 동기화하지 않아서, 5초 주기 동기화가 돌 때까지 몇 번을
        // 당겨도 그대로였다.
        repository.syncStudyLogFromFirebase(repository.todayCalendarDateKey())
        todayTasks = repository.getCalendarTasks(repository.todayCalendarDateKey())
        calcTasksForSummary = repository.getCalcTasks()
        todayLog = repository.getTodayStudyLog()
        refreshStreakAndWeek()
    }

    LaunchedEffect(Unit) {
        // 저장해둔 다른 기기 기록까지 합쳐 곧바로 보여준 뒤(138차),
        todayLog = repository.getTodayStudyLog()
        todayTasks = repository.getCalendarTasks(repository.todayCalendarDateKey())
        calcTasksForSummary = repository.getCalcTasks()
        // 첫 5초 tick을 기다리지 않고 진입 즉시 오늘 기록을 동기화한다 — 예전엔 주간 그래프/스트릭이
        // 동기화 전 값(이 기기 기록만)으로 계산된 채 30초 동안 남아 "초기화"된 것처럼 보였다(138차).
        repository.syncStudyLogFromFirebase(repository.todayCalendarDateKey())
        todayLog = repository.getTodayStudyLog()
        refreshStreakAndWeek()
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
                // 126차: 실행 중에도 "일정 변경" 다이얼로그가 최신 목록을 보여줘야 해서 run 상태와
                // 무관하게 갱신한다(taskName 자동 채움은 taskNameTouchedByUser 가드가 막아준다).
                todayTasks = repository.getCalendarTasks(repository.todayCalendarDateKey())
            }
            // 30초마다 스트릭/주간 그래프 갱신 — 과거 날짜 동기화는 매 5초씩 하기엔 비용이 커서 더 낮은 주기로.
            // 138차: 오늘 합계가 그래프와 달라졌으면(다른 기기 기록 도착 등) 30초를 기다리지 않고 바로 맞춘다.
            if (tickCount % 30 == 0 || last7Days.lastOrNull()?.second?.let { it != todayLog.sumOf { e -> e.seconds }.toLong() } == true) {
                refreshStreakAndWeek()
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
        LedgerAlertDialog(
            onDismissRequest = { showStopNoteDialog = false },
            title = { Text("집중 종료") },
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
                        placeholder = { Text("예: 3장까지 읽었다, 집중이 잘 됐다") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = stopTagText,
                        onValueChange = { stopTagText = it },
                        label = { Text("태그(분야 등, 선택)") },
                        placeholder = { Text("예: 독서, 업무, 운동") },
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

    // 타이머가 그사이 멈췄으면 바꿀 대상이 없으므로 그냥 안 띄운다.
    run?.takeIf { showTaskChangeDialog }?.let { current ->
        StudyTaskChangeDialog(
            todayTasks = todayTasks,
            currentTaskName = current.taskName,
            onDismiss = { showTaskChangeDialog = false },
            onConfirm = { newName ->
                repository.timerChangeTask(newName)
                run = repository.getTimerRun()
                // 앞 구간이 방금 기록으로 넘어갔으므로 "오늘의 공부 기록"도 바로 다시 읽는다.
                refreshLog()
                showTaskChangeDialog = false
            }
        )
    }

    // 144차 리디자인: 타이머 탭의 "주인공"은 숫자 하나다. 대기 중엔 오늘 집중한 시간이, 실행 중엔 지금 재는 시간이
    // 화면 맨 위를 크게 차지하고, 시작 준비·기록·그래프는 그 아래에 가는 선으로만 나뉜 조용한 섹션이 된다.
    // 상태/동기화/버튼 동작은 그대로이고 배치와 표현만 바꿨다(데스크탑판과 같은 구성).
    val palette = LocalPhoneLockPalette.current
    val motion = LocalAppMotion.current
    val remoteActive = remoteStudying || remoteResting
    val mirrorFromRemote = run == null && remoteActive
    val timerActive = run != null || mirrorFromRemote
    val todaySeconds = todayLog.sumOf { it.seconds }.toLong()

    val idleContent: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            Overline("오늘 집중")
            Spacer(Modifier.height(6.dp))
            DurationHero(todaySeconds)
            Spacer(Modifier.height(4.dp))
            Text(
                if (studyStreak > 0) "연속 ${studyStreak}일째 이어가는 중" else "오늘 기록하면 연속 기록이 시작됩니다",
                style = MaterialTheme.typography.bodyMedium,
                color = if (studyStreak > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xl))
            Overline("무엇에 집중할까요")
            Spacer(Modifier.height(Spacing.sm))
            // 92차(사용자 요청): 일정 목록에서 골라도 되고 직접 입력해도 되는 하나의 입력칸(readOnly 아님).
            ExposedDropdownMenuBox(
                expanded = taskDropdownExpanded,
                onExpandedChange = { taskDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = taskName,
                    onValueChange = { taskName = it; taskNameTouchedByUser = true },
                    readOnly = false,
                    label = { Text("오늘 캘린더 일정") },
                    placeholder = { Text("예: 수학, 독서, 운동 (비워둬도 됩니다)") },
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = taskDropdownExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = taskDropdownExpanded,
                    onDismissRequest = { taskDropdownExpanded = false }
                ) {
                    // 93차(사용자 요청): 빈칸으로 지우는 방법을 모르는 사용자를 위해 "해당 없음"을 항상 맨 위에 둔다.
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
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("뽀모도로", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        if (pomodoroEnabled) "집중과 휴식을 번갈아 잽니다" else "멈출 때까지 이어서 잽니다",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = pomodoroEnabled,
                    onCheckedChange = {
                        pomodoroEnabled = it
                        repository.pomodoroModeEnabled = it
                    }
                )
            }
            if (pomodoroEnabled) {
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedTextField(
                        value = studyMinText,
                        onValueChange = { text ->
                            studyMinText = text
                            text.toIntOrNull()?.let { if (it > 0) repository.pomodoroStudyMinutes = it }
                        },
                        label = { Text("집중(분)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = breakMinText,
                        onValueChange = { text ->
                            breakMinText = text
                            text.toIntOrNull()?.let { if (it > 0) repository.pomodoroBreakMinutes = it }
                        },
                        label = { Text("휴식(분)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            // 99차+(사용자 요청): 목표(뽀모도로=사이클 수, 일반=시간), 선택 입력 — 비워두면 진행률 문구를 띄우지 않는다.
            if (pomodoroEnabled) {
                OutlinedTextField(
                    value = pomodoroTargetCyclesText,
                    onValueChange = { text ->
                        pomodoroTargetCyclesText = text
                        val n = text.toIntOrNull()
                        repository.pomodoroTargetCycles = if (n != null && n > 0) n else 0
                    },
                    label = { Text("목표 사이클 수(선택)") },
                    singleLine = true,
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
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(Spacing.md))
            val startInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Button(
                onClick = {
                    repository.timerStart(taskName, pomodoroEnabled)
                    run = repository.getTimerRun()
                },
                interactionSource = startInteraction,
                modifier = Modifier.fillMaxWidth().height(56.dp).pressScale(startInteraction)
            ) {
                androidx.compose.material3.Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text("집중 시작", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
            }
        }
    }

    val runningContent: @Composable () -> Unit = {
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
        val phaseColor = if (isBreak) palette.success else MaterialTheme.colorScheme.primary
        // 점·막대(채움)는 테마와 무관한 밝은 색, 글자·큰 숫자는 대비용 색(144차 후속).
        val phaseFill = if (isBreak) palette.fillGood else MaterialTheme.colorScheme.primary
        val displaySec = if (current.mode == "pomodoro") {
            ((current.phaseEndAt - nowMillis) / 1000L).coerceAtLeast(0L)
        } else {
            ((nowMillis - current.phaseStartedAt) / 1000L).coerceAtLeast(0L)
        }
        val timedUp = current.mode == "pomodoro" && displaySec <= 0L
        // 99차+(사용자 요청): 목표(뽀모도로=사이클 수, 일반=시간) 대비 진행률 — 목표 미설정(0)이면 null.
        val goalProgress: Double? = if (current.mode == "pomodoro") {
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
        // 숫자 아래 막대: 뽀모도로면 이번 단계가 얼마나 지났는지, 일반 모드면 목표 대비(목표가 없으면 막대 없음).
        val barProgress: Float? = if (current.mode == "pomodoro" && current.phaseEndAt > current.phaseStartedAt) {
            ((nowMillis - current.phaseStartedAt).toFloat() / (current.phaseEndAt - current.phaseStartedAt).toFloat()).coerceIn(0f, 1f)
        } else goalProgress?.toFloat()?.coerceIn(0f, 1f)

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveDot(phaseFill)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isBreak) "휴식 중" else "집중 중",
                    style = MaterialTheme.typography.labelLarge,
                    color = phaseColor,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    " · " + current.taskName.ifBlank { "이름 없는 집중" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 다른 기기 세션을 미러링 중일 땐 제어하지 않는다(정지/전환과 같은 규칙).
                if (!isMirror) {
                    TextButton(onClick = { showTaskChangeDialog = true }) {
                        Text("일정 변경", maxLines = 1, softWrap = false)
                    }
                }
            }
            FitText(
                formatHmsLog(displaySec),
                style = MaterialTheme.typography.displayLarge,
                maxSize = 88.sp,
                color = phaseColor
            )
            if (barProgress != null) {
                Spacer(Modifier.height(Spacing.sm))
                ProgressLine(barProgress, color = phaseFill)
            }
            if (goalProgress != null) {
                val tier = StudyProgressQuotes.tierFor(goalProgress)
                val quote = remember(tier) { StudyProgressQuotes.forProgress(goalProgress) }
                Spacer(Modifier.height(Spacing.sm))
                Text(quote, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            }
            if (timedUp) {
                Spacer(Modifier.height(Spacing.md))
                NoticeStrip(
                    "시간이 다 됐어요 — " + if (isBreak) {
                        if (current.breakExtraUsed) "준비되면 집중으로 전환하세요" else "5분만 더 쉬거나 집중으로 전환하세요"
                    } else "휴식으로 전환하세요",
                    tone = NoticeTone.Warning
                )
            }
            Spacer(Modifier.height(Spacing.lg))
            if (isMirror) {
                // 다른 기기가 시작한 세션은 그 기기에서만 정지/전환한다(19차 remoteCommand 왕복 문제 재현 방지 — DECISIONS.md).
                Text(
                    "다른 기기에서 실행 중입니다 — 정지·전환은 그 기기에서 해주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val canSwitch = current.mode == "pomodoro" && (current.phase == "break" || nowMillis >= current.phaseEndAt)
                if (canSwitch) {
                    Button(
                        onClick = {
                            repository.timerSwitchPhase()
                            run = repository.getTimerRun()
                            refreshLog()
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text(if (current.phase == "study") "휴식으로 전환" else "집중으로 전환", maxLines = 1, softWrap = false) }
                    Spacer(Modifier.height(Spacing.sm))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { showStopNoteDialog = true },
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        androidx.compose.material3.Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("정지", maxLines = 1, softWrap = false)
                    }
                    if (current.mode == "pomodoro" && current.phase == "break" && nowMillis >= current.phaseEndAt && !current.breakExtraUsed) {
                        OutlinedButton(
                            onClick = {
                                repository.timerExtendBreak()
                                run = repository.getTimerRun()
                            },
                            modifier = Modifier.weight(1f).height(52.dp)
                        ) { Text("5분만 더", maxLines = 1, softWrap = false) }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                "오늘 합계 " + formatHmsLog(todaySeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 시작/정지할 때 히어로가 "오늘 합계"에서 "지금 재는 시간"으로 바뀐다 — 살짝 커지며 나타나 숫자가 자리를 넘겨받는
    // 느낌을 준다(성능 모드에선 짧은 페이드).
    val heroContent: @Composable () -> Unit = {
        androidx.compose.animation.AnimatedContent(
            targetState = timerActive,
            transitionSpec = {
                if (motion.reduced) fadeIn(motion.standard()) togetherWith fadeOut(motion.exit())
                else (fadeIn(motion.emphasized()) + scaleIn(motion.emphasized(), initialScale = 0.94f)) togetherWith fadeOut(motion.exit())
            },
            label = "timerHero"
        ) { active -> if (active) runningContent() else idleContent() }
    }

    // studyStreak/last7Days는 refreshStreakAndWeek()가 채우는 state — 다른 기기 기록까지 합산해야 해서 여기서 파생하지 않는다.
    // 92차: 오늘 분야(태그)별 집중 시간 — 태그를 안 남겼으면 업무 이름으로 묶는다.
    val todaySubjects = remember(todayLog) {
        todayLog.groupBy { it.tag.ifBlank { it.taskName.ifBlank { "기타" } } }
            .mapValues { (_, entries) -> entries.sumOf { it.seconds }.toLong() }
            .toList()
            .sortedByDescending { it.second }
    }

    val extrasContent: @Composable () -> Unit = {
        LedgerSection("오늘 한눈에") {
            TodaySummaryStrip(today = LocalDate.parse(repository.todayCalendarDateKey()), todayTasks = todayTasks, calcTasks = calcTasksForSummary)
            Spacer(Modifier.height(Spacing.lg))
        }
        LedgerSection("최근 7일") {
            WeekBarChart(days = last7Days)
            Spacer(Modifier.height(Spacing.lg))
        }
        if (todaySubjects.isNotEmpty()) {
            LedgerSection("분야별") {
                SubjectPieChart(subjects = todaySubjects)
                Spacer(Modifier.height(Spacing.lg))
            }
        }
        LedgerSection("오늘의 기록") {
            if (todayLog.isEmpty()) {
                Text("아직 오늘 기록된 집중 시간이 없습니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val byTask = todayLog.groupBy { it.taskName }
                byTask.entries.sortedByDescending { (_, entries) -> entries.sumOf { it.seconds } }.forEach { (name, entries) ->
                    val lastEntry = entries.maxByOrNull { it.startedAt }
                    StudyLogRow(name = name.ifBlank { "이름 없는 집중" }, seconds = entries.sumOf { it.seconds }.toLong(), note = lastEntry?.note.orEmpty(), tag = lastEntry?.tag.orEmpty())
                    Hairline()
                }
                StudyLogRow(name = "합계", seconds = todaySeconds, isTotal = true)
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }

    com.phonelock.app.ui.components.PullToRefreshBox(onRefresh = { refresh() }) {
    if (com.phonelock.app.ui.components.isTabletWidth()) {
        // 태블릿: 왼쪽은 히어로(오늘/지금 재는 시간 + 시작·정지), 오른쪽은 기록과 그래프.
        com.phonelock.app.ui.components.ResponsiveSplit(
            modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.lg, vertical = Spacing.md),
            left = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = Spacing.md)) {
                    accessibilityBanner()
                    heroContent()
                }
            },
            right = { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { extrasContent() } }
        )
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter).padding(top = Spacing.lg)
        ) {
            accessibilityBanner()
            heroContent()
            Spacer(Modifier.height(Spacing.xl))
            extrasContent()
        }
    }
    }
}

/**
 * "오늘 한눈에" — 캘린더 일정 완료 수와 일정표의 오늘 목표량을 큰 숫자 두 칸으로(오늘 집중 시간은 히어로가 이미 보여준다).
 * 새 데이터 없이 화면에 이미 있는 값만 모은다(순수 표시, 데스크탑판과 대칭).
 */
@Composable
private fun TodaySummaryStrip(today: LocalDate, todayTasks: List<CalendarTask>, calcTasks: List<CalcTask>) {
    val doneCount = todayTasks.count { it.status == "O" }
    val totalCount = todayTasks.size
    val todayCalcTargetTotal = calcTasks.sumOf { parseTodayCalcTarget(it, today) }
    StatRow {
        StatBlock(
            "캘린더 일정",
            if (totalCount == 0) "-" else "$doneCount/$totalCount",
            unit = if (totalCount == 0) null else "완료",
            modifier = Modifier.weight(1f)
        )
        VerticalHairline(Modifier.align(Alignment.CenterVertically))
        StatBlock(
            "일정표 오늘 목표",
            if (todayCalcTargetTotal > 0) fmtCalcSummaryNumber(todayCalcTargetTotal) else "-",
            modifier = Modifier.weight(1f)
        )
    }
}

/** 오늘 요일에 해당하는 계산기 업무의 목표량(mon~sun 중 하나)을 숫자로 파싱, 비어있거나 잘못된 값은 0. */
private fun parseTodayCalcTarget(task: CalcTask, today: LocalDate): Double {
    val jsDow = today.dayOfWeek.value % 7 // java DayOfWeek: 월=1..일=7 -> js식 일=0..토=6로 변환
    val raw = when (jsDow) {
        0 -> task.sun; 1 -> task.mon; 2 -> task.tue; 3 -> task.wed
        4 -> task.thu; 5 -> task.fri; else -> task.sat
    }
    return raw.trim().toDoubleOrNull() ?: 0.0
}

private fun fmtCalcSummaryNumber(n: Double): String =
    if (n == n.toLong().toDouble()) n.toLong().toString() else "%.1f".format(n)

/**
 * 집중 기록 한 줄 — 카드 대신 줄 단위(행 사이는 호출하는 쪽이 가는 선으로 나눈다). 시간은 오른쪽 끝 고정폭 숫자,
 * 회고(note)가 있으면 이름 아래 작게. 합계 줄은 굵게 + 강조색.
 */
@Composable
internal fun StudyLogRow(name: String, seconds: Long, isTotal: Boolean = false, note: String = "", tag: String = "") {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = if (isTotal) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
            if (tag.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                formatHmsLog(seconds),
                style = if (isTotal) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.W600),
                color = if (isTotal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                softWrap = false
            )
        }
        if (note.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 92차: 최근 7일(오늘 포함) 집중 시간 막대그래프 — 144차: 지난 날은 중립색, 오늘만 강조색. 처음 보일 때 막대가
 * 바닥에서 자라 올라온다(성능 모드에선 바로 그 높이). 데스크탑판과 대칭.
 */
@Composable
private fun WeekBarChart(days: List<Pair<LocalDate, Long>>) {
    val accent = MaterialTheme.colorScheme.primary
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
    val motion = LocalAppMotion.current
    val grow by androidx.compose.animation.core.animateFloatAsState(if (days.isEmpty()) 0f else 1f, motion.emphasized(), label = "weekGrow")
    val maxSeconds = (days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val dowLabels = listOf("월", "화", "수", "목", "금", "토", "일")
    Row(
        Modifier.fillMaxWidth().height(140.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        days.forEach { (date, seconds) ->
            val fraction = (seconds.toFloat() / maxSeconds.toFloat()).coerceIn(0f, 1f)
            val isToday = date == days.last().first // 마지막 칸이 "하루 시작 기준"의 오늘(refreshStreakAndWeek)
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    if (seconds > 0) formatHmsShort(seconds) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.5f)
                        .fillMaxHeight(0.7f * fraction.coerceAtLeast(0.03f))
                        .graphicsLayer {
                            scaleY = grow
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                        }
                        .background(if (isToday) accent else neutral, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    dowLabels[date.dayOfWeek.value - 1],
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isToday) FontWeight.W700 else FontWeight.W600,
                    color = if (isToday) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (maxSeconds <= 1L && days.all { it.second == 0L }) {
        Spacer(Modifier.height(Spacing.sm))
        Text("최근 7일간 기록된 집중 시간이 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 92차: 오늘 분야(태그)별 집중 시간 도넛 + 범례. 144차: 색은 팔레트의 `categorical`(테마와 무관한 밝은 색 6개, 미니멀 모드면 먹색 농도 차이), 범례는 이름과 시간을 한 줄씩 정렬. 데스크탑판과 대칭.
 */
@Composable
private fun SubjectPieChart(subjects: List<Pair<String, Long>>) {
    val p = LocalPhoneLockPalette.current
    val colors = p.categorical
    val total = subjects.sumOf { it.second }.coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(104.dp)) {
            val strokeWidth = 14.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
            var startAngle = -90f
            subjects.forEachIndexed { idx, (_, seconds) ->
                val sweep = 360f * (seconds.toFloat() / total.toFloat())
                drawArc(
                    color = colors[idx % colors.size],
                    startAngle = startAngle, sweepAngle = (sweep - 1.5f).coerceAtLeast(0.5f), useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokeWidth)
                )
                startAngle += sweep
            }
        }
        Spacer(Modifier.width(Spacing.lg))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            subjects.take(6).forEachIndexed { idx, (name, seconds) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(colors[idx % colors.size], CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(formatHmsShort(seconds), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

private fun formatHmsShort(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
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
                            androidx.compose.material3.Icon(Icons.Filled.Close, contentDescription = "빼기", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 타이머를 끄지 않고 지금 재고 있는 공부 일정만 바꾸는 다이얼로그(126차, 사용자 요청) — 타이머 탭과
 * 공부 잠금 화면([StudyLockActivity])이 같은 것을 쓴다. 시작 전 입력칸과 같은 규칙으로 "오늘 캘린더
 * 일정 중에서 고르거나 직접 입력"이 둘 다 되게 한다. 확인을 누르면 호출부가 `timerChangeTask`를
 * 부르고, 거기서 지금까지 잰 구간은 바꾸기 전 이름으로 기록에 적립된다.
 */
@Composable
internal fun StudyTaskChangeDialog(
    todayTasks: List<CalendarTask>,
    currentTaskName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentTaskName) }
    LedgerAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("집중할 일정 변경") },
        text = {
            Column {
                Text(
                    "지금까지 잰 시간은 \"${currentTaskName.ifBlank { "이름 없는 집중" }}\" 기록으로 남고, " +
                        "새 일정부터 다시 잽니다. 타이머는 멈추지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("집중할 일정") },
                    placeholder = { Text("예: 수학, 독서, 운동 (비워두면 이름 없는 집중)") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (todayTasks.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "오늘 캘린더 일정",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    // 오늘 일정이 많으면 다이얼로그가 화면을 넘지 않게 목록만 스크롤시킨다.
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        todayTasks.forEach { t ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { text = t.name },
                                shape = MaterialTheme.shapes.small,
                                color = if (t.name == text) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    1.dp,
                                    if (t.name == text) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                    else MaterialTheme.colorScheme.outline
                                )
                            ) {
                                Text(
                                    taskDropdownLabel(t),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.trim() != currentTaskName
            ) { Text("변경") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

private fun taskDropdownLabel(task: CalendarTask): String {
    val done = if (task.status == "O") " · 완료" else ""
    return "${task.name}$done · ${task.passIndex + 1}회차"
}

internal fun formatHmsLog(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d".format(h, m, s)
}
