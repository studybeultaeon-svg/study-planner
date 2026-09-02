package com.phonelock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
            OutlinedButton(onClick = { cursor = cursor.minusDays(1) }) { Text("◀") }
            Spacer(Modifier.width(Spacing.sm))
            Text(dateLabel, style = MaterialTheme.typography.titleMedium, color = weekdayColor)
            Spacer(Modifier.width(Spacing.sm))
            OutlinedButton(onClick = { cursor = cursor.plusDays(1) }) { Text("▶") }
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
                    Text(t.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
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
