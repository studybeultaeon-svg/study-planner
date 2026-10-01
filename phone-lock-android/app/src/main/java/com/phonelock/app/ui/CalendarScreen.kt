package com.phonelock.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import com.phonelock.app.ui.components.Hairline
import com.phonelock.app.ui.components.BigNumber
import com.phonelock.app.ui.components.CompactField
import com.phonelock.app.ui.components.Overline
import com.phonelock.app.ui.theme.LocalPhoneLockPalette
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

private val MONTHS_KO = arrayOf("1월", "2월", "3월", "4월", "5월", "6월", "7월", "8월", "9월", "10월", "11월", "12월")
private val WEEKDAYS_KO = arrayOf("일", "월", "화", "수", "목", "금", "토")

/**
 * 51차: 4단계(빨주노초)→7단계 무지개→8단계→77차 3단계(빨/노/초)로 축소를 거쳐, 83차(다회독 상세화)부터는
 * 업무마다 회독 수(3~8)가 달라질 수 있어 고정 색상표 대신 passIndex/passTotal 기반 빨강→초록 그라데이션
 * ([com.phonelock.shared.calc.PassSchedule.passColor])을 쓴다. 레거시 color 문자열만 있는 경우를 위한
 * 폴백만 이 함수에 남겨둔다.
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

private fun passColor(task: CalendarTask): Color = Color(com.phonelock.shared.calc.PassSchedule.passColor(task.passIndex, task.passTotal))
private fun passLabel(task: CalendarTask): String = "${task.passIndex + 1}회차"

private fun dowLabel(date: LocalDate): String = WEEKDAYS_KO[date.dayOfWeek.value % 7]

/**
 * 네이티브 캘린더(2단계). 웹앱 index.html "캘린더" 탭을 이식 — 월 그리드 + 아래 선택된 날짜 상세
 * 섹션, 완료/미완료·순서·이동/복사·삭제, 색상(회독) 변경, 정리(6개월 이전 삭제)까지 원본 로직 그대로.
 * 데스크탑판 CalendarScreen.kt와 로직을 대칭으로 유지한다. DECISIONS.md/CLAUDE.md 참고.
 */
@Composable
fun CalendarScreen(repository: PhoneLockRepository) {
    // 141차: "오늘"은 달력 날짜가 아니라 "하루 시작 기준"(dailyResetHour) — 타이머·공부 기록과 같은 날을 가리킨다.
    val today = LocalDate.parse(repository.todayCalendarDateKey())
    var year by remember { mutableStateOf(today.year) }
    var month by remember { mutableStateOf(today.monthValue - 1) }
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
        // 98차(온라인/오프라인 모드): 오프라인이면 네트워크 타임아웃만 기다리게 되므로 아예 건너뛴다.
        if (!repository.isEffectivelyOffline()) repository.syncCalendarFromFirebase()
        dayRefreshTick++
    }

    val dayDetail: @Composable () -> Unit = {
        val date = selectedDate
        if (date == null) {
            Text("날짜를 눌러 그날의 일정을 확인하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            DayDetailSection(repository = repository, date = date, onChanged = { dayRefreshTick++ })
        }
    }

    // 98차(사용자 요청): 당겨서 새로고침 — 서버 최신 상태를 다시 받아온다.
    com.phonelock.app.ui.components.PullToRefreshBox(onRefresh = {
        if (!repository.isEffectivelyOffline()) repository.syncCalendarFromFirebase()
        dayRefreshTick++
    }) {
    if (com.phonelock.app.ui.components.isTabletWidth()) {
        // 83차: 태블릿은 데스크탑 CalendarScreen.kt와 같은 좌(월 그리드)/우(날짜 상세) 분할.
        Column(Modifier.fillMaxSize().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            com.phonelock.app.ui.components.ResponsiveSplit(
                modifier = Modifier.weight(1f),
                left = {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        CalendarMonthGrid(
                            year = year, month = month, today = today, selectedDate = selectedDate, monthTasks = monthTasks,
                            onPrevMonth = { if (month == 0) { month = 11; year-- } else month-- },
                            onNextMonth = { if (month == 11) { month = 0; year++ } else month++ },
                            onSelectDate = { selectedDate = it },
                            onArchive = { scope.launch { repository.archiveOldCalendarTasks(); dayRefreshTick++ } }
                        )
                    }
                },
                right = {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { dayDetail() }
                }
            )
        }
    } else {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter).padding(top = Spacing.md, bottom = Spacing.xl)) {

        CalendarMonthGrid(
            year = year, month = month, today = today, selectedDate = selectedDate, monthTasks = monthTasks,
            onPrevMonth = { if (month == 0) { month = 11; year-- } else month-- },
            onNextMonth = { if (month == 11) { month = 0; year++ } else month++ },
            onSelectDate = { selectedDate = it },
            onArchive = { scope.launch { repository.archiveOldCalendarTasks(); dayRefreshTick++ } }
        )

        Spacer(Modifier.height(Spacing.lg))
        dayDetail()
    }
    }
    }
}

