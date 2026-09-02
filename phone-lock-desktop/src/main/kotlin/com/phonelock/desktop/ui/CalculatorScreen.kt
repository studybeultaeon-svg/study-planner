package com.phonelock.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.shared.calc.CalcEngine
import com.phonelock.desktop.data.CalcSavedItem
import com.phonelock.desktop.data.*
import com.phonelock.desktop.data.CalcTask
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private val DAY_ORDER = listOf(0, 1, 2, 3, 4, 5, 6) // 일~토 (JS getDay 인덱스)
private val DAY_LABELS = arrayOf("일", "월", "화", "수", "목", "금", "토")

/**
 * 네이티브 계산기(3단계). 웹앱 index.html "할당량 계산기"를 이식 — 업무 입력 카드(요일별 목표 +
 * 휴일 제외) → 계산(CalcEngine, 웹앱 calculate()와 동일 로직) → 결과(진척도/페이스 비교/완료 예상일)
 * → 저장(폴더 트리 포함, 웹앱과 동일하게 사용자가 08-07 세션에 명시적으로 요청). 캘린더 연동
 * (linkedCalc/progressStep)은 이번 단계에서 제외 — DECISIONS.md 참고.
 *
 * 데스크탑판 레이아웃(31차 세션 재작성)은 웹앱의 사이드바 레이아웃(`.calc-layout`/`.calc-sidebar`/
 * `.calc-content`)을 그대로 따른다 — 왼쪽 좁은 칸(업무 입력/저장됨 서브탭)과 오른쪽 넓은 칸(결과)의
 * 좌:우 2:8 비율 분할. 이전(28차)엔 입력/결과/저장됨 3개를 동등한 탭으로 전환했는데, 결과가 별도
 * 탭 뒤에 숨어 있어 계산 후 결과 화면을 못 찾는 것처럼 보인다는 문제가 있어 웹앱 구조로 되돌렸다.
 */
@Composable
fun CalculatorScreen(repository: Repository) {
    var leftTab by remember { mutableStateOf(0) } // 0=업무 입력 1=저장됨
    var tasks by remember { mutableStateOf(repository.getCalcTasks()) }
    var results by remember { mutableStateOf<List<Pair<CalcTask, CalcEngine.CalcOutcome>>>(emptyList()) }
    var savedRefreshTick by remember { mutableStateOf(0) }

    fun refreshTasks() { tasks = repository.getCalcTasks() }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { repository.syncCalculatorFromFirebase() }
        refreshTasks()
        if (tasks.isEmpty()) { repository.addCalcTask(); refreshTasks() }
        savedRefreshTick++
    }

    Column(Modifier.fillMaxSize()) {
        Text("🧮 계산기", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(Spacing.md))
        com.phonelock.desktop.ui.components.ResponsiveSplit(
            modifier = Modifier.weight(1f),
            leftWeight = 2f,
            rightWeight = 8f,
            left = {
                // 왼쪽(비율 2): 업무 입력 / 저장됨 서브탭 — 웹앱 .calc-sidebar
                Column(Modifier.fillMaxSize()) {
                    TabRow(
                        selectedTabIndex = leftTab,
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground
                    ) {
                        Tab(selected = leftTab == 0, onClick = { leftTab = 0 }, text = { Text("✏️ 업무 입력") })
                        Tab(
                            selected = leftTab == 1,
                            onClick = { leftTab = 1; savedRefreshTick++ },
                            text = { Text("💾 저장됨 (${repository.getCalcSaved().size})") }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        when (leftTab) {
                            0 -> CalcInputTab(
                                repository = repository,
                                tasks = tasks,
                                onChanged = { refreshTasks() },
                                onCalculate = { results = tasks.map { it to CalcEngine.calculate(it.toCalcInput()) } }
                            )
                            1 -> CalcSavedTab(repository = repository, refreshTick = savedRefreshTick, onChanged = { savedRefreshTick++ })
                        }
                    }
                }
            },
            right = {
                // 오른쪽(비율 8): 결과 — 웹앱 .calc-content, 서브탭이 아니라 항상 보이는 영역
                Box(Modifier.fillMaxSize()) {
                    CalcResultTab(repository = repository, results = results, onSaved = { savedRefreshTick++ })
                }
            }
        )
    }
}

