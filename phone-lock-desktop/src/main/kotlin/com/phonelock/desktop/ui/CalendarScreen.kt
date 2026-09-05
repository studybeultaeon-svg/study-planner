package com.phonelock.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val MONTHS_KO = arrayOf("1월", "2월", "3월", "4월", "5월", "6월", "7월", "8월", "9월", "10월", "11월", "12월")
private val WEEKDAYS_KO = arrayOf("일", "월", "화", "수", "목", "금", "토")
// 77차: 8단계(51차)에서 3단계(빨/노/초)로 축소(사용자 요청). 예전 색(white/orange/blue/indigo/purple)의
// 라벨은 지웠지만 stageTextColor의 색상값 자체는 남겨둬서, 과거에 그 색으로 저장된 일정은 여전히
// 고유한 색으로 표시된다(51차와 같은 "라벨만 바뀌는" 전례, HANDOFF.md 참고).
private val COLOR_LABEL = mapOf("red" to "1회독", "yellow" to "2회독", "green" to "3회독")

/**
 * 51차: 4단계(빨주노초)→7단계 무지개(빨주노초파남보)→8단계(사용자 요청) — 1회독을 "하얀색"으로 새로
 * 두고 기존 빨주노초파남보는 2~8회독으로 한 칸씩 밀렸다. white는 완전한 흰색(#FFFFFF)이면 이 앱의
 * 밝은 배경(라이트+그린 테마, 49차)에서 텍스트/테두리가 안 보이므로, 대신 은은한 회색조로 표현했다.
 */
