package com.phonelock.desktop.ui

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.desktop.data.CalendarTask
import com.phonelock.desktop.data.*
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.components.BigNumber
import com.phonelock.desktop.ui.components.CompactField
import com.phonelock.desktop.ui.components.Hairline
import com.phonelock.desktop.ui.components.Overline
import com.phonelock.desktop.ui.theme.LocalPhoneLockPalette
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val MONTHS_KO = arrayOf("1월", "2월", "3월", "4월", "5월", "6월", "7월", "8월", "9월", "10월", "11월", "12월")
private val WEEKDAYS_KO = arrayOf("일", "월", "화", "수", "목", "금", "토")
// 77차: 8단계(51차)에서 3단계(빨/노/초)로 축소(사용자 요청). 83차부터는 색상 자체가 passIndex/passTotal
// 그라데이션으로 넘어가 이 라벨 표는 더 이상 쓰이지 않는다(이력은 HANDOFF.md 참고).
private val COLOR_LABEL = mapOf("red" to "1회차", "yellow" to "2회차", "green" to "3회차")

/** 83차(다회독 상세화) — passIndex/passTotal 기반 빨강→초록 그라데이션 accent. */
internal fun passAccentColor(task: CalendarTask): Color = Color(com.phonelock.shared.calc.PassSchedule.passColor(task.passIndex, task.passTotal))
internal fun passLabel(task: CalendarTask): String = "${task.passIndex + 1}회차"

private fun dowLabel(date: LocalDate): String = WEEKDAYS_KO[date.dayOfWeek.value % 7]

/**
 * 웹앱 월 그리드의 `.task-chip` — 배경/테두리를 회독 색으로 채운 배지, O/X 완료 표시는 칩 색과
 * 별개로 항상 초록/빨강(`.status-O::before`/`.status-X::before`). 83차부터 색상은 task.passIndex/
 * passTotal 기반 그라데이션(레거시 color 문자열 대신).
 */
/** 모임 멤버 상세(CalendarTask 엔티티가 아니라 동기화로 받은 일정 요약)용 오버로드 — 128차부터 여기도
 *  passIndex/passTotal을 받아 라이브 캘린더와 똑같은 회독 색을 낸다(이전엔 레거시 color 문자열 기반이라
 *  4회독 이상 일정이 회색으로 나왔다). */