/** 월 이동 헤더 + 오래된 일정 정리 + 요일 헤더 + 날짜 그리드(83차: 태블릿/폰 공용으로 추출). */
@Composable
private fun CalendarMonthGrid(
    year: Int,
    month: Int,
    today: LocalDate,
    selectedDate: LocalDate?,
    monthTasks: List<CalendarTask>,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onArchive: () -> Unit
) {
    // 144차: 달 이름을 크게(편집형 머리), 이동은 오른쪽 화살표 두 개, 칸은 테두리 없이 — 오늘은 강조색 원, 고른 날은 먹색
    // 고리, 일정은 상태 색 점 + 개수(전부 완료=성공, 일부=경고, 하나도 안 함=오류 — 테마 팔레트 색).
    val palette = LocalPhoneLockPalette.current
    val sundayColor = MaterialTheme.colorScheme.error
    val saturdayColor = com.phonelock.app.ui.components.saturdayInk()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Overline("${year}년")
            Text(MONTHS_KO[month], style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
        }
        IconButton(onClick = onPrevMonth) { Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "이전 달") }
        IconButton(onClick = onNextMonth) { Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "다음 달") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onArchive) {
            Icon(Icons.Outlined.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("오래된 일정 정리", maxLines = 1, softWrap = false)
        }
    }
    Spacer(Modifier.height(Spacing.xs))
    Row(Modifier.fillMaxWidth()) {
        WEEKDAYS_KO.forEachIndexed { i, d ->
            val c = when (i) { 0 -> sundayColor; 6 -> saturdayColor; else -> MaterialTheme.colorScheme.onSurfaceVariant }
            Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = c)
        }
    }
    Spacer(Modifier.height(Spacing.xs))
    Hairline()

    val firstOfMonth = LocalDate.of(year, month + 1, 1)
    val firstDow = firstOfMonth.dayOfWeek.value % 7
    val daysInMonth = firstOfMonth.lengthOfMonth()
    val rows = (firstDow + daysInMonth + 6) / 7
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
                    Column(
                        Modifier.weight(1f).height(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .clickable { onSelectDate(date) }
                            .padding(top = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val dayNumColor = when {
                            isToday -> MaterialTheme.colorScheme.onPrimary
                            date.dayOfWeek == java.time.DayOfWeek.SUNDAY -> sundayColor
                            date.dayOfWeek == java.time.DayOfWeek.SATURDAY -> saturdayColor
                            else -> MaterialTheme.colorScheme.onBackground
                        }
                        Box(
                            Modifier.size(28.dp)
                                .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                .then(if (isSelected && !isToday) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("$dayNum", style = MaterialTheme.typography.labelLarge, color = dayNumColor, maxLines = 1, softWrap = false)
                        }
                        if (dayTasks.isNotEmpty()) {
                            val doneCount = dayTasks.count { it.status == "O" }
                            val badgeColor = when {
                                doneCount == dayTasks.size -> palette.fillGood
                                doneCount > 0 -> palette.fillPartial
                                else -> palette.fillBad
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(badgeColor, CircleShape))
                                Spacer(Modifier.width(3.dp))
                                Text("${dayTasks.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                } else {
                    Box(Modifier.weight(1f).height(64.dp))
                }
            }
        }
    }
    Hairline()
}

/**
 * 고른 날짜의 상세(폰은 월 그리드 아래, 태블릿은 오른쪽 칸).
 *
 * 146차: 흰 판 안에 일정마다 상자 + 이모지 색 버튼 5개였던 구조를 기준 화면(차단 규칙 목록)과 같은 문법으로 —
 * 위에 "완료 n / 전체"를 큰 숫자로, 아래는 가는 선으로 나뉜 일정 줄. 줄마다 완료·미완료는 아이콘 두 개로 바로 누르고,
 * 나머지(이름·회차·연결·반복·순서·이동·복사·삭제)는 더보기 메뉴에 둔다.
 */
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
    val doneCount = tasks.count { it.status == "O" }

    Column(Modifier.fillMaxWidth()) {
        Overline("${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일 · ${dowLabel(date)}요일")
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            BigNumber(
                "$doneCount",
                unit = "/ ${tasks.size} 완료",
                style = MaterialTheme.typography.displaySmall,
                color = if (tasks.isNotEmpty() && doneCount == tasks.size) LocalPhoneLockPalette.current.success else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (totalSeconds > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Overline("집중")
                    Text(focusDurationLabel(totalSeconds), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                }
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Hairline()
        if (tasks.isEmpty()) {
            Text(
                "등록된 업무가 없습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
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
                Hairline()
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(
                value = newTaskName,
                onValueChange = { newTaskName = it },
                placeholder = "새 업무 이름",
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
            }) { Text("추가", maxLines = 1, softWrap = false) }
        }
    }
    Spacer(Modifier.height(Spacing.lg))
    LinkedCalcSection(repository = repository, dateKey = dateKey, onChanged = { refreshDay() })
}

/** "1시간 5분" / "45분" / "1분 미만" — 날짜 상세의 집중 시간 표기(146차). */
private fun focusDurationLabel(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}시간 ${m}분"
        h > 0 -> "${h}시간"
        m > 0 -> "${m}분"
        else -> "1분 미만"
    }
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

    SectionCard("계산기 업무에서 추가") {
        Text(
            "완료하면 계산기 진행량에 더해집니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.sm))
        // 업무가 많으면 가로 스크롤은 잘려 보인다는 인상을 줘서(사용자 실기기 확인) 줄바꿈 방식으로 —
        // 스크롤 없이 전부 보이도록 필요한 만큼 여러 줄로 흘러내려간다.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            calcTasks.forEach { t ->
                FilterChip(
                    selected = selected == t.name,
                    onClick = { selected = t.name },
                    label = { Text("${t.name} · ${t.qty}${t.unit}", maxLines = 1, softWrap = false) }
                )
            }
        }
        selected?.let { name ->
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                CompactField(
                    value = fromText,
                    onValueChange = { fromText = it.filter { c -> c.isDigit() } },
                    label = "시작",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    centerValue = true,
                    modifier = Modifier.width(88.dp)
                )
                Text("~")
                CompactField(
                    value = toText,
                    onValueChange = { toText = it.filter { c -> c.isDigit() } },
                    label = "끝",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    centerValue = true,
                    modifier = Modifier.width(88.dp)
                )
                Spacer(Modifier.weight(1f))
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
                }) { Text("추가", maxLines = 1, softWrap = false) }
            }
        }
    }
}