internal fun stageTextColor(stage: String): Color = when (stage) {
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

internal data class ChipColors(val bg: Color, val border: Color)

/** 웹앱 `.task-chip.{color}-task`의 배경/테두리(월 그리드 배지 전용). 배경은 accent를 옅게 탄 라이트
 *  테마용 틴트, 테두리는 accent 그대로 — stageTextColor와 같은 accent를 공유한다. */
internal fun stageChipColors(stage: String): ChipColors {
    val accent = stageTextColor(stage)
    return ChipColors(accent.copy(alpha = 0.15f), accent)
}

/** 83차(다회독 상세화) — passIndex/passTotal 기반 빨강→초록 그라데이션 accent. */
internal fun passAccentColor(task: CalendarTask): Color = Color(com.phonelock.shared.calc.PassSchedule.passColor(task.passIndex, task.passTotal))
internal fun passLabel(task: CalendarTask): String = "${task.passIndex + 1}회독"

private fun dowLabel(date: LocalDate): String = WEEKDAYS_KO[date.dayOfWeek.value % 7]

/**
 * 웹앱 월 그리드의 `.task-chip` — 배경/테두리를 회독 색으로 채운 배지, O/X 완료 표시는 칩 색과
 * 별개로 항상 초록/빨강(`.status-O::before`/`.status-X::before`). 83차부터 색상은 task.passIndex/
 * passTotal 기반 그라데이션(레거시 color 문자열 대신).
 */
/** 모임 멤버 상세(다른 사용자의 동기화된 일정 요약, passIndex/passTotal 없음)용 레거시 오버로드 — 그
 *  화면은 이번 다회독 상세화 범위 밖이라 기존 color 문자열 기반 렌더링을 그대로 유지한다. */
@Composable
internal fun TaskChip(name: String, stage: String, status: String?, modifier: Modifier = Modifier) {
    val chip = stageChipColors(stage)
    Text(
        buildAnnotatedString {
            if (status == "O") withStyle(SpanStyle(color = Color(0xFF34D399), fontWeight = FontWeight.Black)) { append("O ") }
            else if (status == "X") withStyle(SpanStyle(color = Color(0xFFF87171), fontWeight = FontWeight.Black)) { append("X ") }
            append(name)
        },
        modifier = modifier
            .background(chip.bg, MaterialTheme.shapes.extraSmall)
            .border(1.dp, chip.border, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = stageTextColor(stage),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun TaskChip(task: CalendarTask, modifier: Modifier = Modifier) {
    val accent = passAccentColor(task)
    Text(
        buildAnnotatedString {
            if (task.status == "O") withStyle(SpanStyle(color = Color(0xFF34D399), fontWeight = FontWeight.Black)) { append("O ") }
            else if (task.status == "X") withStyle(SpanStyle(color = Color(0xFFF87171), fontWeight = FontWeight.Black)) { append("X ") }
            append(task.name)
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

/**
 * 네이티브 캘린더(2단계). 웹앱 index.html "캘린더" 탭을 이식 — 월 그리드 + 아래 선택된 날짜 상세
 * 섹션(웹앱은 모달이었지만 데스크탑은 화면이 넓어 인라인 섹션으로), 완료/미완료·순서·이동/복사·삭제,
 * 색상(회독) 변경, 정리(6개월 이전 삭제)까지 원본 로직 그대로. DECISIONS.md/CLAUDE.md 참고.
 */
@Composable
fun CalendarScreen(repository: Repository) {
    var year by remember { mutableStateOf(LocalDate.now().year) }
    var month by remember { mutableStateOf(LocalDate.now().monthValue - 1) }
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
        withContext(Dispatchers.IO) { repository.syncCalendarFromFirebase() }
        refresh()
    }
    LaunchedEffect(year, month, dayRefreshTick) { refresh() }

    Column(Modifier.fillMaxSize().padding(Spacing.md)) {
        Text("📅 캘린더", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.md))

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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = { if (month == 0) { month = 11; year-- } else month-- }) {
                        Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("이전")
                    }
                    Text("${year}년 ${MONTHS_KO[month]}", style = MaterialTheme.typography.titleLarge)
                    Row {
                        OutlinedButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                repository.archiveOldCalendarTasks()
                                dayRefreshTick++
                            }
                        }) { Text("🧹 정리") }
                        Spacer(Modifier.width(Spacing.sm))
                        OutlinedButton(onClick = { if (month == 11) { month = 0; year++ } else month++ }) {
                            Text("다음")
                            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))

                Row(Modifier.fillMaxWidth()) {
                    WEEKDAYS_KO.forEachIndexed { i, d ->
                        val c = when (i) { 0 -> Color(0xFFF87171); 6 -> Color(0xFF6B9FFF); else -> MaterialTheme.colorScheme.onSurface }
                        Text(d, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = c)
                    }
                }

                val firstOfMonth = LocalDate.of(year, month + 1, 1)
                val firstDow = firstOfMonth.dayOfWeek.value % 7
                val daysInMonth = firstOfMonth.lengthOfMonth()
                val rows = (firstDow + daysInMonth + 6) / 7
                val today = LocalDate.now()
                val tasksByDate = monthTasks.groupBy { it.dateKey }

                // 그리드 영역이 남는 세로 공간을 다 차지하도록(웹앱 .calendar-grid도 flex:1) 행마다
                // weight(1f)로 균등 분배 — 이전엔 셀 높이가 72dp 고정이라 창이 커도 그 아래가 빈 채로
                // 남았다.
                Column(Modifier.weight(1f)) {
                for (row in 0 until rows) {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
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
                                        .border(
                                            if (isSelected) 2.dp else 1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            MaterialTheme.shapes.small
                                        )
                                        .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, MaterialTheme.shapes.small)
                                        .clickable { selectedDate = date }
                                        .padding(4.dp)
                                ) {
                                    Column {
                                        val dayNumColor = when {
                                            isToday -> MaterialTheme.colorScheme.onPrimary
                                            date.dayOfWeek == java.time.DayOfWeek.SUNDAY -> Color(0xFFF87171)
                                            date.dayOfWeek == java.time.DayOfWeek.SATURDAY -> Color(0xFF6B9FFF)
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier
                                                    .size(20.dp)
                                                    .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("$dayNum", style = MaterialTheme.typography.labelSmall, color = dayNumColor, fontWeight = FontWeight.Bold)
                                            }
                                            // 모바일판과 동일하게, 그날 일정 총 개수 + 완료 정도를 색깔 배지로 요약해서
                                            // 보여준다(사용자 요청) — 전체완료=초록, 일부완료=노랑, 미완료=빨강.
                                            if (dayTasks.isNotEmpty()) {
                                                Spacer(Modifier.width(4.dp))
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

    val secondsByTaskName = remember(studyLog) { studyLog.groupBy { it.taskName }.mapValues { (_, entries) -> entries.sumOf { it.seconds } } }
    val totalSeconds = secondsByTaskName.values.sum()

    fun refreshDay() {
        tasks = repository.getCalendarTasks(dateKey)
        onChanged()
    }

    val title = "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일 (${dowLabel(date)})" +
        if (totalSeconds > 0) " · ⏱ 총 ${formatHmsLog(totalSeconds.toLong())}" else ""

    SectionCard(title) {
        if (tasks.isEmpty()) {
            Text("등록된 업무가 없습니다.", style = MaterialTheme.typography.bodySmall)
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
                    repository.addCalendarTask(dateKey, newTaskName)
                    newTaskName = ""
                    refreshDay()
                }
            }) { Text("+ 추가") }
        }
    }
    Spacer(Modifier.height(Spacing.md))
    LinkedCalcSection(repository = repository, dateKey = dateKey, onChanged = { refreshDay() })
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
    if (calcTasks.isEmpty()) return
    var selected by remember(dateKey) { mutableStateOf<String?>(null) }
    var fromText by remember(dateKey) { mutableStateOf("") }
    var toText by remember(dateKey) { mutableStateOf("") }

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
                        repository.addLinkedCalendarTask(dateKey, name, from, to)
                        fromText = ""
                        toText = ""
                        onChanged()
                    }
                }) { Text("추가") }
            }
        }
    }
}