@Composable
internal fun TaskChip(name: String, passIndex: Int, passTotal: Int, status: String?, modifier: Modifier = Modifier) {
    val accent = Color(com.phonelock.shared.calc.PassSchedule.passColor(passIndex, passTotal))
    Text(
        buildAnnotatedString {
            if (status == "O") withStyle(SpanStyle(color = Color(0xFF34D399), fontWeight = FontWeight.Black)) { append("O ") }
            else if (status == "X") withStyle(SpanStyle(color = Color(0xFFF87171), fontWeight = FontWeight.Black)) { append("X ") }
            append(name)
        },
        modifier = modifier
            .background(accent.copy(alpha = 0.15f), MaterialTheme.shapes.extraSmall)
            .border(1.dp, accent, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun TaskChip(task: CalendarTask, modifier: Modifier = Modifier) =
    TaskChip(task.name, task.passIndex, task.passTotal, task.status, modifier)

/**
 * 네이티브 캘린더(2단계). 웹앱 index.html "캘린더" 탭을 이식 — 월 그리드 + 아래 선택된 날짜 상세
 * 섹션(웹앱은 모달이었지만 데스크탑은 화면이 넓어 인라인 섹션으로), 완료/미완료·순서·이동/복사·삭제,
 * 색상(회독) 변경, 정리(6개월 이전 삭제)까지 원본 로직 그대로. DECISIONS.md/CLAUDE.md 참고.
 */
@Composable
fun CalendarScreen(repository: Repository) {
    // 141차: "오늘"은 달력 날짜가 아니라 "하루 시작 기준"(dailyResetHour) — 타이머·공부 기록과 같은 날을 가리킨다.
    val today = LocalDate.parse(repository.todayCalendarDateKey())
    var year by remember { mutableStateOf(today.year) }
    var month by remember { mutableStateOf(today.monthValue - 1) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var monthTasks by remember { mutableStateOf<List<CalendarTask>>(emptyList()) }
    var dayRefreshTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        val first = LocalDate.of(year, month + 1, 1)
        val last = first.plusMonths(1).minusDays(1)
        monthTasks = repository.getCalendarTasksInRange(first.minusDays(7).toString(), last.plusDays(7).toString())
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { if (!repository.isEffectivelyOffline()) repository.syncCalendarFromFirebase() }
        refresh()
    }
    LaunchedEffect(year, month, dayRefreshTick) { refresh() }

    // 144차: 화면 제목은 집중 탭 머리가 보여주므로 달 이름부터(편집형). 새로고침은 달 머리 오른쪽.
    val palette = com.phonelock.desktop.ui.theme.LocalPhoneLockPalette.current
    val sundayColor = MaterialTheme.colorScheme.error
    val saturdayColor = com.phonelock.desktop.ui.components.saturdayInk()
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {

        // 데스크탑 전용 분할: 왼쪽(넓을 땐 좌측, 좁을 땐 위쪽)은 월 그리드, 오른쪽(넓을 땐 우측, 좁을 땐
        // 아래쪽)은 선택한 날짜의 상세 일정 — 웹앱의 모달 대신 항상 곁에 두고 볼 수 있는 패널 형태.
        // 79차: 창이 좁아지면(다른 앱과 나란히 등) Row 그대로 유지하면 양쪽 다 뭉개지므로, 안드로이드처럼
        // 위아래로 쌓는 ResponsiveSplit으로 교체(사용자 요청).
        com.phonelock.desktop.ui.components.ResponsiveSplit(
            modifier = Modifier.weight(1f),
            leftWeight = 1.1f,
            rightWeight = 0.9f,
            left = {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        com.phonelock.desktop.ui.components.Overline("${year}년")
                        Text(MONTHS_KO[month], style = MaterialTheme.typography.headlineLarge)
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            repository.archiveOldCalendarTasks()
                            dayRefreshTick++
                        }
                    }) {
                        Icon(Icons.Outlined.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("오래된 일정 정리", maxLines = 1, softWrap = false)
                    }
                    // 98차(사용자 요청, 안드로이드판은 당겨서 새로고침) — 데스크탑은 스와이프 제스처가 없어 버튼으로.
                    androidx.compose.material3.IconButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { if (!repository.isEffectivelyOffline()) repository.syncCalendarFromFirebase() }
                            refresh()
                        }
                    }) { Icon(Icons.Outlined.Refresh, contentDescription = "새로고침", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    androidx.compose.material3.IconButton(onClick = { if (month == 0) { month = 11; year-- } else month-- }) {
                        Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "이전 달")
                    }
                    androidx.compose.material3.IconButton(onClick = { if (month == 11) { month = 0; year++ } else month++ }) {
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "다음 달")
                    }
                }
                Spacer(Modifier.height(Spacing.sm))

                Row(Modifier.fillMaxWidth()) {
                    WEEKDAYS_KO.forEachIndexed { i, d ->
                        val c = when (i) { 0 -> sundayColor; 6 -> saturdayColor; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                        Text(d, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = c)
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                com.phonelock.desktop.ui.components.Hairline()

                val firstOfMonth = LocalDate.of(year, month + 1, 1)
                val firstDow = firstOfMonth.dayOfWeek.value % 7
                val daysInMonth = firstOfMonth.lengthOfMonth()
                val rows = (firstDow + daysInMonth + 6) / 7
                val tasksByDate = monthTasks.groupBy { it.dateKey }

                // 그리드 영역이 남는 세로 공간을 다 차지하도록(웹앱 .calendar-grid도 flex:1) 행마다
                // weight(1f)로 균등 분배 — 이전엔 셀 높이가 72dp 고정이라 창이 커도 그 아래가 빈 채로
                // 남았다.
                val lineColor = MaterialTheme.colorScheme.outlineVariant
                Column(Modifier.weight(1f)) {
                for (row in 0 until rows) {
                    // 칸마다 테두리 대신 줄 사이 가는 선(144차).
                    Row(Modifier.fillMaxWidth().weight(1f).drawBehind {
                        drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1f)
                    }) {
                        for (col in 0 until 7) {
                            val dayNum = row * 7 + col - firstDow + 1
                            if (dayNum in 1..daysInMonth) {
                                val date = LocalDate.of(year, month + 1, dayNum)
                                val key = date.toString()
                                val dayTasks = tasksByDate[key].orEmpty()
                                val isToday = date == today
                                val isSelected = selectedDate == date
                                // 웹앱 .day-cell.today는 셀 전체가 아니라 날짜 숫자만 원형 배지로 강조한다
                                // (.day-cell.today .day-num { background: accent; border-radius: 50% }).
                                Box(
                                    Modifier.weight(1f).fillMaxHeight().padding(2.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                        .clickable { selectedDate = date }
                                        .padding(4.dp)
                                ) {
                                    Column {
                                        val dayNumColor = when {
                                            isToday -> MaterialTheme.colorScheme.onPrimary
                                            date.dayOfWeek == java.time.DayOfWeek.SUNDAY -> sundayColor
                                            date.dayOfWeek == java.time.DayOfWeek.SATURDAY -> saturdayColor
                                            else -> MaterialTheme.colorScheme.onBackground
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier
                                                    .size(26.dp)
                                                    .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                                    .then(if (isSelected && !isToday) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else Modifier),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("$dayNum", style = MaterialTheme.typography.labelLarge, color = dayNumColor, maxLines = 1, softWrap = false)
                                            }
                                            // 모바일판과 동일하게, 그날 일정 총 개수 + 완료 정도를 색깔 배지로 요약해서
                                            // 보여준다(사용자 요청) — 전체완료=초록, 일부완료=노랑, 미완료=빨강.
                                            if (dayTasks.isNotEmpty()) {
                                                Spacer(Modifier.width(4.dp))
                                                val doneCount = dayTasks.count { it.status == "O" }
                                                val badgeColor = when {
                                                    doneCount == dayTasks.size -> palette.fillGood
                                                    doneCount > 0 -> palette.fillPartial
                                                    else -> palette.fillBad
                                                }
                                                Box(Modifier.size(6.dp).background(badgeColor, CircleShape))
                                                Spacer(Modifier.width(3.dp))
                                                Text("${dayTasks.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                            }
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        dayTasks.take(3).forEach { t ->
                                            TaskChip(
                                                task = t,
                                                modifier = Modifier.fillMaxWidth().padding(top = 1.dp)
                                            )
                                        }
                                        if (dayTasks.size > 3) {
                                            Text("+${dayTasks.size - 3}개 더", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            } else {
                                Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp))
                            }
                        }
                    }
                }
                }
            }
            },
            right = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    val date = selectedDate
                    if (date == null) {
                        Text("날짜를 클릭하면 그날의 일정을 확인할 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        DayDetailSection(repository = repository, date = date, onChanged = { dayRefreshTick++ })
                    }
                }
            }
        )
    }
}

/**
 * 고른 날짜의 상세(폰은 월 그리드 아래, 태블릿은 오른쪽 칸).
 *
 * 146차: 흰 판 안에 일정마다 상자 + 이모지 색 버튼 5개였던 구조를 기준 화면(차단 규칙 목록)과 같은 문법으로 —
 * 위에 "완료 n / 전체"를 큰 숫자로, 아래는 가는 선으로 나뉜 일정 줄. 줄마다 완료·미완료는 아이콘 두 개로 바로 누르고,
 * 나머지(이름·회차·연결·반복·순서·이동·복사·삭제)는 더보기 메뉴에 둔다.
 */
@Composable
private fun DayDetailSection(repository: Repository, date: LocalDate, onChanged: () -> Unit) {
    val dateKey = date.toString()
    var tasks by remember(dateKey) { mutableStateOf(repository.getCalendarTasks(dateKey)) }
    var newTaskName by remember(dateKey) { mutableStateOf("") }
    var studyLog by remember(dateKey) { mutableStateOf(repository.getStudyLogForDate(dateKey)) }

    // 이 날짜 상세 패널이 열려 있는 동안 5초마다 다른 기기 기록을 다시 읽어온다 — 예전엔 진입 시
    // 1회만 동기화해서, 패널을 계속 켜둔 채 다른 기기에서 방금 기록을 남겨도 반영되지 않았다.
    LaunchedEffect(dateKey) {
        while (true) {
            withContext(Dispatchers.IO) { repository.syncStudyLogFromFirebase(dateKey) }
            studyLog = repository.getStudyLogForDate(dateKey)
            delay(5000)
        }
    }

    fun refreshDay() {
        tasks = repository.getCalendarTasks(dateKey)
        onChanged()
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
                    dateKey = dateKey,
                    task = task,
                    ordinal = ordinal,
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
                    repository.addCalendarTask(dateKey, newTaskName)
                    newTaskName = ""
                    refreshDay()
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
 * 이식, 51차 신규). 완료 체크하면 그 계산기 업무의 progress가 자동으로 늘어난다(Repository.
 * addLinkedCalendarTask/setCalendarTaskStatus 참고).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LinkedCalcSection(repository: Repository, dateKey: String, onChanged: () -> Unit) {
    val calcTasks = remember(dateKey) { repository.getCalcTasks().filter { it.name.isNotBlank() } }
    var selected by remember(dateKey) { mutableStateOf<String?>(null) }
    var fromText by remember(dateKey) { mutableStateOf("") }
    var toText by remember(dateKey) { mutableStateOf("") }
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
                        repository.addLinkedCalendarTask(dateKey, name, from, to)
                        fromText = ""
                        toText = ""
                        onChanged()
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
    repository: Repository,
    dateKey: String,
    ordinal: Int,
    task: CalendarTask,
    onChanged: () -> Unit,
    onCancel: () -> Unit
) {
    val calcTasks = remember(task) { repository.getCalcTasks().filter { it.name.isNotBlank() } }
    var selected by remember(task) { mutableStateOf(task.linkedCalc) }
    var amountText by remember(task) { mutableStateOf(task.progressStep ?: "") }

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
                    repository.setCalendarTaskLink(dateKey, ordinal, name, amountText.ifBlank { null })
                    onChanged()
                },
                enabled = selected != null
            ) { Text("적용", maxLines = 1, softWrap = false) }
            TextButton(onClick = {
                repository.setCalendarTaskLink(dateKey, ordinal, null, null)
                onChanged()
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
    repository: Repository,
    dateKey: String,
    task: CalendarTask,
    ordinal: Int,
    isFirst: Boolean,
    isLast: Boolean,
    loggedSeconds: Int?,
    onChanged: () -> Unit
) {
    var editingName by remember(task) { mutableStateOf(false) }
    var nameText by remember(task) { mutableStateOf(task.name) }
    var showPassPicker by remember(task) { mutableStateOf(false) }
    var showMoveCopy by remember(task) { mutableStateOf<String?>(null) }
    var targetDateText by remember(task) { mutableStateOf("") }
    var showLinkEditor by remember(task) { mutableStateOf(false) }
    var menuOpen by remember(task) { mutableStateOf(false) }
    val palette = LocalPhoneLockPalette.current

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // 회차 색 점 — 누르면 회차를 바꾼다(웹앱에서 이름을 눌러 색을 고르던 동작과 같다).
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable { showPassPicker = !showPassPicker },
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(10.dp).background(passAccentColor(task), CircleShape)) }
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
                repository.setCalendarTaskStatus(dateKey, ordinal, "O"); onChanged()
            }
            StatusToggle(Icons.Filled.Close, "미완료", task.status == "X", MaterialTheme.colorScheme.error) {
                repository.setCalendarTaskStatus(dateKey, ordinal, "X"); onChanged()
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
                        onClick = { menuOpen = false; repository.setCalendarTaskMultiPass(dateKey, ordinal, !task.multiPassEnabled); onChanged() }
                    )
                    if (!isFirst) DropdownMenuItem(text = { Text("위로") }, onClick = { menuOpen = false; repository.moveCalendarTaskOrder(dateKey, ordinal, -1); onChanged() })
                    if (!isLast) DropdownMenuItem(text = { Text("아래로") }, onClick = { menuOpen = false; repository.moveCalendarTaskOrder(dateKey, ordinal, 1); onChanged() })
                    DropdownMenuItem(text = { Text("다른 날로 옮기기") }, onClick = { menuOpen = false; showMoveCopy = "move" })
                    DropdownMenuItem(text = { Text("다른 날로 복사") }, onClick = { menuOpen = false; showMoveCopy = "copy" })
                    DropdownMenuItem(
                        text = { Text("삭제", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; repository.deleteCalendarTask(dateKey, ordinal); onChanged() }
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
                                repository.setCalendarTaskPassIndex(dateKey, ordinal, idx); showPassPicker = false; onChanged()
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
                        if (nameText.isNotBlank()) repository.renameCalendarTask(dateKey, ordinal, nameText)
                        editingName = false
                        onChanged()
                    }) { Text("저장") }
                    TextButton(onClick = { editingName = false; nameText = task.name }) { Text("취소") }
                }
            }
        }

        if (showLinkEditor) {
            LinkEditorPanel(
                repository = repository,
                dateKey = dateKey,
                ordinal = ordinal,
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
                    com.phonelock.desktop.ui.components.DatePickerField(
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
                                if (showMoveCopy == "move") repository.moveCalendarTaskToDate(dateKey, ordinal, target)
                                else repository.copyCalendarTaskToDate(dateKey, ordinal, target)
                                showMoveCopy = null
                                targetDateText = ""
                                onChanged()
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