/**
 * 캘린더 일정 하나의 계산기 연동 설정 편집(86차 신규) — 생성 시(LinkedCalcSection)에만 정할 수 있던
 * 연결을 업무마다 나중에 바꿀 수 있게 하는 작은 패널. 다른 계산기 업무로 재연결, 완전 해제, 완료 시
 * 반영될 할당량(progressStep) 수정을 한 곳에서 처리한다. "적용"은 선택된 업무가 지금 연결과 같아도
 * 실행되므로, 그 업무의 회독 설정이 나중에 바뀐 경우 다시 맞추는 초기화 용도로도 쓰인다.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LinkEditorPanel(
    repository: PhoneLockRepository,
    task: CalendarTask,
    onChanged: () -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var calcTasks by remember(task.id) { mutableStateOf<List<CalcTask>>(emptyList()) }
    var selected by remember(task.id) { mutableStateOf(task.linkedCalc) }
    var amountText by remember(task.id) { mutableStateOf(task.progressStep ?: "") }

    LaunchedEffect(task.id) {
        calcTasks = repository.getCalcTasks().filter { it.name.isNotBlank() }
    }

    InlinePanel("업무 연결") {
        if (calcTasks.isEmpty()) {
            Text("등록된 계산기 업무가 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                calcTasks.forEach { t ->
                    FilterChip(
                        selected = selected == t.name,
                        onClick = { selected = t.name },
                        label = { Text(t.name, maxLines = 1, softWrap = false) }
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        CompactField(
            value = amountText,
            onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
            label = "완료하면 더할 양",
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
        )
        Spacer(Modifier.height(Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val name = selected ?: return@Button
                    scope.launch {
                        repository.setCalendarTaskLink(task, name, amountText.ifBlank { null })
                        onChanged()
                    }
                },
                enabled = selected != null
            ) { Text("적용", maxLines = 1, softWrap = false) }
            TextButton(onClick = {
                scope.launch {
                    repository.setCalendarTaskLink(task, null, null)
                    onChanged()
                }
            }) { Text("연결 해제", maxLines = 1, softWrap = false) }
            TextButton(onClick = onCancel) { Text("취소", maxLines = 1, softWrap = false) }
        }
    }
}

/** 일정 줄 아래에 펼쳐지는 작은 편집 칸(146차) — 차단 규칙 목록의 "끄기 확인" 칸과 같은 옅은 바탕 + 작은 라벨. */
@Composable
private fun InlinePanel(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(Spacing.md)
    ) {
        Overline(title)
        Spacer(Modifier.height(Spacing.xs))
        content()
    }
}

