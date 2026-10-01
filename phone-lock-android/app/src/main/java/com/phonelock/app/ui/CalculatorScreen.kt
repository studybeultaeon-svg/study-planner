package com.phonelock.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.phonelock.shared.calc.CalcEngine
import com.phonelock.app.data.CalcSavedItem
import com.phonelock.app.data.*
import com.phonelock.app.data.CalcTask
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.util.Locale

// 85차(사용자 요청): 일요일이 맨 앞이던 순서를 월~일로 변경 — dayValues 맵 키(0=일~6=토, CalcEngine.jsDow와
// 동일)는 그대로 두고, 화면에 훑는 순서만 이 리스트로 바꾼다.
private val DAY_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)
private val DAY_LABELS = arrayOf("일", "월", "화", "수", "목", "금", "토")

/**
 * 네이티브 계산기(3단계). 데스크탑판 CalculatorScreen.kt와 로직을 대칭으로 유지 — 업무 입력 카드 →
 * 계산(CalcEngine) → 결과(진척도/페이스/완료예상일) → 저장(폴더 트리 포함). 캘린더 연동은 제외
 * (DECISIONS.md 참고).
 */
@Composable
fun CalculatorScreen(repository: PhoneLockRepository) {
    var subTab by remember { mutableStateOf(0) }
    var tasks by remember { mutableStateOf<List<CalcTask>>(emptyList()) }
    var results by remember { mutableStateOf<List<Pair<CalcTask, CalcEngine.CalcOutcome>>>(emptyList()) }
    var savedCount by remember { mutableStateOf(0) }
    var savedRefreshTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // 98차(온라인/오프라인 모드): 오프라인이면 네트워크 타임아웃만 기다리게 되므로 아예 건너뛴다.
        if (!repository.isEffectivelyOffline()) repository.syncCalculatorFromFirebase()
        var t = repository.getCalcTasks()
        if (t.isEmpty()) { repository.addCalcTask(); t = repository.getCalcTasks() }
        tasks = t
        savedCount = repository.getCalcSaved().size
    }

    val onChanged: () -> Unit = { scope.launch { tasks = repository.getCalcTasks() } }
    val onCalculate: () -> Unit = {
        // 141차: 남은 일수·필요 페이스도 "하루 시작 기준"의 오늘부터 센다(공부 알림 StudyAlertChecker와 같은 기준).
        val today = java.time.LocalDate.parse(repository.todayCalendarDateKey())
        results = tasks.map { it to CalcEngine.calculate(it.toCalcInput(), today) }
        subTab = 1
    }
    val onSaved: () -> Unit = { scope.launch { savedCount = repository.getCalcSaved().size } }

    // 98차(사용자 요청): 당겨서 새로고침 — 서버 최신 상태를 다시 받아온다.
    com.phonelock.app.ui.components.PullToRefreshBox(onRefresh = {
        if (!repository.isEffectivelyOffline()) repository.syncCalculatorFromFirebase()
        tasks = repository.getCalcTasks()
        savedCount = repository.getCalcSaved().size
    }) {
    Column(Modifier.fillMaxSize()) {
        if (com.phonelock.app.ui.components.isTabletWidth()) {
            // 83차: 태블릿은 데스크탑 CalculatorScreen.kt와 같은 좌(입력)/우(결과) 분할 — 입력/결과를
            // 탭으로 나누지 않고 동시에 보여준다. "저장됨"만 별도 탭으로 유지(데스크탑도 입력 옆 서브탭).
            com.phonelock.app.ui.components.SegmentedTabs(
                listOf("계산기", "저장됨 $savedCount"),
                if (subTab == 2) 1 else 0,
                { i -> if (i == 1) { subTab = 2; savedRefreshTick++ } else subTab = 0 },
                Modifier.padding(horizontal = Spacing.lg).padding(top = Spacing.md).widthIn(max = 420.dp)
            )
            Spacer(Modifier.height(Spacing.sm))
            if (subTab == 2) {
                CalcSavedTab(repository = repository, refreshTick = savedRefreshTick, onChanged = { savedRefreshTick++; onSaved(); onChanged() })
            } else {
                com.phonelock.app.ui.components.ResponsiveSplit(
                    modifier = Modifier.weight(1f),
                    left = { CalcInputTab(repository = repository, tasks = tasks, onChanged = onChanged, onCalculate = onCalculate) },
                    right = { CalcResultTab(repository = repository, results = results, onSaved = onSaved) }
                )
            }
        } else {
            com.phonelock.app.ui.components.SegmentedTabs(
                listOf("입력", "결과", "저장됨 $savedCount"),
                subTab,
                { i -> subTab = i; if (i == 2) savedRefreshTick++ },
                Modifier.padding(horizontal = Spacing.gutter).padding(top = Spacing.md)
            )
            Spacer(Modifier.height(Spacing.sm))

            when (subTab) {
                0 -> CalcInputTab(repository = repository, tasks = tasks, onChanged = onChanged, onCalculate = onCalculate)
                1 -> CalcResultTab(repository = repository, results = results, onSaved = onSaved)
                // 98차 버그 수정: "저장됨" 탭에서 불러오기(loadCalcSavedItemAsDraft)해도 저장됨 목록만
                // 새로고침되고 "입력" 탭의 draft 목록(tasks)은 안 갱신돼서, 다른 탭 갔다 오거나 앱을
                // 재시작해야 보이던 버그 — onChanged()도 함께 호출해 draft 목록을 즉시 갱신한다.
                2 -> CalcSavedTab(repository = repository, refreshTick = savedRefreshTick, onChanged = { savedRefreshTick++; onSaved(); onChanged() })
            }
        }
    }
    }
}

