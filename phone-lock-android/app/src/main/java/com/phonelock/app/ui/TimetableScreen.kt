package com.phonelock.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonelock.app.data.CalcTask
import com.phonelock.app.data.*
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.ui.theme.Spacing
import java.time.LocalDate
import java.util.Locale

private val WEEKDAYS_KO = arrayOf("일", "월", "화", "수", "목", "금", "토")

private fun dayValue(task: CalcTask, jsDow: Int): String = when (jsDow) {
    0 -> task.sun; 1 -> task.mon; 2 -> task.tue; 3 -> task.wed
    4 -> task.thu; 5 -> task.fri; else -> task.sat
}

private fun fmtDec(n: Double): String {
    val r = Math.round(n * 100) / 100.0
    return if (r == Math.floor(r)) r.toLong().toString() else String.format(Locale.KOREA, "%.2f", r).trimEnd('0').trimEnd('.')
}

/** 태블릿 주간 표(데스크탑판 TimetableScreen.kt와 대칭)에서 한 업무의 시작/마감일을 함께 들고 있기 위한 행. */
private data class WeekTaskRow(val task: CalcTask, val start: LocalDate, val dday: LocalDate)

/**
 * 네이티브 일정표(4단계). 웹앱 index.html "일정표" 탭의 모바일(일 단위) 뷰를 이식 — 할당량
 * 계산기의 draft 업무 목록을 선택한 날짜 기준으로 필터링해 요일별 목표량을 보여준다. 캘린더 연동
 * (linkedCalc 완료 체크)은 3단계에서 이미 제외됐으므로 이 화면에도 없다 — DECISIONS.md 참고.
 */