private fun CalcTask.toCalcInput() = CalcEngine.CalcInput(
    name = name, qty = qty, unit = unit, progress = progress, start = start, dday = dday,
    mon = mon, tue = tue, wed = wed, thu = thu, fri = fri, sat = sat, sun = sun, holidays = holidays
)

/**
 * 화면 폭에 맞춰 열 개수가 자동으로 늘고 주는 카드 그리드 — 웹앱 `.results-grid`의
 * `grid-template-columns: repeat(auto-fill, minmax(320px, 1fr))`를 그대로 이식(320dp 최소 폭
 * 기준 자동 배치). 예전엔 폭 상관없이 2열 고정이라 카드가 웹앱보다 훨씬 가로로 넓고 짧았다.
 */
@Composable
private fun <T> CardGrid(items: List<T>, modifier: Modifier = Modifier, card: @Composable (T) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 320.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // 웹앱 .result-block { animation: fadeIn .3s ease both } — 카드가 뜰 때 위로 살짝 밀리며 페이드인.
        items(items) { item ->
            val visibleState = remember(item) { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(
                visibleState = visibleState,
                enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 8 }
            ) {
                card(item)
            }
        }
    }
}

/**
 * 왼쪽 사이드바 폭에 맞춰 카드를 한 줄씩 세로로 쌓는다(웹앱 사이드바도 좁은 폭에선 1열로 접힘).
 * 하단 액션 버튼(추가/계산/초기화)은 스크롤 영역 밖(weight 없는 고정 Column)에 둬서 업무가 많아도
 * 항상 보이게 한다 — 이전엔 스크롤 리스트 맨 끝에 있어 업무가 많으면 스크롤해야만 눌렀다.
 */
