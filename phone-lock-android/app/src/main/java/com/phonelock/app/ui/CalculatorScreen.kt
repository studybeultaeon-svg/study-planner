package com.phonelock.app.ui

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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.phonelock.app.calc.CalcEngine
import com.phonelock.app.data.CalcSavedItem
import com.phonelock.app.data.CalcTask
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.ui.components.SectionCard
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.util.Locale

private val DAY_ORDER = listOf(0, 1, 2, 3, 4, 5, 6)
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
        repository.syncCalculatorFromFirebase()
        var t = repository.getCalcTasks()
        if (t.isEmpty()) { repository.addCalcTask(); t = repository.getCalcTasks() }
        tasks = t
        savedCount = repository.getCalcSaved().size
    }

    Column(Modifier.fillMaxSize()) {
        Text("🧮 계산기", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(Spacing.md))
        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("입력") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("결과") })
            Tab(
                selected = subTab == 2,
                onClick = { subTab = 2; savedRefreshTick++ },
                text = { Text("저장됨 ($savedCount)") }
            )
        }
        Spacer(Modifier.height(Spacing.sm))

        when (subTab) {
            0 -> CalcInputTab(
                repository = repository,
                tasks = tasks,
                onChanged = { scope.launch { tasks = repository.getCalcTasks() } },
                onCalculate = {
                    results = tasks.map { it to CalcEngine.calculate(it.toCalcInput()) }
                    subTab = 1
                }
            )
            1 -> CalcResultTab(
                repository = repository, results = results,
                onSaved = { scope.launch { savedCount = repository.getCalcSaved().size } }
            )
            2 -> CalcSavedTab(repository = repository, refreshTick = savedRefreshTick, onChanged = { savedRefreshTick++; scope.launch { savedCount = repository.getCalcSaved().size } })
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
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.md)) {
            if (tasks.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { collapsedKeys.value = emptySet() }, modifier = Modifier.weight(1f)) { Text("모두 펴기") }
                    TextButton(onClick = { collapsedKeys.value = tasks.map { it.id }.toSet() }, modifier = Modifier.weight(1f)) { Text("모두 접기") }
                }
            }
            tasks.forEachIndexed { index, task ->
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
                Spacer(Modifier.height(Spacing.sm))
            }
        }
        Column(Modifier.padding(horizontal = Spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = { scope.launch { repository.addCalcTask(); onChanged() } }) { Text("+ 업무 추가") }
                OutlinedButton(onClick = { scope.launch { repository.resetCalcTasks(); onChanged() } }) { Text("입력 초기화") }
            }
            Spacer(Modifier.height(Spacing.sm))
            Button(onClick = onCalculate, modifier = Modifier.fillMaxWidth()) { Text("📊 계산하기") }
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

    fun persist() {
        val d = dayValues.value
        onSave(
            task.copy(
                name = name, qty = qty, unit = unit, progress = progress, start = start, dday = dday,
                mon = d[1] ?: "", tue = d[2] ?: "", wed = d[3] ?: "", thu = d[4] ?: "", fri = d[5] ?: "", sat = d[6] ?: "", sun = d[0] ?: "",
                holidaysCsv = CalcEngine.parseHolidaysInput(holidaysText).joinToString(",")
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
        }
    }
}

