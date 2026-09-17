package com.phonelock.desktop.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.CalcTask
import com.phonelock.desktop.data.*
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        withContext(Dispatchers.IO) { repository.syncCalculatorFromFirebase() }
        tasks = repository.getCalcTasks()
    }

    LaunchedEffect(Unit) { refresh() }

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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("🗓️ 일정표", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text("할당량 계산기 업무 입력 기준", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 사용자 요청(안드로이드판은 당겨서 새로고침) — 데스크탑은 스와이프 제스처가 없어 버튼으로.
            IconButton(onClick = { scope.launch { refresh() } }) { Text("🔄") }
        }
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

        // 90차(사용자 요청): 표 자체는 그대로 두되, 넓은 데스크탑 창에서 표 오른쪽에 남던 빈 공간에
        // "오늘 목표"/범례 패널을 둔다. 표가 주인공이라 좌:우 = 3:1, 창이 좁아지면 ResponsiveSplit이
        // 알아서 위아래로 쌓는다.
        com.phonelock.desktop.ui.components.ResponsiveSplit(
            modifier = Modifier.weight(1f),
            leftWeight = 3f,
            rightWeight = 1f,
            left = {
                // 125차(사용자 요청): 스크롤은 있었지만 스크롤바가 없어 가로로 넘친 칸이 있는지 알 수 없었고, 표와
                // 오른쪽 패널의 경계도 흐렸다 — 테두리 안에서만 스크롤되게 하고 넘치는 방향에 스크롤바를 두며,
                // 요일 행은 세로 스크롤해도 위에 고정한다.
                TimetableScrollArea(
                    header = {
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
                    }
                ) {
                    Column {
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
            },
            right = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SectionCard("오늘 목표") {
                        val todayIdx = weekDates.indexOfFirst { it == today }
                        if (todayIdx < 0) {
                            Text(
                                "지금 보고 있는 주에 오늘이 없습니다. \"이번 주\"로 돌아오면 오늘 목표가 표시됩니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            // 표를 그리며 채우는 dayTotals와 별개로, 오늘 칸 값만 다시 계산한다
                            // (렌더링 순서에 기대지 않기 위해 — 계산식은 위 표 본문과 동일).
                            val todayGoals = rows.mapNotNull { row ->
                                if (today < row.start || today > row.dday) return@mapNotNull null
                                val v = dayValue(row.task, todayIdx).toDoubleOrNull() ?: 0.0
                                if (v > 0) row to v else null
                            }
                            if (todayGoals.isEmpty()) {
                                Text("오늘 예정된 업무가 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    todayGoals.forEach { (row, v) ->
                                        val achieved = repository.isLinkedGoalAchieved(today.toString(), row.task.name, v)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(row.task.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "${fmtDec(v)}${row.task.unit}" + if (achieved) " ✅" else "",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (achieved) Color(0xFF34D399) else Color(0xFFF87171)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("표 보는 법") {
                        Text(
                            "· 값은 계산기 업무의 요일별 목표량입니다.\n" +
                                "· 오늘 칸은 빨강, 캘린더 연동 목표를 달성한 칸은 초록 ✅으로 표시됩니다.\n" +
                                "· 목표량은 할당량 계산기 \"업무 입력\"에서 바꿉니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }
}

/** 스크롤바가 차지하는 폭 — 스크롤바가 마지막 열/행을 가리지 않도록 그만큼 본문 끝에 여백을 둔다. */
private val SCROLLBAR_LANE = 10.dp

/**
 * 일정표 표를 담는 스크롤 영역(125차, 사용자 요청, 안드로이드판과 대칭). 테두리 안쪽에서만 스크롤되고 넘친 내용은
 * 테두리 밖으로 그려지지 않는다. 크기는 표에 맞추되 부모 영역을 넘지 않고, 넘치는 방향에만 스크롤바(드래그 가능)를
 * 보여준다. 마우스 휠은 세로, Shift+휠은 가로로 스크롤된다. [header]는 세로로는 고정되고 가로로는 본문과 같은
 * 스크롤 상태를 공유해 함께 움직인다.
 */
@Composable
private fun TimetableScrollArea(
    header: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val showV = vScroll.maxValue in 1 until Int.MAX_VALUE
    val showH = hScroll.maxValue in 1 until Int.MAX_VALUE
    val shape = RoundedCornerShape(12.dp)
    // 기본 스타일은 검정 반투명이라 다크 테마에서 안 보인다 — 테마 글자색 기준으로 칠한다.
    val scrollbarStyle = ScrollbarStyle(
        minimalHeight = 24.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 300,
        unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
        hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    )
    val endLane = if (showV) SCROLLBAR_LANE else 0.dp
    Column(Modifier.clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)) {
        Box(Modifier.horizontalScroll(hScroll).padding(end = endLane)) { header() }
        Box(Modifier.weight(1f, fill = false)) {
            Box(
                Modifier
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll)
                    .padding(end = endLane, bottom = if (showH) SCROLLBAR_LANE else 0.dp)
            ) { content() }
            if (showV) {
                VerticalScrollbar(
                    rememberScrollbarAdapter(vScroll),
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 2.dp),
                    style = scrollbarStyle
                )
            }
            if (showH) {
                HorizontalScrollbar(
                    rememberScrollbarAdapter(hScroll),
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 2.dp, end = endLane),
                    style = scrollbarStyle
                )
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