@Composable
fun TimetableScreen(repository: PhoneLockRepository) {
    var tasks by remember { mutableStateOf<List<CalcTask>>(emptyList()) }
    var cursor by remember { mutableStateOf(LocalDate.now()) }

    LaunchedEffect(Unit) {
        repository.syncCalculatorFromFirebase()
        tasks = repository.getCalcTasks()
    }

    val today = LocalDate.now()
    val isToday = cursor == today
    val jsDow = cursor.dayOfWeek.value % 7
    val dateLabel = "${cursor.monthValue}월 ${cursor.dayOfMonth}일 (${WEEKDAYS_KO[jsDow]})" + if (isToday) " · 오늘" else ""

    val dayTasks = tasks.filter { t ->
        if (t.name.isBlank() || t.dday.isBlank()) return@filter false
        val dday = runCatching { LocalDate.parse(t.dday) }.getOrNull() ?: return@filter false
        val start = if (t.start.isBlank()) today else (runCatching { LocalDate.parse(t.start) }.getOrNull() ?: today)
        !cursor.isBefore(start) && !cursor.isAfter(dday)
    }

    // 계산기 연동(51차, 웹앱 isCalTaskLinkedDone 이식, 데스크탑판과 대칭) — 그날 연결된 일정이 목표량만큼
    // 완료됐는지 미리 조회해둔다(suspend라 렌더 루프 안에서 직접 못 부름).
    var achievedMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(cursor, dayTasks) {
        val map = mutableMapOf<String, Boolean>()
        dayTasks.forEach { t ->
            val v = dayValue(t, jsDow).toDoubleOrNull() ?: 0.0
            if (v > 0) map[t.name] = repository.isLinkedGoalAchieved(cursor.toString(), t.name, v)
        }
        achievedMap = map
    }

    // 태블릿은 데스크탑 TimetableScreen.kt와 같은 주간(일~토) 표 뷰 — 폰의 하루씩 넘기는 뷰 대신
    // 이번 주 전체를 한 표로 보여준다(데스크탑도 같은 이유로 넓은 화면에서 표를 씀). isLinkedGoalAchieved가
    // suspend라 폰의 achievedMap과 동일하게 LaunchedEffect로 선계산한다.
    if (com.phonelock.app.ui.components.isTabletWidth()) {
        var weekOffset by remember { mutableStateOf(0) }
        val currentSunday = today.minusDays(today.dayOfWeek.value.toLong() % 7)
        val sunday = currentSunday.plusWeeks(weekOffset.toLong())
        val weekDates = (0..6).map { sunday.plusDays(it.toLong()) }

        val rows = tasks.mapNotNull { t ->
            if (t.name.isBlank() || t.dday.isBlank()) return@mapNotNull null
            val dday = runCatching { LocalDate.parse(t.dday) }.getOrNull() ?: return@mapNotNull null
            val start = if (t.start.isBlank()) today else (runCatching { LocalDate.parse(t.start) }.getOrNull() ?: today)
            if (start > weekDates.last() || dday < weekDates.first()) null else WeekTaskRow(t, start, dday)
        }

        var weekAchievedMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
        LaunchedEffect(weekOffset, rows) {
            val map = mutableMapOf<String, Boolean>()
            rows.forEach { row ->
                weekDates.forEachIndexed { i, d ->
                    if (d < row.start || d > row.dday) return@forEachIndexed
                    val v = dayValue(row.task, i).toDoubleOrNull() ?: 0.0
                    if (v > 0) map["${d}|${row.task.name}"] = repository.isLinkedGoalAchieved(d.toString(), row.task.name, v)
                }
            }
            weekAchievedMap = map
        }

        Column(Modifier.fillMaxSize().padding(Spacing.md)) {
            Text("🗓️ 일정표", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text("할당량 계산기 업무 입력 기준", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.md))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { weekOffset-- }) {
                    androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("이전주")
                }
                Text(
                    "${weekDates.first().monthValue}/${weekDates.first().dayOfMonth} ~ ${weekDates.last().monthValue}/${weekDates.last().dayOfMonth}" +
                        if (weekOffset == 0) " (이번 주)" else "",
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedButton(onClick = { weekOffset++ }) {
                    Text("다음주")
                    androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(Spacing.sm))

            if (rows.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(
                        if (tasks.isEmpty()) "할당량 계산기에서 업무를 입력하면\n일정표가 자동으로 생성됩니다" else "이 주에 진행 예정인 업무가 없습니다",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return
            }

            val dayTotals = DoubleArray(7)
            val nameColWidth = 180.dp
            val dayColWidth = 92.dp
            val totalColWidth = 92.dp
            val border = MaterialTheme.colorScheme.outlineVariant

            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row(Modifier.border(1.dp, border)) {
                    TtCell("업무", nameColWidth, header = true)
                    weekDates.forEachIndexed { i, d ->
                        val isTodayCol = d == today
                        val weekdayColor2 = when (i) { 0 -> Color(0xFFF87171); 6 -> Color(0xFF6B9FFF); else -> null }
                        TtCell("${WEEKDAYS_KO[i]}\n${d.monthValue}/${d.dayOfMonth}", dayColWidth, header = true, highlight = isTodayCol, textColor = weekdayColor2)
                    }
                    TtCell("합계", totalColWidth, header = true)
                }
                rows.forEach { row ->
                    var rowTotal = 0.0
                    Row(Modifier.border(1.dp, border)) {
                        TtCell(row.task.name, nameColWidth)
                        weekDates.forEachIndexed { i, d ->
                            val isTodayCol = d == today
                            if (d < row.start || d > row.dday) {
                                TtCell("", dayColWidth, highlight = isTodayCol)
                            } else {
                                val v = dayValue(row.task, i).toDoubleOrNull() ?: 0.0
                                if (v > 0) {
                                    rowTotal += v
                                    dayTotals[i] += v
                                    val achieved = weekAchievedMap["${d}|${row.task.name}"] == true
                                    val label = "${fmtDec(v)}${row.task.unit}" + if (achieved) " ✅" else ""
                                    val cellColor = when {
                                        achieved -> Color(0xFF34D399)
                                        isTodayCol -> Color(0xFFF87171)
                                        else -> null
                                    }
                                    TtCell(label, dayColWidth, highlight = isTodayCol, textColor = cellColor)
                                } else {
                                    TtCell("—", dayColWidth, highlight = isTodayCol)
                                }
                            }
                        }
                        TtCell("${fmtDec(rowTotal)}${row.task.unit}", totalColWidth, bold = true, textColor = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(Modifier.border(1.dp, border)) {
                    TtCell("합계", nameColWidth, bold = true)
                    dayTotals.forEach { v -> TtCell(fmtDec(v), dayColWidth, bold = true) }
                    TtCell(fmtDec(dayTotals.sum()), totalColWidth, bold = true, textColor = MaterialTheme.colorScheme.primary)
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(Spacing.md)) {
        Text("🗓️ 일정표", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text("할당량 계산기 업무 입력 기준", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.sm))

        val weekdayColor = when (jsDow) {
            0 -> androidx.compose.ui.graphics.Color(0xFFF87171)
            6 -> androidx.compose.ui.graphics.Color(0xFF6B9FFF)
            else -> MaterialTheme.colorScheme.onSurface
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { cursor = cursor.minusDays(1) }) { androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "이전") }
            Spacer(Modifier.width(Spacing.sm))
            Text(dateLabel, style = MaterialTheme.typography.titleMedium, color = weekdayColor)
            Spacer(Modifier.width(Spacing.sm))
            OutlinedButton(onClick = { cursor = cursor.plusDays(1) }) { androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "다음") }
        }
        Spacer(Modifier.height(Spacing.md))

        if (dayTasks.isEmpty()) {
            Text(
                if (tasks.isEmpty()) "할당량 계산기에서 업무를 입력하면\n일정표가 자동으로 생성됩니다" else "이 날은 진행 중인 업무가 없습니다",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }

        // 웹앱 .tt-day-list-item — 업무명은 굵은 흰색, 값은 accent 파랑 굵게(오늘 보는 화면이라
        // .tt-today-val과 동일하게 항상 빨강), 0이면 무채색 "—".
        Column(Modifier.verticalScroll(rememberScrollState())) {
            var dayTotal = 0.0
            dayTasks.forEach { t ->
                val v = dayValue(t, jsDow).toDoubleOrNull() ?: 0.0
                dayTotal += v
                val achieved = v > 0 && achievedMap[t.name] == true
                Row(
                    Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 86차 버그 수정: 이름/값 둘 다 weight 없이 SpaceBetween만 쓰면 이름이 길 때
                    // 오른쪽 값(amount) Text가 화면 밖으로 밀려나 아예 안 보였다(사용자 실사용 확인) —
                    // 이름 쪽에만 weight(1f, fill=false)를 줘서 값 Text의 자연폭을 먼저 확보하고,
                    // 이름은 남는 폭 안에서 줄바꿈되도록 함(잘리지 않고 여러 줄로 전부 표시됨).
                    Text(
                        t.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false).padding(end = Spacing.sm)
                    )
                    Text(
                        if (v > 0) "${fmtDec(v)}${t.unit}" + if (achieved) " ✅" else "" else "—",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (v > 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (v <= 0) MaterialTheme.colorScheme.onSurfaceVariant
                            else if (achieved) Color(0xFF34D399)
                            else if (isToday) Color(0xFFF87171) else MaterialTheme.colorScheme.primary
                    )
                }
                HorizontalDivider()
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("합계", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmtDec(dayTotal), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 태블릿 주간 표 셀 — 데스크탑판 TimetableScreen.kt의 TtCell을 그대로 이식. */
@Composable
private fun TtCell(text: String, width: androidx.compose.ui.unit.Dp, header: Boolean = false, bold: Boolean = false, highlight: Boolean = false, textColor: Color? = null) {
    Column(
        Modifier
            .width(width)
            .heightIn(min = if (header) 52.dp else 44.dp)
            .background(if (highlight) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text,
            style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold || header) FontWeight.Bold else FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = textColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}