/**
 * 캘린더 일정 하나의 계산기 연동 설정 편집(86차 신규, 안드로이드판 LinkEditorPanel과 대칭) — 생성 시
 * (LinkedCalcSection)에만 정할 수 있던 연결을 업무마다 나중에 바꿀 수 있게 하는 작은 패널. 다른 계산기
 * 업무로 재연결, 완전 해제, 완료 시 반영될 할당량(progressStep) 수정을 한 곳에서 처리한다. "적용"은
 * 선택된 업무가 지금 연결과 같아도 실행되므로, 그 업무의 회독 설정이 나중에 바뀐 경우 다시 맞추는
 * 초기화 용도로도 쓰인다.
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

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), MaterialTheme.shapes.small)
            .padding(Spacing.sm)
            .padding(top = Spacing.xs)
    ) {
        Text("업무 연결 설정", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.xs))
        if (calcTasks.isEmpty()) {
            Text("등록된 계산기 업무가 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                calcTasks.forEach { t ->
                    val isSelected = selected == t.name
                    OutlinedButton(
                        onClick = { selected = t.name },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        colors = if (isSelected) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        } else ButtonDefaults.outlinedButtonColors()
                    ) { Text(t.name, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("완료 시 반영될 할당량") },
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Button(
                onClick = {
                    val name = selected ?: return@Button
                    repository.setCalendarTaskLink(dateKey, ordinal, name, amountText.ifBlank { null })
                    onChanged()
                },
                enabled = selected != null
            ) { Text("적용") }
            OutlinedButton(onClick = {
                repository.setCalendarTaskLink(dateKey, ordinal, null, null)
                onChanged()
            }) { Text("연결 해제") }
            TextButton(onClick = onCancel) { Text("취소") }
        }
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
    var showColorPicker by remember(task) { mutableStateOf(false) }
    var showMoveCopy by remember(task) { mutableStateOf<String?>(null) }
    var targetDateText by remember(task) { mutableStateOf("") }
    var showLinkEditor by remember(task) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
            .padding(Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column {
                com.phonelock.desktop.ui.components.IconChip(
                    Icons.Filled.KeyboardArrowUp, enabled = !isFirst,
                    onClick = { repository.moveCalendarTaskOrder(dateKey, ordinal, -1); onChanged() }
                )
                com.phonelock.desktop.ui.components.IconChip(
                    Icons.Filled.KeyboardArrowDown, enabled = !isLast,
                    onClick = { repository.moveCalendarTaskOrder(dateKey, ordinal, 1); onChanged() }
                )
            }
            Spacer(Modifier.width(Spacing.xs))
            if (editingName) {
                Box(Modifier.size(10.dp).background(passAccentColor(task), CircleShape))
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
                        color = passAccentColor(task)
                    )
                    // 이름 아래 회독 라벨 옆 빈 공간에 이 업무를 실제로 잰 시간을 붙여 보여준다.
                    val metaLine = passLabel(task)
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
                    .clickable { repository.setCalendarTaskMultiPass(dateKey, ordinal, !task.multiPassEnabled); onChanged() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
            // 85차: 스마트폰에서 업무 이름이 과하게 줄바꿈되는 문제 해결을 위해 다회독/미완 버튼 사이의
            // "다음 회독 주기" 입력칸을 제거해 이름에 폭을 더 준다(사용자 요청, 안드로이드판과 대칭) —
            // task.nextDays 자체는 여전히 CalendarTask에 남아 자동 회독 생성 시(applyCalendarAutoSchedule)
            // 커스텀 간격으로 쓰이지만, 그 값을 직접 입력하는 UI만 뺐다.
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
            // 83차: 회독 수가 업무마다 다를 수 있으므로(3~8) 고정 3개가 아니라 task.passTotal만큼 보여준다.
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.padding(top = Spacing.xs).horizontalScroll(rememberScrollState())
            ) {
                (task.passTotal - 1 downTo 0).forEach { idx ->
                    val stageColor = Color(com.phonelock.shared.calc.PassSchedule.passColor(idx, task.passTotal))
                    OutlinedButton(
                        onClick = { repository.setCalendarTaskPassIndex(dateKey, ordinal, idx); showColorPicker = false; onChanged() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = stageColor),
                        border = BorderStroke(1.dp, stageColor.copy(alpha = 0.5f))
                    ) { Text("${idx + 1}회독", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }

        if (editingName) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
                TextButton(onClick = {
                    if (nameText.isNotBlank()) repository.renameCalendarTask(dateKey, ordinal, nameText)
                    editingName = false
                    onChanged()
                }) { Text("저장") }
                TextButton(onClick = { editingName = false; nameText = task.name }) { Text("취소") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { editingName = true },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) { Text("✏️ 이름 수정", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                // 86차: 계산기 업무 연결은 생성 시(LinkedCalcSection)에만 설정할 수 있었는데, 업무마다
                // 개별적으로 연결 해제/변경/할당량 수정이 가능하도록 작은 버튼 하나만 추가(안드로이드판과
                // 대칭, 사용자 요청 — 공간을 많이 차지하지 않게).
                TextButton(
                    onClick = { showLinkEditor = !showLinkEditor },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        if (task.linkedCalc != null) "🔗 ${task.linkedCalc}" else "🔗 업무 연결",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (task.linkedCalc != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

        // 웹앱 .modal-actions .modal-btn — 버튼 5개가 flex:1로 균등하게 폭을 나눠 차지하고,
        // 각각 다른 틴트색(.btn-done/.btn-undone/.btn-move/.btn-copy/.btn-remove).
        val green = Color(0xFF34D399)
        val red = Color(0xFFF87171)
        val purple = MaterialTheme.colorScheme.secondary
        val actionButtonPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
            OutlinedButton(
                onClick = { repository.setCalendarTaskStatus(dateKey, ordinal, "O"); onChanged() },
                colors = ButtonDefaults.outlinedButtonColors(containerColor = green.copy(alpha = 0.15f), contentColor = green),
                border = BorderStroke(1.dp, green.copy(alpha = 0.35f)),
                contentPadding = actionButtonPadding,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) { Text("✔ 완료", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { repository.setCalendarTaskStatus(dateKey, ordinal, "X"); onChanged() },
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
                onClick = { repository.deleteCalendarTask(dateKey, ordinal); onChanged() },
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
                        if (showMoveCopy == "move") repository.moveCalendarTaskToDate(dateKey, ordinal, target)
                        else repository.copyCalendarTaskToDate(dateKey, ordinal, target)
                        showMoveCopy = null
                        targetDateText = ""
                        onChanged()
                    }
                }) { Text("확인") }
                TextButton(onClick = { showMoveCopy = null }) { Text("취소") }
            }
        }
    }
}