/** 완료(O)·미완료(X) 상태를 바로 고르는 둥근 아이콘 버튼 — 고른 상태면 그 색의 옅은 원 안에 진하게(146차). */
@Composable
private fun StatusToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) color.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
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
    var showPassPicker by remember(task.id) { mutableStateOf(false) }
    var showMoveCopy by remember(task.id) { mutableStateOf<String?>(null) }
    var targetDateText by remember(task.id) { mutableStateOf("") }
    var showLinkEditor by remember(task.id) { mutableStateOf(false) }
    var menuOpen by remember(task.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val palette = LocalPhoneLockPalette.current

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // 회차 색 점 — 누르면 회차를 바꾼다(웹앱에서 이름을 눌러 색을 고르던 동작과 같다).
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable { showPassPicker = !showPassPicker },
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(10.dp).background(passColor(task), CircleShape)) }
            Spacer(Modifier.width(Spacing.xs))
            Column(Modifier.weight(1f).clickable { showPassPicker = !showPassPicker }) {
                Text(
                    task.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                // 회차 · 반복 · 잰 시간 · 연결 — 한 줄 메타(이모지 없이).
                val meta = buildList {
                    add(passLabel(task))
                    if (task.multiPassEnabled) add("반복")
                    if (loggedSeconds != null && loggedSeconds > 0) add(focusDurationLabel(loggedSeconds))
                    task.linkedCalc?.let { add("연결: $it") }
                }.joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            StatusToggle(Icons.Filled.Check, "완료", task.status == "O", palette.success) {
                scope.launch { repository.setCalendarTaskStatus(task, "O"); onChanged() }
            }
            StatusToggle(Icons.Filled.Close, "미완료", task.status == "X", MaterialTheme.colorScheme.error) {
                scope.launch { repository.setCalendarTaskStatus(task, "X"); onChanged() }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "더보기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("이름 바꾸기") }, onClick = { menuOpen = false; editingName = true })
                    DropdownMenuItem(text = { Text("회차 바꾸기") }, onClick = { menuOpen = false; showPassPicker = true })
                    DropdownMenuItem(text = { Text(if (task.linkedCalc != null) "업무 연결 바꾸기" else "계산기 업무 연결") }, onClick = { menuOpen = false; showLinkEditor = true })
                    // 79차: 완료(O) 시 다음 회차를 자동으로 만들지 업무마다 켜고 끈다(기본 off).
                    DropdownMenuItem(
                        text = { Text(if (task.multiPassEnabled) "반복 끄기" else "반복 켜기 (완료하면 다음 회차)") },
                        onClick = { menuOpen = false; scope.launch { repository.setCalendarTaskMultiPass(task, !task.multiPassEnabled); onChanged() } }
                    )
                    if (!isFirst) DropdownMenuItem(text = { Text("위로") }, onClick = { menuOpen = false; scope.launch { repository.moveCalendarTaskOrder(task, -1); onChanged() } })
                    if (!isLast) DropdownMenuItem(text = { Text("아래로") }, onClick = { menuOpen = false; scope.launch { repository.moveCalendarTaskOrder(task, 1); onChanged() } })
                    DropdownMenuItem(text = { Text("다른 날로 옮기기") }, onClick = { menuOpen = false; showMoveCopy = "move" })
                    DropdownMenuItem(text = { Text("다른 날로 복사") }, onClick = { menuOpen = false; showMoveCopy = "copy" })
                    DropdownMenuItem(
                        text = { Text("삭제", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; scope.launch { repository.deleteCalendarTask(task); onChanged() } }
                    )
                }
            }
        }

        if (showPassPicker) {
            // 83차: 회독 수가 업무마다 다를 수 있으므로(3~8) 고정 3개가 아니라 task.passTotal만큼 보여준다.
            InlinePanel("회차") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    (task.passTotal - 1 downTo 0).forEach { idx ->
                        val stageColor = Color(com.phonelock.shared.calc.PassSchedule.passColor(idx, task.passTotal))
                        OutlinedButton(
                            onClick = {
                                scope.launch { repository.setCalendarTaskPassIndex(task, idx); showPassPicker = false; onChanged() }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = stageColor),
                            border = BorderStroke(1.dp, stageColor.copy(alpha = 0.6f))
                        ) { Text("${idx + 1}회차", maxLines = 1, softWrap = false) }
                    }
                }
            }
        }

        if (editingName) {
            InlinePanel("이름 바꾸기") {
                CompactField(value = nameText, onValueChange = { nameText = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        scope.launch {
                            if (nameText.isNotBlank()) repository.renameCalendarTask(task, nameText)
                            editingName = false
                            onChanged()
                        }
                    }) { Text("저장") }
                    TextButton(onClick = { editingName = false; nameText = task.name }) { Text("취소") }
                }
            }
        }

        if (showLinkEditor) {
            LinkEditorPanel(
                repository = repository,
                task = task,
                onChanged = { showLinkEditor = false; onChanged() },
                onCancel = { showLinkEditor = false }
            )
        }

        if (showMoveCopy != null) {
            InlinePanel(if (showMoveCopy == "move") "다른 날로 옮기기" else "다른 날로 복사") {
                Row(verticalAlignment = Alignment.Bottom) {
                    // 92차: 수동 "YYYY-MM-DD" 텍스트 입력 대신 미니 캘린더 날짜 선택 버튼(DatePickerField) —
                    // 직접 타이핑하다 형식이 틀려 조용히 무시되던 문제도 함께 해소된다(항상 유효한 날짜만 돌려준다).
                    com.phonelock.app.ui.components.DatePickerField(
                        value = targetDateText,
                        onValueChange = { targetDateText = it },
                        label = "날짜",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Button(
                        onClick = {
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
                        },
                        modifier = Modifier.height(56.dp)
                    ) { Text("확인", maxLines = 1, softWrap = false) }
                    TextButton(onClick = { showMoveCopy = null; targetDateText = "" }, modifier = Modifier.height(56.dp)) { Text("취소") }
                }
            }
        }
    }
}
