package com.phonelock.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.app.data.CalcTask
import com.phonelock.app.data.*
import com.phonelock.app.data.CalendarTask
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.StudyLogEntry
import com.phonelock.app.ui.components.SectionCard
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

private val MONTHS_KO = arrayOf("1월", "2월", "3월", "4월", "5월", "6월", "7월", "8월", "9월", "10월", "11월", "12월")
private val WEEKDAYS_KO = arrayOf("일", "월", "화", "수", "목", "금", "토")
// 77차: 8단계(51차)에서 3단계(빨/노/초)로 축소(사용자 요청, 데스크탑판과 대칭).
private val COLOR_LABEL = mapOf("red" to "1회독", "yellow" to "2회독", "green" to "3회독")

/**
 * 51차: 4단계(빨주노초)→7단계 무지개(빨주노초파남보)→8단계(사용자 요청, 데스크탑판과 대칭) — 1회독을
 * "하얀색"으로 새로 두고 기존 빨주노초파남보는 2~8회독으로 한 칸씩 밀렸다. white는 완전한 흰색이면
 * 이 앱의 밝은 배경(라이트+그린 테마, 49차)에서 안 보이므로 은은한 회색조로 표현했다.
 */
private fun stageTextColor(stage: String): Color = when (stage) {
    "white" -> Color(0xFF9CA3AF)
    "red" -> Color(0xFFEF4444)
    "orange" -> Color(0xFFF97316)
    "yellow" -> Color(0xFFEAB308)
    "green" -> Color(0xFF22C55E)
    "blue" -> Color(0xFF3B82F6)
    "indigo" -> Color(0xFF6366F1)
    "purple" -> Color(0xFFA855F7)
    else -> Color(0xFFAAAAAA)
}

private fun dowLabel(date: LocalDate): String = WEEKDAYS_KO[date.dayOfWeek.value % 7]

/**
 * 네이티브 캘린더(2단계). 웹앱 index.html "캘린더" 탭을 이식 — 월 그리드 + 아래 선택된 날짜 상세
 * 섹션, 완료/미완료·순서·이동/복사·삭제, 색상(회독) 변경, 정리(6개월 이전 삭제)까지 원본 로직 그대로.
 * 데스크탑판 CalendarScreen.kt와 로직을 대칭으로 유지한다. DECISIONS.md/CLAUDE.md 참고.
 */