private fun CalcTask.toCalcInput() = CalcEngine.CalcInput(
    name = name, qty = qty, unit = unit, progress = progress, start = start, dday = dday,
    mon = mon, tue = tue, wed = wed, thu = thu, fri = fri, sat = sat, sun = sun,
    holidays = if (holidaysCsv.isBlank()) emptyList() else holidaysCsv.split(",")
)

/**
 * 하단 액션 버튼(추가/계산/초기화)은 스크롤 영역 밖(weight 없는 고정 Column)에 둬서 업무가 많아도
 * 항상 보이게 한다 — 데스크탑판과 동일한 이유(31차 이후 세션에서 지적된 버그, HANDOFF.md 참고).
 * 146차: 업무마다 흰 판 → 가는 선으로 나뉜 줄(접으면 목록 한 줄, 펴면 입력칸), 아래 버튼은 "계산하기" 하나만 크게.
 */
@Composable
private fun CalcInputTab(
    repository: PhoneLockRepository,
    tasks: List<CalcTask>,
    onChanged: () -> Unit,
    onCalculate: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val collapsedKeys = remember { mutableStateOf(setOf<Long>()) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter)) {
            if (tasks.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    com.phonelock.app.ui.components.Overline("업무 ${tasks.size}개", Modifier.weight(1f))
                    TextButton(onClick = { collapsedKeys.value = emptySet() }) { Text("모두 펴기", maxLines = 1, softWrap = false) }
                    TextButton(onClick = { collapsedKeys.value = tasks.map { it.id }.toSet() }) { Text("모두 접기", maxLines = 1, softWrap = false) }
                }
            }
            tasks.forEachIndexed { index, task ->
                com.phonelock.app.ui.components.Hairline()
                CalcTaskCard(
                    task = task,
                    isFirst = index == 0,
                    isLast = index == tasks.lastIndex,
                    collapsed = task.id in collapsedKeys.value,
                    onToggleCollapse = {
                        collapsedKeys.value = if (task.id in collapsedKeys.value) collapsedKeys.value - task.id else collapsedKeys.value + task.id
                    },
                    onSave = { updated -> scope.launch { repository.updateCalcTask(updated); onChanged() } },
                    onDelete = { scope.launch { repository.removeCalcTask(task); onChanged() } },
                    onMoveUp = { scope.launch { repository.moveCalcTaskOrder(task, -1); onChanged() } },
                    onMoveDown = { scope.launch { repository.moveCalcTaskOrder(task, 1); onChanged() } }
                )
            }
            if (tasks.isNotEmpty()) com.phonelock.app.ui.components.Hairline()
            Spacer(Modifier.height(Spacing.md))
        }
        com.phonelock.app.ui.components.Hairline()
        Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.sm)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { scope.launch { repository.addCalcTask(); onChanged() } }) {
                    androidx.compose.material3.Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("업무 추가", maxLines = 1, softWrap = false)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { scope.launch { repository.resetCalcTasks(); onChanged() } }) {
                    Text("입력 초기화", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
            Button(onClick = onCalculate, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("계산하기", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CalcTaskCard(
    task: CalcTask,
    isFirst: Boolean,
    isLast: Boolean,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onSave: (CalcTask) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var name by remember(task.id) { mutableStateOf(task.name) }
    var qty by remember(task.id) { mutableStateOf(task.qty) }
    var unit by remember(task.id) { mutableStateOf(task.unit) }
    var progress by remember(task.id) { mutableStateOf(task.progress) }
    var start by remember(task.id) { mutableStateOf(task.start) }
    var dday by remember(task.id) { mutableStateOf(task.dday) }
    val dayValues = remember(task.id) {
        mutableStateOf(mapOf(0 to task.sun, 1 to task.mon, 2 to task.tue, 3 to task.wed, 4 to task.thu, 5 to task.fri, 6 to task.sat))
    }
    var holidaysText by remember(task.id) { mutableStateOf(task.holidaysCsv) }
    var passCount by remember(task.id) { mutableStateOf(task.passCount) }
    var passIntervals by remember(task.id) {
        mutableStateOf(com.phonelock.shared.calc.PassSchedule.parsePassIntervals(task.passIntervalsCsv, task.passCount))
    }
    var multiPassUsageEnabled by remember(task.id) { mutableStateOf(task.multiPassUsageEnabled) }
    var menuOpen by remember(task.id) { mutableStateOf(false) }

    fun persist() {
        val d = dayValues.value
        onSave(
            task.copy(
                name = name, qty = qty, unit = unit, progress = progress, start = start, dday = dday,
                mon = d[1] ?: "", tue = d[2] ?: "", wed = d[3] ?: "", thu = d[4] ?: "", fri = d[5] ?: "", sat = d[6] ?: "", sun = d[0] ?: "",
                holidaysCsv = CalcEngine.parseHolidaysInput(holidaysText).joinToString(","),
                passCount = passCount, passIntervalsCsv = passIntervals.joinToString(","),
                multiPassUsageEnabled = multiPassUsageEnabled
            )
        )
    }

    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
        // 머리 줄 — 접기/펴기 + 이름(접으면 분량·마감도) + 더보기(순서·삭제).
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleCollapse) {
                androidx.compose.material3.Icon(
                    if (collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (collapsed) "펴기" else "접기",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(Modifier.weight(1f).clickable(onClick = onToggleCollapse)) {
                Text(name.ifBlank { "새 업무" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (collapsed) {
                    Text(
                        listOf("${qty.ifBlank { "0" }}$unit", dday.takeIf { it.isNotBlank() }?.let { "$it 마감" }).filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    androidx.compose.material3.Icon(Icons.Filled.MoreVert, contentDescription = "더보기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!isFirst) DropdownMenuItem(text = { Text("위로") }, onClick = { menuOpen = false; onMoveUp() })
                    if (!isLast) DropdownMenuItem(text = { Text("아래로") }, onClick = { menuOpen = false; onMoveDown() })
                    DropdownMenuItem(text = { Text("삭제", color = MaterialTheme.colorScheme.error) }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
        if (!collapsed) {
            Spacer(Modifier.height(Spacing.xs))
            OutlinedTextField(
                value = name, onValueChange = { name = it; persist() },
                label = { Text("업무 이름") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(12.dp), textStyle = com.phonelock.app.ui.components.calcFieldTextStyle()
            )

            CalcFieldGroupHeader("기본 정보")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                com.phonelock.app.ui.components.NumberStepperField(value = qty, onValueChange = { qty = it; persist() }, label = "전체 분량", modifier = Modifier.weight(1f))
                OutlinedTextField(value = unit, onValueChange = { unit = it; persist() }, label = { Text("단위") }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(12.dp), textStyle = com.phonelock.app.ui.components.calcFieldTextStyle())
            }
            Spacer(Modifier.height(Spacing.xs))
            com.phonelock.app.ui.components.NumberStepperField(value = progress, onValueChange = { progress = it; persist() }, label = "지금까지 한 양", modifier = Modifier.fillMaxWidth())

            CalcFieldGroupHeader("기간")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                com.phonelock.app.ui.components.DatePickerField(value = start, onValueChange = { start = it; persist() }, label = "시작일", modifier = Modifier.weight(1f))
                com.phonelock.app.ui.components.DatePickerField(value = dday, onValueChange = { dday = it; persist() }, label = "마감일", modifier = Modifier.weight(1f))
            }

            CalcFieldGroupHeader("요일별 목표")
            // 85차 1차: 화살표로 조절 가능 + 한 줄에 7칸 요청으로 weight(1f) 단일 Row를 썼는데, 실기기
            // 폰 폭에서는 7등분이 너무 좁아 라벨(요일 글자)과 숫자가 화살표에 가려 안 보이는 문제가
            // 발생했다(85차 2차, 사용자 실기기 확인) — 4+3 두 줄로 나눠 칸당 폭을 넉넉히 확보한다.
            val (weekdays, weekend) = DAY_ORDER.take(4) to DAY_ORDER.drop(4)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                weekdays.forEach { d ->
                    com.phonelock.app.ui.components.NumberStepperField(
                        value = dayValues.value[d] ?: "",
                        onValueChange = { v -> dayValues.value = dayValues.value.toMutableMap().apply { put(d, v) }; persist() },
                        label = DAY_LABELS[d],
                        centerValue = true,
                        overlayStepper = true,
                        stepperSize = 16.dp,
                        stepperIconSize = 11.dp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                weekend.forEach { d ->
                    com.phonelock.app.ui.components.NumberStepperField(
                        value = dayValues.value[d] ?: "",
                        onValueChange = { v -> dayValues.value = dayValues.value.toMutableMap().apply { put(d, v) }; persist() },
                        label = DAY_LABELS[d],
                        centerValue = true,
                        overlayStepper = true,
                        stepperSize = 16.dp,
                        stepperIconSize = 11.dp,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(Spacing.xs))
            OutlinedTextField(
                value = holidaysText, onValueChange = { holidaysText = it; persist() },
                label = { Text("휴일 제외 날짜 (쉼표로 구분)") },
                placeholder = { Text("2026-01-01,2026-01-05") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(12.dp), textStyle = com.phonelock.app.ui.components.calcFieldTextStyle()
            )

            CalcFieldGroupHeader("반복")
            com.phonelock.app.ui.components.ToggleRow(
                title = "반복 사용",
                description = if (multiPassUsageEnabled) "캘린더에 연동할 때 몇 번 반복할지" else "캘린더엔 1회차만 만듭니다.",
                checked = multiPassUsageEnabled,
                onCheckedChange = { multiPassUsageEnabled = it; persist() }
            )
            if (multiPassUsageEnabled) {
                Spacer(Modifier.height(Spacing.xs))
                com.phonelock.app.ui.components.NumberStepperField(
                    value = passCount.toString(),
                    onValueChange = { text ->
                        val newCount = (text.toIntOrNull() ?: passCount)
                            .coerceIn(com.phonelock.shared.calc.PassSchedule.MIN_PASS_COUNT, com.phonelock.shared.calc.PassSchedule.MAX_PASS_COUNT)
                        passCount = newCount
                        passIntervals = com.phonelock.shared.calc.PassSchedule.parsePassIntervals(passIntervals.joinToString(","), newCount)
                        persist()
                    },
                    label = "반복 횟수",
                    min = com.phonelock.shared.calc.PassSchedule.MIN_PASS_COUNT,
                    max = com.phonelock.shared.calc.PassSchedule.MAX_PASS_COUNT,
                    modifier = Modifier.width(160.dp)
                )
                Spacer(Modifier.height(Spacing.xs))
                Text("회차별 간격(일)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                // 회독 수가 늘어나면(최대 8이면 간격칸 7개) 고정 Row는 화면 폭을 넘어가 찌부러진다(83차 발견) —
                // FlowRow로 넘치면 자동 줄바꿈, 칸 자체 폭도 줄여서 한 줄에 더 많이 들어가게 함.
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    passIntervals.forEachIndexed { i, days ->
                        com.phonelock.app.ui.components.NumberStepperField(
                            value = days.toString(),
                            onValueChange = { text ->
                                val newDays = (text.toIntOrNull() ?: days).coerceIn(1, 90)
                                passIntervals = passIntervals.toMutableList().also { it[i] = newDays }
                                persist()
                            },
                            label = "${i + 1}→${i + 2}회차",
                            min = 1, max = 90,
                            centerValue = true,
                            modifier = Modifier.width(100.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xs))
        }
    }
}

/** 업무 입력 안에서 묶음을 나누는 작은 라벨(144차 Ledger Overline) — 입력칸이 주인공이 되게 조용하게. */
@Composable
private fun CalcFieldGroupHeader(title: String) {
    com.phonelock.app.ui.components.Overline(title, Modifier.padding(top = Spacing.md, bottom = 6.dp))
}

@Composable
private fun CalcResultTab(repository: PhoneLockRepository, results: List<Pair<CalcTask, CalcEngine.CalcOutcome>>, onSaved: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val collapsedKeys = remember { mutableStateOf(setOf<Long>()) }
    var savedAllCount by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter)) {
        if (results.isEmpty()) {
            Text(
                "입력에서 업무를 적고 계산하기를 누르세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
        }
        results.forEach { (_, outcome) ->
            if (outcome is CalcEngine.CalcOutcome.Error) {
                com.phonelock.app.ui.components.NoticeStrip(outcome.message, modifier = Modifier.padding(vertical = Spacing.xs))
            }
        }
        val successes = results.mapNotNull { (task, outcome) -> if (outcome is CalcEngine.CalcOutcome.Success) task to outcome else null }
        if (successes.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { collapsedKeys.value = emptySet() }) { Text("모두 펴기", maxLines = 1, softWrap = false) }
                TextButton(onClick = { collapsedKeys.value = successes.map { it.first.id }.toSet() }) { Text("모두 접기", maxLines = 1, softWrap = false) }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        scope.launch {
                            successes.forEach { (task, outcome) -> repository.saveCalcResult(task, outcome.result) }
                            savedAllCount = successes.size
                            onSaved()
                        }
                    }
                ) { Text(savedAllCount?.let { "${it}개 저장됨" } ?: "전체 저장", maxLines = 1, softWrap = false) }
            }
        }
        successes.forEach { (task, outcome) ->
            // 웹앱 .result-block { animation: fadeIn .3s ease both }
            val visibleState = remember(task.id) { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(
                visibleState = visibleState,
                enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 8 }
            ) {
                Column {
                    com.phonelock.app.ui.components.Hairline()
                    CalcResultCard(
                        result = outcome.result,
                        collapsed = task.id in collapsedKeys.value,
                        onToggleCollapse = {
                            collapsedKeys.value = if (task.id in collapsedKeys.value) collapsedKeys.value - task.id else collapsedKeys.value + task.id
                        },
                        onSave = { scope.launch { repository.saveCalcResult(task, outcome.result); onSaved() } },
                        onCopy = { clipboard.setText(AnnotatedString(summaryText(outcome.result))) }
                    )
                }
            }
        }
        if (successes.isNotEmpty()) com.phonelock.app.ui.components.Hairline()
        Spacer(Modifier.height(Spacing.md))
    }
}

private fun fmtNum(n: Double): String = Math.round(n).toString()
private fun fmtDec(n: Double): String {
    val r = Math.round(n * 100) / 100.0
    return if (r == Math.floor(r)) r.toLong().toString() else String.format(Locale.KOREA, "%.2f", r).trimEnd('0').trimEnd('.')
}

/** 웹앱 fmtDate(d.toLocaleDateString('ko-KR',{month:'long',day:'numeric'}))와 동일한 "9월 4일" 형식. */
private fun fmtKoreanDate(date: java.time.LocalDate?): String = if (date == null) "10년 이상" else "${date.monthValue}월 ${date.dayOfMonth}일"

private fun summaryText(r: CalcEngine.CalcResult): String {
    val period = "기간: ${r.startDate} ~ ${r.ddayDate} (${r.totalDays}일)" + if (r.holidayCount > 0) " · 휴일 제외 ${r.holidayCount}일" else ""
    val progressLine = "진척도: ${fmtNum(r.progress)}${r.unit} / ${fmtNum(r.qty)}${r.unit} (${r.progressPct}%) · 남은 양 ${fmtNum(r.remaining)}${r.unit}"
    val verdict = if (r.enough) "✅ 현재 페이스로 충분 · 완료 예상 ${fmtKoreanDate(r.finishDate)}"
    else "⚠️ 페이스 부족 (약 ${fmtDec(r.multiplier)}배 증가 필요) · 현재 페이스 완료 예상 ${fmtKoreanDate(r.finishDate)}"
    return "📚 ${r.name}\n$period\n$progressLine\n$verdict"
}

/** 웹앱 diffLabel() — 완료 예상일과 마감일 차이(146차: 이모지 없이 글자색으로). null이면 표시 안 함(10년 이상인 경우). */
@Composable
private fun diffBadge(diffDays: Int?): Pair<String, Color>? {
    if (diffDays == null) return null
    val palette = com.phonelock.app.ui.theme.LocalPhoneLockPalette.current
    return when {
        diffDays == 0 -> "딱 마감일" to MaterialTheme.colorScheme.primary
        diffDays < 0 -> "마감 ${-diffDays}일 전" to palette.success
        else -> "마감 ${diffDays}일 초과" to MaterialTheme.colorScheme.error
    }
}

/**
 * 계산 결과 하나 — 146차: 테두리 카드 + 그라디언트 막대 → 기준 화면과 같은 문법(작은 라벨 · 큰 숫자 · 얇은 진행선 ·
 * 한 줄 알림 띠). 진척도 %가 이 블록의 주인공이고, 페이스 표와 판정은 펼쳤을 때만 보인다.
 */
@Composable
private fun CalcResultCard(
    result: CalcEngine.CalcResult,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit
) {
    var saved by remember(result) { mutableStateOf(false) }
    val palette = com.phonelock.app.ui.theme.LocalPhoneLockPalette.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(Modifier.fillMaxWidth().clickable(onClick = onToggleCollapse).padding(vertical = Spacing.md)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                result.name,
                style = MaterialTheme.typography.titleMedium,
                color = onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(result.ddayLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
            androidx.compose.material3.Icon(
                if (collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (collapsed) "펴기" else "접기",
                tint = muted,
                modifier = Modifier.padding(start = Spacing.xs).size(20.dp)
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            com.phonelock.app.ui.components.BigNumber(
                "${result.progressPct}",
                unit = "%",
                style = MaterialTheme.typography.displaySmall,
                color = if (result.enough) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${fmtNum(result.progress)} / ${fmtNum(result.qty)}${result.unit} · 남은 ${fmtNum(result.remaining)}${result.unit}",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        com.phonelock.app.ui.components.ProgressLine(
            result.progressPct / 100f,
            color = if (result.enough) palette.fillGood else palette.fillBad
        )

        if (!collapsed) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                "${result.startDate} ~ ${result.ddayDate} · ${result.totalDays}일" + if (result.holidayCount > 0) " · 휴일 제외 ${result.holidayCount}일" else "",
                style = MaterialTheme.typography.bodySmall,
                color = muted
            )
            Spacer(Modifier.height(Spacing.md))
            com.phonelock.app.ui.components.Overline(if (result.enough) "요일별 페이스" else "요일별 페이스 · 필요 페이스")
            Spacer(Modifier.height(4.dp))
            PaceTable(result)
            Spacer(Modifier.height(Spacing.md))

            val badge = diffBadge(result.finishDiffDays)
            val verdict = buildAnnotatedString {
                append(if (result.enough) "지금 페이스로 충분합니다" else "페이스를 약 ${fmtDec(result.multiplier)}배 올려야 합니다")
                append(" · 완료 예상 ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(fmtKoreanDate(result.finishDate)) }
                if (badge != null) {
                    append(" ")
                    withStyle(SpanStyle(color = badge.second, fontWeight = FontWeight.SemiBold)) { append("(${badge.first})") }
                }
            }
            VerdictStrip(verdict, good = result.enough)
            Spacer(Modifier.height(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                TextButton(onClick = { onSave(); saved = true }, enabled = !saved) {
                    Text(if (saved) "저장됨" else "이 업무 저장", maxLines = 1, softWrap = false)
                }
                TextButton(onClick = onCopy) { Text("결과 복사", maxLines = 1, softWrap = false) }
            }
        }
    }
}

/** 판정 한 줄 — [com.phonelock.app.ui.components.NoticeStrip]과 같은 모양(왼쪽 색 막대 + 옅은 바탕)에 강조 글자를 섞어 쓴다. */
@Composable
private fun VerdictStrip(text: AnnotatedString, good: Boolean) {
    val palette = com.phonelock.app.ui.theme.LocalPhoneLockPalette.current
    val bar = if (good) palette.success else palette.warning
    val bg = if (good) MaterialTheme.colorScheme.primaryContainer else palette.warningContainer
    Row(
        Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).background(bg, RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(bar, RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun PaceTable(result: CalcEngine.CalcResult) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val need = MaterialTheme.colorScheme.error

    Row(Modifier.fillMaxWidth()) {
        Text("", modifier = Modifier.weight(0.6f))
        DAY_ORDER.forEach { d -> Text(DAY_LABELS[d], modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.Center) }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("현재", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelSmall, color = muted)
        DAY_ORDER.forEach { d ->
            val v = result.dayGoals[d] ?: 0.0
            Text(
                if (v > 0) "${fmtNum(v)}${result.unit}" else "—",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                color = if (v > 0) onSurface else muted
            )
        }
    }
    if (!result.enough) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text("필요", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelSmall, color = need, fontWeight = FontWeight.Bold)
            DAY_ORDER.forEach { d ->
                val v = result.reqGoals[d] ?: 0.0
                Text(
                    if (v > 0) "${fmtNum(v)}${result.unit}" else "—",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = need, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun CalcSavedTab(repository: PhoneLockRepository, refreshTick: Int, onChanged: () -> Unit) {
    var saved by remember { mutableStateOf<List<CalcSavedItem>>(emptyList()) }
    var newFolderName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshTick) { saved = repository.getCalcSaved() }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.phonelock.app.ui.components.CompactField(
                value = newFolderName, onValueChange = { newFolderName = it },
                placeholder = "새 폴더 이름", modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Spacing.sm))
            TextButton(onClick = {
                if (repository.createCalcFolder(emptyList(), newFolderName)) { newFolderName = ""; onChanged() }
            }) { Text("폴더 만들기", maxLines = 1, softWrap = false) }
        }
        Spacer(Modifier.height(Spacing.sm))

        if (saved.isEmpty() && repository.getCalcFolderPaths().isEmpty()) {
            Text(
                "저장된 업무가 없습니다. 결과에서 저장해 보세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
        } else {
            com.phonelock.app.ui.components.Hairline()
            FolderTreeSection(repository = repository, parentPath = emptyList(), depth = 0, saved = saved, onChanged = onChanged)
        }

        if (saved.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            TextButton(onClick = { scope.launch { repository.clearAllCalcSaved(); onChanged() } }) { Text("전체 삭제", color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(Spacing.md))
    }
}

private fun decodeFolderPath(csv: String): List<String> = if (csv.isBlank()) emptyList() else csv.split("|")

@Composable
private fun FolderTreeSection(
    repository: PhoneLockRepository,
    parentPath: List<String>,
    depth: Int,
    saved: List<CalcSavedItem>,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val hereItems = saved.filter { decodeFolderPath(it.folderPathCsv) == parentPath }
    hereItems.forEach { item ->
        // 웹앱 .saved-item { animation: fadeIn .2s ease both }
        val visibleState = remember(item.id) { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 8 }
        ) {
            Column {
                SavedItemRow(
                    repository = repository, item = item, depth = depth,
                    allFolders = repository.getCalcFolderPaths(),
                    onChanged = onChanged
                )
                com.phonelock.app.ui.components.Hairline()
            }
        }
    }

    val subfolders = repository.getCalcSubfolderNames(parentPath)
    subfolders.forEach { name ->
        val subPath = parentPath + name
        val expanded = !repository.isCalcFolderCollapsed(subPath)
        var renaming by remember(parentPath, name) { mutableStateOf(false) }
        var renameText by remember(parentPath, name) { mutableStateOf(name) }
        var menuOpen by remember(parentPath, name) { mutableStateOf(false) }
        val countHere = saved.count { val fp = decodeFolderPath(it.folderPathCsv); fp.size >= subPath.size && fp.subList(0, subPath.size) == subPath }

        Row(
            Modifier.fillMaxWidth().padding(start = (depth * 16).dp, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { repository.toggleCalcFolderCollapsed(subPath); onChanged() }) {
                androidx.compose.material3.Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "접기" else "펴기",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (renaming) {
                com.phonelock.app.ui.components.CompactField(value = renameText, onValueChange = { renameText = it }, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { if (repository.renameCalcFolder(subPath, renameText)) { renaming = false; onChanged() } } }) { Text("저장") }
                TextButton(onClick = { renaming = false }) { Text("취소") }
            } else {
                androidx.compose.material3.Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(name, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(" $countHere", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        androidx.compose.material3.Icon(Icons.Filled.MoreVert, contentDescription = "폴더 메뉴", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("위로") }, onClick = { menuOpen = false; repository.moveCalcFolderOrder(parentPath, name, -1); onChanged() })
                        DropdownMenuItem(text = { Text("아래로") }, onClick = { menuOpen = false; repository.moveCalcFolderOrder(parentPath, name, 1); onChanged() })
                        DropdownMenuItem(text = { Text("이름 바꾸기") }, onClick = { menuOpen = false; renaming = true })
                        DropdownMenuItem(
                            text = { Text("폴더 삭제", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; scope.launch { repository.deleteCalcFolder(subPath); onChanged() } }
                        )
                    }
                }
            }
        }
        com.phonelock.app.ui.components.Hairline()
        if (expanded) {
            FolderTreeSection(repository = repository, parentPath = subPath, depth = depth + 1, saved = saved, onChanged = onChanged)
        }
    }
}

@Composable
private fun SavedItemRow(
    repository: PhoneLockRepository,
    item: CalcSavedItem,
    depth: Int,
    allFolders: List<List<String>>,
    onChanged: () -> Unit
) {
    var showFolderPicker by remember(item.id) { mutableStateOf(false) }
    var menuOpen by remember(item.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dayLine = "월${item.mon.ifBlank { "0" }} 화${item.tue.ifBlank { "0" }} 수${item.wed.ifBlank { "0" }} 목${item.thu.ifBlank { "0" }} " +
        "금${item.fri.ifBlank { "0" }} 토${item.sat.ifBlank { "0" }} 일${item.sun.ifBlank { "0" }}"
    val folderLabel = decodeFolderPath(item.folderPathCsv).lastOrNull() ?: "미분류"

    Column(Modifier.fillMaxWidth().padding(start = (depth * 16).dp).padding(vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(item.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            // 웹앱 .folder-popover — 인라인 목록 대신 버튼 근처에 뜨는 플로팅 팝오버로, 현재 폴더는
            // accent 색+굵게 강조(.folder-popover-item.active)한다.
            Box {
                TextButton(onClick = { showFolderPicker = !showFolderPicker }) {
                    androidx.compose.material3.Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(folderLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                }
                val curPath = decodeFolderPath(item.folderPathCsv)
                DropdownMenu(expanded = showFolderPicker, onDismissRequest = { showFolderPicker = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "미분류",
                                color = if (curPath.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (curPath.isEmpty()) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = { scope.launch { repository.moveCalcSavedItemToFolder(item, null); showFolderPicker = false; onChanged() } }
                    )
                    allFolders.forEach { path ->
                        val active = path == curPath
                        DropdownMenuItem(
                            text = {
                                Text(
                                    path.last(),
                                    modifier = Modifier.padding(start = ((path.size - 1) * 12).dp),
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { scope.launch { repository.moveCalcSavedItemToFolder(item, path); showFolderPicker = false; onChanged() } }
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    androidx.compose.material3.Icon(Icons.Filled.MoreVert, contentDescription = "더보기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("위로") }, onClick = { menuOpen = false; scope.launch { repository.moveCalcSavedItem(item, -1); onChanged() } })
                    DropdownMenuItem(text = { Text("아래로") }, onClick = { menuOpen = false; scope.launch { repository.moveCalcSavedItem(item, 1); onChanged() } })
                    DropdownMenuItem(
                        text = { Text("삭제", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; scope.launch { repository.deleteCalcSavedItem(item); onChanged() } }
                    )
                }
            }
        }
        Text(
            "${fmtNum(item.qty)}${item.unit} · ${item.start.ifBlank { "" }}${if (item.start.isNotBlank()) " 시작 · " else ""}${item.dday} 마감 · $dayLine · 저장 ${item.savedAt}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = { scope.launch { repository.loadCalcSavedItemAsDraft(item); onChanged() } },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 4.dp)
        ) { Text("입력으로 불러오기", maxLines = 1, softWrap = false) }
    }
}