@Composable
private fun CalcInputTab(
    repository: Repository,
    tasks: List<CalcTask>,
    onChanged: () -> Unit,
    onCalculate: () -> Unit
) {
    val collapsedKeys = remember { mutableStateOf(setOf<Int>()) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.sm)) {
            if (tasks.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { collapsedKeys.value = emptySet() }, modifier = Modifier.weight(1f)) { Text("모두 펴기") }
                    TextButton(onClick = { collapsedKeys.value = tasks.indices.toSet() }, modifier = Modifier.weight(1f)) { Text("모두 접기") }
                }
            }
            tasks.forEachIndexed { index, task ->
                CalcTaskCard(
                    task = task,
                    isFirst = index == 0,
                    isLast = index == tasks.lastIndex,
                    collapsed = index in collapsedKeys.value,
                    onToggleCollapse = {
                        collapsedKeys.value = if (index in collapsedKeys.value) collapsedKeys.value - index else collapsedKeys.value + index
                    },
                    onSave = { repository.updateCalcTask(index, it); onChanged() },
                    onDelete = { repository.removeCalcTask(index); onChanged() },
                    onMoveUp = { repository.moveCalcTaskOrder(index, -1); onChanged() },
                    onMoveDown = { repository.moveCalcTaskOrder(index, 1); onChanged() }
                )
                Spacer(Modifier.height(Spacing.sm))
            }
        }
        Column(Modifier.padding(horizontal = Spacing.sm)) {
            OutlinedButton(onClick = { repository.addCalcTask(); onChanged() }, modifier = Modifier.fillMaxWidth()) { Text("+ 업무 추가") }
            Spacer(Modifier.height(Spacing.xs))
            Button(onClick = onCalculate, modifier = Modifier.fillMaxWidth()) { Text("📊 계산하기") }
            Spacer(Modifier.height(Spacing.xs))
            OutlinedButton(onClick = { repository.resetCalcTasks(); onChanged() }, modifier = Modifier.fillMaxWidth()) { Text("↺ 입력 초기화") }
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

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
    var name by remember(task) { mutableStateOf(task.name) }
    var qty by remember(task) { mutableStateOf(task.qty) }
    var unit by remember(task) { mutableStateOf(task.unit) }
    var progress by remember(task) { mutableStateOf(task.progress) }
    var start by remember(task) { mutableStateOf(task.start) }
    var dday by remember(task) { mutableStateOf(task.dday) }
    val dayValues = remember(task) {
        mutableStateOf(mapOf(0 to task.sun, 1 to task.mon, 2 to task.tue, 3 to task.wed, 4 to task.thu, 5 to task.fri, 6 to task.sat))
    }
    var holidaysText by remember(task) { mutableStateOf(task.holidays.joinToString(",")) }
    var autoGenEnabled by remember(task) { mutableStateOf(task.autoGenEnabled) }
    var autoGenBatchSizeText by remember(task) { mutableStateOf(if (task.autoGenBatchSize > 0) task.autoGenBatchSize.toString() else "") }

    fun persist() {
        val d = dayValues.value
        onSave(
            task.copy(
                name = name, qty = qty, unit = unit, progress = progress, start = start, dday = dday,
                mon = d[1] ?: "", tue = d[2] ?: "", wed = d[3] ?: "", thu = d[4] ?: "", fri = d[5] ?: "", sat = d[6] ?: "", sun = d[0] ?: "",
                holidays = CalcEngine.parseHolidaysInput(holidaysText),
                autoGenEnabled = autoGenEnabled, autoGenBatchSize = autoGenBatchSizeText.toIntOrNull() ?: 0
            )
        )
    }

    SectionCard(name.ifBlank { "새 업무" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("▲", fontSize = 10.sp, modifier = Modifier.clickable(enabled = !isFirst, onClick = onMoveUp).padding(2.dp))
                Text("▼", fontSize = 10.sp, modifier = Modifier.clickable(enabled = !isLast, onClick = onMoveDown).padding(2.dp))
            }
            Text(
                if (collapsed) "▶" else "▼",
                modifier = Modifier.clickable(onClick = onToggleCollapse).padding(6.dp)
            )
            if (collapsed) {
                Text(
                    "${name.ifBlank { "새 업무" }} · ${qty.ifBlank { "0" }}${unit}",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium
                )
            } else {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; persist() },
                    label = { Text("업무 이름") }, modifier = Modifier.weight(1f), singleLine = true
                )
            }
            Spacer(Modifier.width(Spacing.xs))
            TextButton(onClick = onDelete) { Text("삭제") }
        }
        if (!collapsed) {
            Spacer(Modifier.height(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(value = qty, onValueChange = { qty = it; persist() }, label = { Text("총 할당량") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = unit, onValueChange = { unit = it; persist() }, label = { Text("단위") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Spacer(Modifier.height(Spacing.xs))
            OutlinedTextField(value = progress, onValueChange = { progress = it; persist() }, label = { Text("현재 진척도") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(value = start, onValueChange = { start = it; persist() }, label = { Text("시작 (YYYY-MM-DD)") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = dday, onValueChange = { dday = it; persist() }, label = { Text("마감 (YYYY-MM-DD)") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Spacer(Modifier.height(Spacing.xs))
            Text("요일별 목표", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DAY_ORDER.forEach { d ->
                    OutlinedTextField(
                        value = dayValues.value[d] ?: "",
                        onValueChange = { v -> dayValues.value = dayValues.value.toMutableMap().apply { put(d, v) }; persist() },
                        label = { Text(DAY_LABELS[d]) },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            OutlinedTextField(
                value = holidaysText, onValueChange = { holidaysText = it; persist() },
                label = { Text("휴일 제외 날짜 (쉼표로 구분, 예: 2026-01-01,2026-01-05)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(checked = autoGenEnabled, onCheckedChange = { autoGenEnabled = it; persist() })
                Spacer(Modifier.width(Spacing.xs))
                Text("캘린더 일정 자동 생성", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (autoGenEnabled) {
                Spacer(Modifier.height(Spacing.xs))
                OutlinedTextField(
                    value = autoGenBatchSizeText,
                    onValueChange = { v -> autoGenBatchSizeText = v.filter { it.isDigit() }; persist() },
                    label = { Text("한 번에 만들 배치 크기 (${unit.ifBlank { "단위" }})") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Text(
                    "완료 체크할 때마다 다음날에 \"업무명 N~M$unit\" 일정을 자동으로 만들고, 할당량 연동도 그대로 이어집니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CalcResultTab(repository: Repository, results: List<Pair<CalcTask, CalcEngine.CalcOutcome>>, onSaved: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("입력 탭에서 업무를 입력하고 계산하기를 눌러주세요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val collapsedKeys = remember { mutableStateOf(setOf<Int>()) }
    var savedAllCount by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.md)) {
        results.forEach { (_, outcome) ->
            if (outcome is CalcEngine.CalcOutcome.Error) {
                Text("⚠️ ${outcome.message}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = Spacing.xs))
            }
        }
        val successes = results.mapNotNull { (task, outcome) -> if (outcome is CalcEngine.CalcOutcome.Success) task to outcome else null }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { collapsedKeys.value = emptySet() }, modifier = Modifier.weight(1f)) { Text("모두 펴기") }
            TextButton(onClick = { collapsedKeys.value = successes.indices.toSet() }, modifier = Modifier.weight(1f)) { Text("모두 접기") }
            OutlinedButton(
                onClick = {
                    successes.forEach { (task, outcome) -> repository.saveCalcResult(task, outcome.result) }
                    savedAllCount = successes.size
                    onSaved()
                },
                modifier = Modifier.weight(1f)
            ) { Text("💾 전체 저장") }
        }
        savedAllCount?.let {
            Text("${it}개 저장됨", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Spacing.xs))
        }
        CardGrid(successes.withIndex().toList(), Modifier.weight(1f)) { (index, pair) ->
            val (task, outcome) = pair
            CalcResultCard(
                result = outcome.result,
                collapsed = index in collapsedKeys.value,
                onToggleCollapse = {
                    collapsedKeys.value = if (index in collapsedKeys.value) collapsedKeys.value - index else collapsedKeys.value + index
                },
                onSave = { repository.saveCalcResult(task, outcome.result); onSaved() },
                onCopy = { clipboard.setText(AnnotatedString(summaryText(outcome.result))) }
            )
        }
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

/** 웹앱 diffLabel() — 완료 예상일과 마감일 차이를 색 배지로. null이면 표시 안 함(10년 이상인 경우). */
@Composable
private fun DiffBadgeSpan(diffDays: Int?): Pair<String, Color>? {
    if (diffDays == null) return null
    return when {
        diffDays == 0 -> "딱 마감일 ✓" to MaterialTheme.colorScheme.primary
        diffDays < 0 -> "마감 ${-diffDays}일 전 ✅" to Color(0xFF34D399)
        else -> "마감 ${diffDays}일 초과 ⚠️" to Color(0xFFF87171)
    }
}

/**
 * 웹앱(index.html) `.result-block` 계산 결과 카드를 실제 CSS/JS 소스 기준으로 그대로 재현.
 * 핵심은 "카드 자체는 무채색, 색은 특정 요소에만" — 카드 배경/테두리는 항상 회색(surfaceVariant/
 * outline)이고, 색이 실제로 쓰이는 곳은 ① D-day 배지(파랑 고정, 상태와 무관) ② 진행바 채움(파랑→
 * 초록 그라디언트, 상태와 무관) ③ 페이스 표의 "필요⚠️" 행(빨강 고정) ④ 판정 배너의 배경+테두리
 * (충분=초록/부족=빨강, 배너 안 텍스트 자체는 무채색) ⑤ 완료예상일 옆 마감 초과/여유 배지, 이렇게
 * 5곳뿐이다. 제목·퍼센트·본문 텍스트는 전부 무채색 — 이전 버전들이 카드 전체를 상태색으로 물들이고
 * 제목/퍼센트까지 칠했던 건 전부 오독이었다(DECISIONS.md 참고할 정도로 반복 정정된 부분).
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
    val accent = MaterialTheme.colorScheme.primary
    val green = Color(0xFF34D399)
    val red = Color(0xFFF87171)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            // 헤더: 제목(무채색, 굵게) — D-day 배지(파랑 알약) + 접기 화살표(무채색)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    result.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = onSurface,
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Surface(shape = RoundedCornerShape(50), color = accent.copy(alpha = 0.15f)) {
                        Text(
                            "🗓️ ${result.ddayLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                    Text(if (collapsed) "▶" else "▼", color = muted, modifier = Modifier.clickable(onClick = onToggleCollapse))
                }
            }
            Spacer(Modifier.height(12.dp))

            // 진행바: 파랑→초록 고정 그라디언트(상태와 무관), 트랙은 무채색
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outline)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction = (result.progressPct / 100f).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(accent, green)))
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildAnnotatedString {
                    append("완료 ")
                    withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.SemiBold)) { append("${fmtNum(result.progress)}${result.unit}") }
                    append(" / 총 ")
                    withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.SemiBold)) { append("${fmtNum(result.qty)}${result.unit}") }
                    append(" · ")
                    withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.SemiBold)) { append("${result.progressPct}%") }
                    append(" · 남은 양 ")
                    withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.SemiBold)) { append("${fmtNum(result.remaining)}${result.unit}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted
            )

            if (!collapsed) {
                Spacer(Modifier.height(12.dp))
                Text(
                    buildAnnotatedString {
                        append("📆 계산 기간: ")
                        withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.SemiBold)) { append("${result.startDate} ~ ${result.ddayDate}") }
                        append(" (${result.totalDays}일)")
                        if (result.holidayCount > 0) {
                            append(" · 휴일 제외 ")
                            withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.SemiBold)) { append("${result.holidayCount}일") }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = muted
                )
                Spacer(Modifier.height(12.dp))

                // 요일별 페이스: 웹앱 .pace-section — 카드 안에 무채색 테두리로 한 번 더 감싼 박스
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            "요일별 페이스" + if (!result.enough) " — 필요 페이스 함께 표시" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = muted
                        )
                        Spacer(Modifier.height(4.dp))
                        PaceTable(result)
                    }
                }
                Spacer(Modifier.height(8.dp))

                // 판정 배너: 배경+테두리만 상태색(충분=초록/부족=빨강), 안의 텍스트는 무채색
                val bannerColor = if (result.enough) green else red
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = bannerColor.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, bannerColor.copy(alpha = 0.3f))
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            if (result.enough) "✅ 현재 페이스로 충분합니다"
                            else "⚠️ 페이스가 부족합니다 (약 ${fmtDec(result.multiplier)}배 증가 필요)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        val diffBadge = DiffBadgeSpan(result.finishDiffDays)
                        Text(
                            buildAnnotatedString {
                                if (result.enough) {
                                    append("기간 내 총 ${fmtNum(result.totalCapacity)}${result.unit} 소화 가능 · 완료 예상 ")
                                } else {
                                    append("기간 내 ${fmtNum(result.totalCapacity)}${result.unit} 소화 가능 / 남은 양 ${fmtNum(result.remaining)}${result.unit} · 현재 페이스 완료 예상 ")
                                }
                                withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.SemiBold)) { append(fmtKoreanDate(result.finishDate)) }
                                if (diffBadge != null) {
                                    append(" ")
                                    withStyle(SpanStyle(color = diffBadge.second, fontWeight = FontWeight.Bold)) { append(diffBadge.first) }
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = muted
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                // 버튼 2개: 둘 다 파란 알약(웹앱 .save-result-btn, 둘 다 같은 클래스), flex:1로 반반
                val btnColors = ButtonDefaults.outlinedButtonColors(containerColor = accent.copy(alpha = 0.1f), contentColor = accent)
                val btnBorder = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onSave(); saved = true },
                        enabled = !saved,
                        shape = RoundedCornerShape(7.dp),
                        colors = btnColors,
                        border = btnBorder,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (saved) "✅ 저장됨" else "💾 이 업무 저장") }
                    OutlinedButton(
                        onClick = onCopy,
                        shape = RoundedCornerShape(7.dp),
                        colors = btnColors,
                        border = btnBorder,
                        modifier = Modifier.weight(1f)
                    ) { Text("📋 결과 복사") }
                }
            }
        }
    }
}

@Composable
private fun PaceTable(result: CalcEngine.CalcResult) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val red = Color(0xFFF87171)

    Row(Modifier.fillMaxWidth()) {
        Text("", modifier = Modifier.weight(0.6f))
        DAY_ORDER.forEach { d -> Text(DAY_LABELS[d], modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.Center) }
    }
    Row(Modifier.fillMaxWidth()) {
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
        Row(Modifier.fillMaxWidth().background(red.copy(alpha = 0.06f))) {
            Text("필요⚠️", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelSmall, color = red, fontWeight = FontWeight.Bold)
            DAY_ORDER.forEach { d ->
                val v = result.reqGoals[d] ?: 0.0
                Text(
                    if (v > 0) "${fmtNum(v)}${result.unit}" else "—",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = red, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * "저장됨" 탭: 왼쪽 사이드바 폭(비율 2)엔 좌우 분할 폴더 탐색기가 들어갈 공간이 없어, 웹앱
 * 사이드바(`#saved-list`, 폴더 헤더 + 하위 항목을 한 목록에 세로로 나열)와 안드로이드판
 * `FolderTreeSection`과 동일하게 재귀적으로 세로 나열하는 폴더 트리로 되돌렸다(31차 세션).
 */
@Composable
private fun CalcSavedTab(repository: Repository, refreshTick: Int, onChanged: () -> Unit) {
    var saved by remember(refreshTick) { mutableStateOf(repository.getCalcSaved()) }
    var newFolderName by remember { mutableStateOf("") }

    fun refresh() {
        saved = repository.getCalcSaved()
        onChanged()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newFolderName, onValueChange = { newFolderName = it },
                label = { Text("새 폴더") }, modifier = Modifier.weight(1f), singleLine = true
            )
            Spacer(Modifier.width(Spacing.xs))
            OutlinedButton(onClick = { if (repository.createCalcFolder(emptyList(), newFolderName)) { newFolderName = ""; refresh() } }) { Text("생성") }
        }
        Spacer(Modifier.height(Spacing.sm))

        if (saved.isEmpty() && repository.getCalcFolderPaths().isEmpty()) {
            Text(
                "저장된 업무가 없습니다. 결과에서 계산 결과를 저장해 보세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            CalcFolderTreeSection(repository = repository, parentPath = emptyList(), depth = 0, saved = saved, onChanged = { refresh() })
        }

        if (saved.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            TextButton(onClick = { repository.clearAllCalcSaved(); refresh() }) { Text("전체 삭제", color = MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(Spacing.md))
    }
}

@Composable
private fun CalcFolderTreeSection(
    repository: Repository,
    parentPath: List<String>,
    depth: Int,
    saved: List<CalcSavedItem>,
    onChanged: () -> Unit
) {
    val hereItems = saved.filter { (it.folderPath ?: emptyList()) == parentPath }
    hereItems.forEach { item ->
        val idx = saved.indexOf(item)
        // 웹앱 .saved-item { animation: fadeIn .2s ease both }
        val visibleState = remember(item) { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 8 }
        ) {
            SavedItemRow(
                repository = repository, item = item, index = idx, depth = depth,
                allFolders = repository.getCalcFolderPaths(), onChanged = onChanged
            )
        }
        Spacer(Modifier.height(Spacing.xs))
    }

    val subfolders = repository.getCalcSubfolderNames(parentPath)
    subfolders.forEach { name ->
        val subPath = parentPath + name
        val expanded = !repository.isCalcFolderCollapsed(subPath)
        var renaming by remember(parentPath, name) { mutableStateOf(false) }
        var renameText by remember(parentPath, name) { mutableStateOf(name) }
        val countHere = saved.count { val fp = it.folderPath ?: emptyList(); fp.size >= subPath.size && fp.subList(0, subPath.size) == subPath }

        Row(
            Modifier.fillMaxWidth().padding(start = (depth * 16).dp, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("▲", fontSize = 10.sp, modifier = Modifier.clickable { repository.moveCalcFolderOrder(parentPath, name, -1); onChanged() }.padding(2.dp))
                Text("▼", fontSize = 10.sp, modifier = Modifier.clickable { repository.moveCalcFolderOrder(parentPath, name, 1); onChanged() }.padding(2.dp))
            }
            Text(if (expanded) "▼" else "▶", modifier = Modifier.clickable { repository.toggleCalcFolderCollapsed(subPath); onChanged() }.padding(4.dp))
            if (renaming) {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, modifier = Modifier.weight(1f), singleLine = true)
                TextButton(onClick = { if (repository.renameCalcFolder(subPath, renameText)) { renaming = false; onChanged() } }) { Text("저장") }
                TextButton(onClick = { renaming = false }) { Text("취소") }
            } else {
                Text("📁 $name ($countHere)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { renaming = true }) { Text("✏️") }
                TextButton(onClick = { repository.deleteCalcFolder(subPath); onChanged() }) { Text("✕") }
            }
        }
        if (expanded) {
            CalcFolderTreeSection(repository = repository, parentPath = subPath, depth = depth + 1, saved = saved, onChanged = onChanged)
        }
    }
}

@Composable
private fun SavedItemRow(
    repository: Repository,
    item: CalcSavedItem,
    index: Int,
    depth: Int,
    allFolders: List<List<String>>,
    onChanged: () -> Unit
) {
    var showFolderPicker by remember(item) { mutableStateOf(false) }
    val dayLine = "월${item.mon.ifBlank { "0" }} 화${item.tue.ifBlank { "0" }} 수${item.wed.ifBlank { "0" }} 목${item.thu.ifBlank { "0" }} " +
        "금${item.fri.ifBlank { "0" }} 토${item.sat.ifBlank { "0" }} 일${item.sun.ifBlank { "0" }}"

    Column(
        Modifier.fillMaxWidth().padding(start = (depth * 16).dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("▲", fontSize = 10.sp, modifier = Modifier.clickable { repository.moveCalcSavedItem(index, -1); onChanged() }.padding(2.dp))
                Text("▼", fontSize = 10.sp, modifier = Modifier.clickable { repository.moveCalcSavedItem(index, 1); onChanged() }.padding(2.dp))
            }
            Spacer(Modifier.width(Spacing.xs))
            Text(item.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            // 웹앱 .folder-popover — 인라인 목록 대신 버튼 근처에 뜨는 플로팅 팝오버로, 현재 폴더는
            // accent 색+굵게 강조(.folder-popover-item.active)한다.
            Box {
                TextButton(onClick = { showFolderPicker = !showFolderPicker }) {
                    Text("📁 ${item.folderPath?.lastOrNull() ?: "미분류"}", style = MaterialTheme.typography.labelSmall)
                }
                val curPath = item.folderPath ?: emptyList()
                DropdownMenu(expanded = showFolderPicker, onDismissRequest = { showFolderPicker = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "— 미분류",
                                color = if (curPath.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (curPath.isEmpty()) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = { repository.moveCalcSavedItemToFolder(index, null); showFolderPicker = false; onChanged() }
                    )
                    allFolders.forEach { path ->
                        val active = path == curPath
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "📁 ${path.last()}",
                                    modifier = Modifier.padding(start = ((path.size - 1) * 12).dp),
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = { repository.moveCalcSavedItemToFolder(index, path); showFolderPicker = false; onChanged() }
                        )
                    }
                }
            }
        }
        Text(
            "${fmtNum(item.qty)}${item.unit} · ${item.start.ifBlank { "" }}${if (item.start.isNotBlank()) " 시작 · " else ""}${item.dday} 마감 · $dayLine · 저장 ${item.savedAt}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.xs)) {
            TextButton(onClick = { repository.loadCalcSavedItemAsDraft(index); onChanged() }) { Text("✚ 불러오기") }
            TextButton(onClick = { repository.deleteCalcSavedItem(index); onChanged() }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
        }
    }
}