@Composable
private fun CalcResultTab(repository: PhoneLockRepository, results: List<Pair<CalcTask, CalcEngine.CalcOutcome>>, onSaved: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val collapsedKeys = remember { mutableStateOf(setOf<Long>()) }
    var savedAllCount by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.md)) {
        if (results.isEmpty()) {
            Text("입력 탭에서 업무를 입력하고 계산하기를 눌러주세요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        results.forEach { (_, outcome) ->
            if (outcome is CalcEngine.CalcOutcome.Error) {
                Text("⚠️ ${outcome.message}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = Spacing.xs))
            }
        }
        val successes = results.mapNotNull { (task, outcome) -> if (outcome is CalcEngine.CalcOutcome.Success) task to outcome else null }
        if (successes.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { collapsedKeys.value = emptySet() }, modifier = Modifier.weight(1f)) { Text("모두 펴기") }
                TextButton(onClick = { collapsedKeys.value = successes.map { it.first.id }.toSet() }, modifier = Modifier.weight(1f)) { Text("모두 접기") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            successes.forEach { (task, outcome) -> repository.saveCalcResult(task, outcome.result) }
                            savedAllCount = successes.size
                            onSaved()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("💾 전체 저장") }
            }
            savedAllCount?.let {
                Text("${it}개 저장됨", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Spacing.xs))
            }
            Spacer(Modifier.height(Spacing.sm))
        }
        successes.forEach { (task, outcome) ->
            // 웹앱 .result-block { animation: fadeIn .3s ease both }
            val visibleState = remember(task.id) { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(
                visibleState = visibleState,
                enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 8 }
            ) {
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
            Spacer(Modifier.height(Spacing.sm))
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
 * 웹앱(index.html) `.result-block` 계산 결과 카드를 실제 CSS/JS 소스 기준으로 재현 — 데스크탑판
 * CalcResultCard와 동일 스타일. 카드 자체는 무채색이고, 색은 D-day 배지(파랑)·진행바(파랑→초록
 * 그라디언트)·필요⚠️ 행(빨강)·판정 배너 배경+테두리(초록/빨강)·마감 배지, 이 5곳에만 쓰인다.
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

            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outline)) {
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

@Composable
private fun CalcSavedTab(repository: PhoneLockRepository, refreshTick: Int, onChanged: () -> Unit) {
    var saved by remember { mutableStateOf<List<CalcSavedItem>>(emptyList()) }
    var newFolderName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshTick) { saved = repository.getCalcSaved() }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newFolderName, onValueChange = { newFolderName = it },
                label = { Text("새 폴더 이름") }, modifier = Modifier.weight(1f), singleLine = true
            )
            Spacer(Modifier.width(Spacing.sm))
            Button(onClick = {
                if (repository.createCalcFolder(emptyList(), newFolderName)) { newFolderName = ""; onChanged() }
            }) { Text("폴더 생성") }
        }
        Spacer(Modifier.height(Spacing.sm))

        if (saved.isEmpty() && repository.getCalcFolderPaths().isEmpty()) {
            Text("저장된 업무가 없습니다. 결과 탭에서 계산 결과를 저장해 보세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
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
            SavedItemRow(
                repository = repository, item = item, depth = depth,
                allFolders = repository.getCalcFolderPaths(),
                onChanged = onChanged
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
        val countHere = saved.count { val fp = decodeFolderPath(it.folderPathCsv); fp.size >= subPath.size && fp.subList(0, subPath.size) == subPath }

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
                TextButton(onClick = { scope.launch { if (repository.renameCalcFolder(subPath, renameText)) { renaming = false; onChanged() } } }) { Text("저장") }
                TextButton(onClick = { renaming = false }) { Text("취소") }
            } else {
                Text("📁 $name ($countHere)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { renaming = true }) { Text("✏️") }
                TextButton(onClick = { scope.launch { repository.deleteCalcFolder(subPath); onChanged() } }) { Text("✕") }
            }
        }
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
    val scope = rememberCoroutineScope()
    val dayLine = "월${item.mon.ifBlank { "0" }} 화${item.tue.ifBlank { "0" }} 수${item.wed.ifBlank { "0" }} 목${item.thu.ifBlank { "0" }} " +
        "금${item.fri.ifBlank { "0" }} 토${item.sat.ifBlank { "0" }} 일${item.sun.ifBlank { "0" }}"
    val folderLabel = decodeFolderPath(item.folderPathCsv).lastOrNull() ?: "미분류"

    Column(
        Modifier.fillMaxWidth().padding(start = (depth * 16).dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("▲", fontSize = 10.sp, modifier = Modifier.clickable { scope.launch { repository.moveCalcSavedItem(item, -1); onChanged() } }.padding(2.dp))
                Text("▼", fontSize = 10.sp, modifier = Modifier.clickable { scope.launch { repository.moveCalcSavedItem(item, 1); onChanged() } }.padding(2.dp))
            }
            Spacer(Modifier.width(Spacing.xs))
            Text(item.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            // 웹앱 .folder-popover — 인라인 목록 대신 버튼 근처에 뜨는 플로팅 팝오버로, 현재 폴더는
            // accent 색+굵게 강조(.folder-popover-item.active)한다.
            Box {
                TextButton(onClick = { showFolderPicker = !showFolderPicker }) {
                    Text("📁 $folderLabel", style = MaterialTheme.typography.labelSmall)
                }
                val curPath = decodeFolderPath(item.folderPathCsv)
                DropdownMenu(expanded = showFolderPicker, onDismissRequest = { showFolderPicker = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "— 미분류",
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
                                    "📁 ${path.last()}",
                                    modifier = Modifier.padding(start = ((path.size - 1) * 12).dp),
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = { scope.launch { repository.moveCalcSavedItemToFolder(item, path); showFolderPicker = false; onChanged() } }
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
            TextButton(onClick = { scope.launch { repository.loadCalcSavedItemAsDraft(item); onChanged() } }) { Text("✚ 불러오기") }
            TextButton(onClick = { scope.launch { repository.deleteCalcSavedItem(item); onChanged() } }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
        }
    }
}
