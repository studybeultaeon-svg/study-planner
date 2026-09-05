package com.phonelock.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import com.phonelock.desktop.data.CalcTask
import com.phonelock.desktop.data.*
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

private data class WeekTaskRow(val task: CalcTask, val start: LocalDate, val dday: LocalDate)

/**
 * 네이티브 일정표(4단계). 웹앱 index.html "일정표" 탭의 데스크탑(주간) 뷰를 이식 — 할당량
 * 계산기의 draft 업무 목록(`repository.getCalcTasks()`, 저장됨 목록이 아님)을 이번 주(일~토)
 * 기준으로 필터링해 요일별 목표량 표로 보여준다. 웹앱의 캘린더 연동(linkedCalc 완료 체크)은
 * 3단계에서 이미 제외됐으므로 이 화면에도 없다 — DECISIONS.md 참고.
 */
@Composable
fun TimetableScreen(repository: Repository) {
    var tasks by remember { mutableStateOf(repository.getCalcTasks()) }
    var weekOffset by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { repository.syncCalculatorFromFirebase() }
        tasks = repository.getCalcTasks()
    }

    val today = LocalDate.now()
    val currentSunday = today.minusDays(today.dayOfWeek.value.toLong() % 7)
    val sunday = currentSunday.plusWeeks(weekOffset.toLong())
    val weekDates = (0..6).map { sunday.plusDays(it.toLong()) }

    val rows = tasks.mapNotNull { t ->
        if (t.name.isBlank() || t.dday.isBlank()) return@mapNotNull null
        val dday = runCatching { LocalDate.parse(t.dday) }.getOrNull() ?: return@mapNotNull null
        val start = if (t.start.isBlank()) today else (runCatching { LocalDate.parse(t.start) }.getOrNull() ?: today)
        if (start > weekDates.last() || dday < weekDates.first()) null else WeekTaskRow(t, start, dday)
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
                Text(if (tasks.isEmpty()) "할당량 계산기에서 업무를 입력하면\n일정표가 자동으로 생성됩니다" else "이 주에 진행 예정인 업무가 없습니다",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }

        val dayTotals = DoubleArray(7)
        // 86차 버그 수정: 업무명 칸이 좁고(140dp) 높이가 고정(44dp)이라, 이름이 길어 줄바꿈되면 두 번째
        // 줄이 고정 높이 밖으로 잘려 안 보였다(사용자 실사용 확인) — 폭을 넓히고, 아래 TtCell의 고정
        // height를 heightIn(min=)으로 바꿔 이름이 길면 그 행만 자연스럽게 늘어나도록 함.
        val nameColWidth = 180.dp
        val dayColWidth = 92.dp
        val totalColWidth = 92.dp
        val border = MaterialTheme.colorScheme.outlineVariant

        Column(Modifier.horizontalScroll(rememberScrollState())) {
            // 헤더 — 웹앱과 동일하게 일요일은 빨강, 토요일은 파랑으로 강조(.weekday-label.sun/.sat)
            Row(Modifier.border(1.dp, border)) {
                TtCell("업무", nameColWidth, header = true)
                weekDates.forEachIndexed { i, d ->
                    val isToday = d == today
                    val weekdayColor = when (i) { 0 -> Color(0xFFF87171); 6 -> Color(0xFF6B9FFF); else -> null }
                    TtCell("${WEEKDAYS_KO[i]}\n${d.monthValue}/${d.dayOfMonth}", dayColWidth, header = true, highlight = isToday, textColor = weekdayColor)
                }
                TtCell("합계", totalColWidth, header = true)
            }
            // 업무별 행
            rows.forEach { row ->
                var rowTotal = 0.0
                Row(Modifier.border(1.dp, border)) {
                    TtCell(row.task.name, nameColWidth)
                    weekDates.forEachIndexed { i, d ->
                        val isToday = d == today
                        if (d < row.start || d > row.dday) {
                            TtCell("", dayColWidth, highlight = isToday)
                        } else {
                            val v = dayValue(row.task, i).toDoubleOrNull() ?: 0.0
                            if (v > 0) {
                                rowTotal += v
                                dayTotals[i] += v
                                // 계산기 연동(51차, 웹앱 isCalTaskLinkedDone 이식) — 그날 연결된 일정이
                                // 목표량만큼 완료됐으면 ✅로 "달성" 표시.
                                val achieved = repository.isLinkedGoalAchieved(d.toString(), row.task.name, v)
                                val label = "${fmtDec(v)}${row.task.unit}" + if (achieved) " ✅" else ""
                                // 웹앱 .tt-val.tt-today-val — 오늘 칸 값은 빨강으로 강조(마감 임박 신호), 달성 시엔 초록.
                                val cellColor = when {
                                    achieved -> Color(0xFF34D399)
                                    isToday -> Color(0xFFF87171)
                                    else -> null
                                }
                                TtCell(label, dayColWidth, highlight = isToday, textColor = cellColor)
                            } else {
                                TtCell("—", dayColWidth, highlight = isToday)
                            }
                        }
                    }
                    // 웹앱 .tt-total — 합계 열은 항상 accent 파랑
                    TtCell("${fmtDec(rowTotal)}${row.task.unit}", totalColWidth, bold = true, textColor = MaterialTheme.colorScheme.primary)
                }
            }
            // 합계 행
            Row(Modifier.border(1.dp, border)) {
                TtCell("합계", nameColWidth, bold = true)
                dayTotals.forEach { v -> TtCell(fmtDec(v), dayColWidth, bold = true) }
                TtCell(fmtDec(dayTotals.sum()), totalColWidth, bold = true, textColor = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

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