@Composable
fun CalendarScreen(repository: PhoneLockRepository) {
    var year by remember { mutableStateOf(LocalDate.now().year) }
    var month by remember { mutableStateOf(LocalDate.now().monthValue - 1) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var monthTasks by remember { mutableStateOf<List<CalendarTask>>(emptyList()) }
    var dayRefreshTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(year, month, dayRefreshTick) {
        val first = LocalDate.of(year, month + 1, 1)
        val last = first.plusMonths(1).minusDays(1)
        monthTasks = repository.getCalendarTasksInRange(first.minusDays(7).toString(), last.plusDays(7).toString())
    }

    LaunchedEffect(Unit) {
        repository.syncCalendarFromFirebase()
        dayRefreshTick++
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.md)) {
        Text("📅 캘린더", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.md))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { if (month == 0) { month = 11; year-- } else month-- }) { Text("◀ 이전") }
            Text("${year}년 ${MONTHS_KO[month]}", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { if (month == 11) { month = 0; year++ } else month++ }) { Text("다음 ▶") }
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                scope.launch {
                    repository.archiveOldCalendarTasks()
                    dayRefreshTick++
                }
            }) { Text("🧹 오래된 일정 정리") }
        }

        Row(Modifier.fillMaxWidth()) {
            WEEKDAYS_KO.forEachIndexed { i, d ->
                val c = when (i) { 0 -> Color(0xFFF87171); 6 -> Color(0xFF6B9FFF); else -> MaterialTheme.colorScheme.onSurface }
                Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = c)
            }
        }

        val firstOfMonth = LocalDate.of(year, month + 1, 1)
        val firstDow = firstOfMonth.dayOfWeek.value % 7
        val daysInMonth = firstOfMonth.lengthOfMonth()
        val rows = (firstDow + daysInMonth + 6) / 7
        val today = LocalDate.now()
        val tasksByDate = monthTasks.groupBy { it.dateKey }

        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNum = row * 7 + col - firstDow + 1
                    if (dayNum in 1..daysInMonth) {
                        val date = LocalDate.of(year, month + 1, dayNum)
                        val key = date.toString()
                        val dayTasks = tasksByDate[key].orEmpty()
                        val isToday = date == today
                        val isSelected = selectedDate == date
                        // 웹앱 .day-cell.today는 셀 전체가 아니라 날짜 숫자만 원형 배지로 강조한다.
                        Box(
                            Modifier.weight(1f).padding(1.dp).height(56.dp)
                                .border(
                                    if (isSelected) 2.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    MaterialTheme.shapes.small
                                )
                                .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, MaterialTheme.shapes.small)
                                .clickable { selectedDate = date }
                                .padding(2.dp)
                        ) {
                            Column {
                                val dayNumColor = when {
                                    isToday -> MaterialTheme.colorScheme.onPrimary
                                    date.dayOfWeek == java.time.DayOfWeek.SUNDAY -> Color(0xFFF87171)
                                    date.dayOfWeek == java.time.DayOfWeek.SATURDAY -> Color(0xFF6B9FFF)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Box(
                                    Modifier.size(18.dp).background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("$dayNum", style = MaterialTheme.typography.labelSmall, color = dayNumColor, fontWeight = FontWeight.Bold)
                                }
                                if (dayTasks.isNotEmpty()) {
                                    val doneCount = dayTasks.count { it.status == "O" }
                                    val badgeColor = when {
                                        doneCount == dayTasks.size -> Color(0xFF34D399)
                                        doneCount > 0 -> Color(0xFFFBBF24)
                                        else -> Color(0xFFF87171)
                                    }
                                    Text(
                                        "${dayTasks.size}개",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = badgeColor,
                                        modifier = Modifier
                                            .background(badgeColor.copy(alpha = 0.18f), MaterialTheme.shapes.extraSmall)
                                            .padding(horizontal = 3.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f).padding(1.dp).height(56.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        val date = selectedDate
        if (date == null) {
            Text("날짜를 눌러 그날의 일정을 확인하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            DayDetailSection(repository = repository, date = date, onChanged = { dayRefreshTick++ })
        }
    }
}

@Composable
private fun DayDetailSection(repository: PhoneLockRepository, date: LocalDate, onChanged: () -> Unit) {
    val dateKey = date.toString()
    var tasks by remember(dateKey) { mutableStateOf<List<CalendarTask>>(emptyList()) }
    var newTaskName by remember(dateKey) { mutableStateOf("") }
    var studyLog by remember(dateKey) { mutableStateOf<List<StudyLogEntry>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(dateKey) {
        tasks = repository.getCalendarTasks(dateKey)
        // 이 날짜 상세 패널이 열려 있는 동안 5초마다 다른 기기 기록을 다시 읽어온다 — 예전엔 진입 시
        // 1회만 동기화해서, 패널을 계속 켜둔 채 다른 기기에서 방금 기록을 남겨도 반영되지 않았다.
        while (true) {
            repository.syncStudyLogFromFirebase(dateKey)
            studyLog = repository.getStudyLogForDate(dateKey)
            delay(5000)
        }
    }

    fun refreshDay() {
        scope.launch {
            tasks = repository.getCalendarTasks(dateKey)
            onChanged()
        }
    }

    val secondsByTaskName = remember(studyLog) { studyLog.groupBy { it.taskName }.mapValues { (_, entries) -> entries.sumOf { it.seconds } } }
    val totalSeconds = secondsByTaskName.values.sum()
    val title = "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일 (${dowLabel(date)})" +
        if (totalSeconds > 0) " · ⏱ 총 ${formatHmsLog(totalSeconds.toLong())}" else ""

    SectionCard(title) {
        if (tasks.isEmpty()) {
            Text("등록된 업무가 없습니다.", style = MaterialTheme.typography.bodySmall)
        } else {
            tasks.forEachIndexed { ordinal, task ->
                CalendarTaskRow(
                    repository = repository,
                    task = task,
                    isFirst = ordinal == 0,
                    isLast = ordinal == tasks.lastIndex,
                    loggedSeconds = secondsByTaskName[task.name],
                    onChanged = { refreshDay() }
                )
                Spacer(Modifier.height(Spacing.xs))
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newTaskName,
                onValueChange = { newTaskName = it },
                label = { Text("새 업무 이름") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Spacing.sm))
            Button(onClick = {
                if (newTaskName.isNotBlank()) {
                    scope.launch {
                        repository.addCalendarTask(dateKey, newTaskName)
                        newTaskName = ""
                        refreshDay()
                    }
                }
            }) { Text("+ 추가") }
        }
    }
    Spacer(Modifier.height(Spacing.md))
    LinkedCalcSection(repository = repository, dateKey = dateKey, onChanged = { refreshDay() })
}

/**
 * 계산기 업무의 범위(예: "51~60쪽")를 이 날짜의 캘린더 일정으로 연결한다(웹앱 addLinkedTasksFromModal
 * 이식, 51차 신규, 데스크탑판과 대칭). 완료 체크하면 그 계산기 업무의 progress가 자동으로 늘어난다.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LinkedCalcSection(repository: PhoneLockRepository, dateKey: String, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var calcTasks by remember(dateKey) { mutableStateOf<List<CalcTask>>(emptyList()) }
    var selected by remember(dateKey) { mutableStateOf<String?>(null) }
    var fromText by remember(dateKey) { mutableStateOf("") }
    var toText by remember(dateKey) { mutableStateOf("") }

    LaunchedEffect(dateKey) {
        calcTasks = repository.getCalcTasks().filter { it.name.isNotBlank() }
    }
    if (calcTasks.isEmpty()) return

    SectionCard("📊 계산기 업무 연결") {
        Text(
            "계산기 업무의 범위를 이 날 일정으로 만듭니다. 완료 체크하면 계산기 진행량에 자동으로 더해집니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.sm))
        // 업무가 많으면 가로 스크롤은 잘려 보인다는 인상을 줘서(사용자 실기기 확인) 줄바꿈 방식으로 변경 —
        // 스크롤 없이 전부 보이도록 필요한 만큼 여러 줄로 흘러내려간다.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            calcTasks.forEach { t ->
                val isSelected = selected == t.name
                OutlinedButton(
                    onClick = { selected = t.name },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = if (isSelected) {
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    } else ButtonDefaults.outlinedButtonColors()
                ) { Text("${t.name} (${t.qty}${t.unit})", style = MaterialTheme.typography.labelSmall) }
            }
        }
        selected?.let { name ->
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(
                    value = fromText,
                    onValueChange = { fromText = it.filter { c -> c.isDigit() } },
                    label = { Text("시작") },
                    modifier = Modifier.width(90.dp)
                )
                Text("~")
                OutlinedTextField(
                    value = toText,
                    onValueChange = { toText = it.filter { c -> c.isDigit() } },
                    label = { Text("끝") },
                    modifier = Modifier.width(90.dp)
                )
                Button(onClick = {
                    val from = fromText.toIntOrNull()
                    val to = toText.toIntOrNull()
                    if (from != null && to != null && from in 1..to) {
                        scope.launch {
                            repository.addLinkedCalendarTask(dateKey, name, from, to)
                            fromText = ""
                            toText = ""
                            onChanged()
                        }
                    }
                }) { Text("추가") }
            }
        }
    }
}

@Composable
private fun CalendarTaskRow(
    repository: PhoneLockRepository,
    task: CalendarTask,
    isFirst: Boolean,
    isLast: Boolean,
    loggedSeconds: Int?,
    onChanged: () -> Unit
) {
    var editingName by remember(task.id) { mutableStateOf(false) }
    var nameText by remember(task.id) { mutableStateOf(task.name) }
    var showColorPicker by remember(task.id) { mutableStateOf(false) }
    var showMoveCopy by remember(task.id) { mutableStateOf<String?>(null) }
    var targetDateText by remember(task.id) { mutableStateOf("") }
    var nextDaysText by remember(task.id) { mutableStateOf(task.nextDays?.toString() ?: "") }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
            .padding(Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("▲", fontSize = 10.sp, modifier = Modifier.clickable(enabled = !isFirst) {
                    scope.launch { repository.moveCalendarTaskOrder(task, -1); onChanged() }
                }.padding(2.dp))
                Text("▼", fontSize = 10.sp, modifier = Modifier.clickable(enabled = !isLast) {
                    scope.launch { repository.moveCalendarTaskOrder(task, 1); onChanged() }
                }.padding(2.dp))
            }
            Spacer(Modifier.width(Spacing.xs))
            if (editingName) {
                Box(Modifier.size(10.dp).background(stageTextColor(task.color), CircleShape))
                Spacer(Modifier.width(Spacing.xs))
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            } else {
                // 웹앱 상세 목록(.task-name-modal)은 월 그리드 배지와 달리 배경/테두리 없는 색 텍스트다.
                Column(Modifier.weight(1f).clickable { showColorPicker = !showColorPicker }) {
                    Text(
                        task.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = stageTextColor(task.color)
                    )
                    // 이름 아래 회독 라벨 옆 빈 공간에 이 업무를 실제로 잰 시간을 붙여 보여준다.
                    val metaLine = COLOR_LABEL[task.color] ?: ""
                    val timeLine = if (loggedSeconds != null && loggedSeconds > 0) " · ⏱ ${formatHmsLog(loggedSeconds.toLong())}" else ""
                    Text("$metaLine$timeLine", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(Spacing.xs))
            // 79차: 완료(O) 시 다음 회독을 자동 생성할지 업무마다 켜고 끌 수 있는 토글(기본 off, 사용자 요청).
            // 꺼져 있으면 아래 ⏱(nextDays) 입력은 의미가 없으므로 숨긴다.
            Text(
                if (task.multiPassEnabled) "🔁다회독" else "🔁off",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (task.multiPassEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        (if (task.multiPassEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.15f),
                        RoundedCornerShape(50)
                    )
                    .clickable { scope.launch { repository.setCalendarTaskMultiPass(task, !task.multiPassEnabled); onChanged() } }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
            if (task.multiPassEnabled) {
                // 웹앱 .next-days-btn — 다음 회독까지 며칠 뒤인지 짧은 알약 입력, 헤더 줄 안에 넣는다.
                OutlinedTextField(
                    value = nextDaysText,
                    onValueChange = { text ->
                        nextDaysText = text
                        scope.launch { repository.setCalendarTaskNextDays(task, text.trim().toIntOrNull()) }
                    },
                    placeholder = { Text("⏱", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.width(64.dp),
                    textStyle = MaterialTheme.typography.labelSmall,
                    singleLine = true
                )
                Spacer(Modifier.width(Spacing.xs))
            }
            val statusLabel = task.status ?: "미완"
            val statusColor = when (task.status) {
                "O" -> Color(0xFF34D399)
                "X" -> Color(0xFFF87171)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                statusLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = statusColor,
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(50))
                    .clickable { showColorPicker = !showColorPicker }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        if (showColorPicker) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.padding(top = Spacing.xs).horizontalScroll(rememberScrollState())
            ) {
                listOf(
                    "green" to "3회독", "yellow" to "2회독", "red" to "1회독"
                ).forEach { (c, label) ->
                    val stageColor = stageTextColor(c)
                    OutlinedButton(
                        onClick = {
                            scope.launch { repository.recolorCalendarTask(task, c); showColorPicker = false; onChanged() }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = stageColor),
                        border = BorderStroke(1.dp, stageColor.copy(alpha = 0.5f))
                    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }

        if (editingName) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
                TextButton(onClick = {
                    scope.launch {
                        if (nameText.isNotBlank()) repository.renameCalendarTask(task, nameText)
                        editingName = false
                        onChanged()
                    }
                }) { Text("저장") }
                TextButton(onClick = { editingName = false; nameText = task.name }) { Text("취소") }
            }
        } else {
            TextButton(
                onClick = { editingName = true },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) { Text("✏️ 이름 수정", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        // 웹앱 .modal-actions .modal-btn — 버튼 5개가 flex:1로 균등하게 폭을 나눠 차지하고,
        // 각각 다른 틴트색(.btn-done/.btn-undone/.btn-move/.btn-copy/.btn-remove).
        val green = Color(0xFF34D399)
        val red = Color(0xFFF87171)
        val purple = MaterialTheme.colorScheme.secondary
        val actionButtonPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
            OutlinedButton(
                onClick = { scope.launch { repository.setCalendarTaskStatus(task, "O"); onChanged() } },
                colors = ButtonDefaults.outlinedButtonColors(containerColor = green.copy(alpha = 0.15f), contentColor = green),
                border = BorderStroke(1.dp, green.copy(alpha = 0.35f)),
                contentPadding = actionButtonPadding,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) { Text("✔ 완료", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { scope.launch { repository.setCalendarTaskStatus(task, "X"); onChanged() } },
                colors = ButtonDefaults.outlinedButtonColors(containerColor = red.copy(alpha = 0.15f), contentColor = red),
                border = BorderStroke(1.dp, red.copy(alpha = 0.35f)),
                contentPadding = actionButtonPadding,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) { Text("✘ 미완료", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { showMoveCopy = "move" },
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), contentColor = MaterialTheme.colorScheme.primary),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                contentPadding = actionButtonPadding,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) { Text("↔ 이동", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { showMoveCopy = "copy" },
                colors = ButtonDefaults.outlinedButtonColors(containerColor = purple.copy(alpha = 0.1f), contentColor = purple),
                border = BorderStroke(1.dp, purple.copy(alpha = 0.3f)),
                contentPadding = actionButtonPadding,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) { Text("⎘ 복사", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { scope.launch { repository.deleteCalendarTask(task); onChanged() } },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = red),
                contentPadding = actionButtonPadding,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) { Text("✕ 삭제", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
        }

        if (showMoveCopy != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
                OutlinedTextField(
                    value = targetDateText,
                    onValueChange = { targetDateText = it },
                    label = { Text("대상 날짜 (YYYY-MM-DD)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.width(Spacing.xs))
                Button(onClick = {
                    val target = targetDateText.trim()
                    if (target.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                        scope.launch {
                            if (showMoveCopy == "move") repository.moveCalendarTaskToDate(task, target)
                            else repository.copyCalendarTaskToDate(task, target)
                            showMoveCopy = null
                            targetDateText = ""
                            onChanged()
                        }
                    }
                }) { Text("확인") }
                TextButton(onClick = { showMoveCopy = null }) { Text("취소") }
            }
        }
    }
}
