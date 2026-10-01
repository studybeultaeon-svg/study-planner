package com.phonelock.desktop.ui

import com.phonelock.desktop.ui.theme.LocalPhoneLockPalette
import com.phonelock.desktop.ui.theme.LocalAppMotion
import com.phonelock.desktop.ui.components.StatRow
import com.phonelock.desktop.ui.components.StatBlock
import com.phonelock.desktop.ui.components.SectionTabs
import com.phonelock.desktop.ui.components.ProgressLine
import com.phonelock.desktop.ui.components.PageMasthead
import com.phonelock.desktop.ui.components.Overline
import com.phonelock.desktop.ui.components.LedgerSection
import com.phonelock.desktop.ui.components.Hairline
import com.phonelock.desktop.ui.components.BigNumber
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import com.phonelock.shared.routine.RoutineRepeat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.*
import com.phonelock.desktop.data.Routine
import com.phonelock.desktop.routine.RoutineEngine
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val ROUTINE_WEEKDAYS_KO = arrayOf("월", "화", "수", "목", "금", "토", "일")
private val ROUTINE_WEEKDAYS_SUN_FIRST = arrayOf("일", "월", "화", "수", "목", "금", "토")

private fun bitIndexFor(date: LocalDate): Int = date.dayOfWeek.value - 1

// 134차: 예정일 판정은 RoutineEngine 하나만 쓴다 — 여기에 같은 로직을 복사해 두었더니 반복 방식이
// 늘었을 때(며칠마다·매월 날짜) 이 화면만 옛 규칙으로 목록을 그렸다(에뮬레이터 확인에서 발견).
private fun isScheduledOn(routine: Routine, date: LocalDate): Boolean = RoutineEngine.isScheduledOn(routine, date)

/**
 * 루틴앱 v1(47~48차 설계, DECISIONS.md 참고) 메인 화면 — "오늘"(체크리스트, 시간대 지정 루틴은 시간순으로
 * 정렬해 일과표 역할까지 겸함)/"통계"(활동 기반 집계) 2개 내부 서브탭. 51차: 스트릭을 루틴별이 아니라
 * "하루" 단위 전역 스트릭으로 전면 개편(RoutineEngine.kt 참고) — 그날 예정된 루틴을 전부 완료해야 그날이
 * 스트릭에 +1되고, 하나라도 미완료면 그 자리에서 끊긴다(방어권 없음). 편집은 RoutineEditDialog(별도
 * 화면 대신 다이얼로그)로 처리.
 */
@Composable
fun RoutineScreen(repository: Repository) {
    val scope = rememberCoroutineScope()
    var subTab by remember { mutableIntStateOf(0) }
    var routines by remember { mutableStateOf(repository.getRoutines()) }
    var editing by remember { mutableStateOf<Routine?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var weekOffset by remember { mutableStateOf(0) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    // 91차(90차 지정 3번, 사용자 요청): 오른쪽 컬럼의 "오늘 요약" 통계 대신, 루틴을 클릭하면 그 루틴의
    // 상세 정보가 뜨는 마스터-디테일 패턴으로 변경. GroupListScreen/GroupEditScreen과 같은 방식.
    var selectedRoutineId by remember { mutableStateOf<Long?>(null) }
    // 체크박스를 눌러도 Routine 목록 자체(제목/요일 등)는 안 바뀌어서 routines를 재할당해도 값이 구조적으로
    // 동일하면 Compose가 변경으로 인식하지 못해 화면이 갱신 안 되는 버그가 있었다(그룹 탭에서도 같은 패턴이
    // 있었음, MainScreen.kt 참고). refreshTick은 매번 다른 값이 되므로 key()로 감싸 확실히 재구성시킨다.
    var refreshTick by remember { mutableIntStateOf(0) }

    fun refresh() {
        routines = repository.getRoutines()
        refreshTick++
    }

    LaunchedEffect(Unit) {
        // 98차(온라인/오프라인 모드): 오프라인이면 네트워크 타임아웃만 기다리게 되므로 아예 건너뛴다.
        withContext(Dispatchers.IO) {
            if (!repository.isEffectivelyOffline()) {
                repository.syncRoutinesFromFirebase()
                repository.syncPointsFromFirebase()
            }
        }
        refresh()
    }

    // 144차 리디자인(안드로이드판과 같은 언어): 편집형 머리 + 텍스트 탭, 테두리 칩 대신 요일 띠.
    Column(Modifier.fillMaxSize()) {
        val realTodayForHeader = LocalDate.now()
        PageMasthead(
            title = "루틴",
            overline = "${realTodayForHeader.monthValue}월 ${realTodayForHeader.dayOfMonth}일 ${ROUTINE_WEEKDAYS_KO[bitIndexFor(realTodayForHeader)]}요일"
        ) {
            // 98차(사용자 요청, 안드로이드판은 당겨서 새로고침) — 데스크탑은 스와이프 제스처가 없어 버튼으로.
            IconButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        if (!repository.isEffectivelyOffline()) {
                            repository.syncRoutinesFromFirebase()
                            repository.syncPointsFromFirebase()
                        }
                    }
                    refresh()
                }
            }) { androidx.compose.material3.Icon(Icons.Outlined.Refresh, contentDescription = "새로고침", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            FilledTonalButton(onClick = { showAddDialog = true }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                androidx.compose.material3.Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("루틴 추가", maxLines = 1, softWrap = false)
            }
        }
        SectionTabs(listOf("오늘", "연속 기록"), subTab, { subTab = it })

        Column(Modifier.fillMaxSize().padding(horizontal = Spacing.lg)) {
            if (subTab == 0) {
                Spacer(Modifier.height(Spacing.sm))
                val realToday = LocalDate.now()
                val currentSunday = realToday.minusDays(realToday.dayOfWeek.value.toLong() % 7)
                val sunday = currentSunday.plusWeeks(weekOffset.toLong())
                val weekDates = (0..6).map { sunday.plusDays(it.toLong()) }
                Row(Modifier.widthIn(max = 640.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { weekOffset-- }) { androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "이전 주", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(Modifier.weight(1f)) {
                        weekDates.forEachIndexed { i, d ->
                            WeekDayCell(
                                weekday = ROUTINE_WEEKDAYS_SUN_FIRST[i],
                                day = d.dayOfMonth,
                                selected = d == selectedDate,
                                isToday = d == realToday,
                                modifier = Modifier.weight(1f)
                            ) { selectedDate = d }
                        }
                    }
                    IconButton(onClick = { weekOffset++ }) { androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "다음 주", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(Spacing.md))
            }

            if (routines.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text("아직 루틴이 없습니다", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "매일 하는 일, 정해진 시간의 일과, 며칠마다 돌아오는 일을 하나로 관리합니다. 오른쪽 위 \"루틴 추가\"로 시작해 보세요.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Box(Modifier.weight(1f)) {
                    key(refreshTick) {
                        when (subTab) {
                            0 -> RoutineTodayTab(
                                repository, routines, selectedDate,
                                onEdit = { editing = it },
                                onChanged = { refresh() },
                                onSwap = { a, b -> repository.swapRoutineOrder(a, b); refresh() },
                                selectedRoutineId = selectedRoutineId,
                                onSelect = { selectedRoutineId = it }
                            )
                            1 -> RoutineStatsTab(repository, routines)
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        RoutineEditDialog(
            routine = null,
            onDismiss = { showAddDialog = false },
            onSave = { r -> repository.addRoutine(r); showAddDialog = false; refresh() }
        )
    }
    editing?.let { r ->
        RoutineEditDialog(
            routine = r,
            onDismiss = { editing = null },
            onSave = { updated -> repository.updateRoutine(updated); editing = null; refresh() },
            onDelete = { repository.deleteRoutine(r.id); editing = null; refresh() },
            onCopy = { repository.copyRoutine(r); editing = null; refresh() }
        )
    }
}

/** 주간 띠의 하루 — 요일(작게) + 날짜(굵게). 고른 날은 먹색 원 안에, 오늘은 강조색 점(안드로이드판과 같은 부품). */
@Composable
private fun WeekDayCell(weekday: String, day: Int, selected: Boolean, isToday: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val motion = LocalAppMotion.current
    val fill by animateColorAsState(if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent, motion.quick(), label = "dayFill")
    val ink = if (selected) MaterialTheme.colorScheme.background else if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
    Column(
        modifier.heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(weekday, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.size(32.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
            Text("$day", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W700), color = ink, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(4.dp).background(if (isToday && !selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape))
    }
}

@Composable
private fun RoutineTodayTab(
    repository: Repository,
    routines: List<Routine>,
    selectedDate: LocalDate,
    onEdit: (Routine) -> Unit,
    onChanged: () -> Unit,
    onSwap: (Long, Long) -> Unit,
    selectedRoutineId: Long?,
    onSelect: (Long?) -> Unit
) {
    val realToday = remember { LocalDate.now() }
    val isToday = selectedDate == realToday
    val dateKey = remember(selectedDate) { selectedDate.toString() }
    // 시간대 지정 루틴이 먼저 시간순으로, 시간대 없는 루틴은 뒤에 붙는다(일과표 탭 통합, 50차).
    val todays = routines.filter { isScheduledOn(it, selectedDate) }
        .sortedWith(compareBy(nullsLast()) { it.timeSlot })
    // 시간대 없는 루틴만 순서를 사용자가 직접 정할 수 있다(52차).
    val untimed = todays.filter { it.timeSlot == null }
    val doneCount = todays.count { repository.isRoutineCompleted(it.id, dateKey) }
    val currentStreak = remember(routines) {
        val completedByRoutine = routines.associate { it.id to repository.getRoutineCompletedDateKeys(it.id) }
        RoutineEngine.currentStreak(routines, completedByRoutine, realToday, repository.routineStreakFreezePerWeek)
    }
    val timeColumn = todays.any { it.timeSlot != null }

    // 90차(사용자 요청): 넓은 창에서 좌(목록)/우(상세)로 나눴다 — 목록이 주인공이라 2:1, 좁아지면 위아래로.
    com.phonelock.desktop.ui.components.ResponsiveSplit(leftWeight = 2f, rightWeight = 1f, left = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(end = Spacing.lg)) {
            // 144차 히어로: 고른 날의 완료 수를 크게, 연속 기록은 오른쪽에 강조색으로.
            Overline("${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일 ${ROUTINE_WEEKDAYS_KO[bitIndexFor(selectedDate)]}요일" + if (isToday) " · 오늘" else "")
            Spacer(Modifier.height(4.dp))
            if (todays.isEmpty()) {
                Text("이 날 예정된 루틴이 없습니다", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    BigNumber("$doneCount/${todays.size}", unit = "완료", style = MaterialTheme.typography.displayMedium, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Overline("연속")
                        BigNumber("$currentStreak", unit = "일", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                ProgressLine(doneCount / todays.size.toFloat())
                Spacer(Modifier.height(Spacing.md))
                todays.forEach { routine ->
                    val done = repository.isRoutineCompleted(routine.id, dateKey)
                    val untimedIdx = if (routine.timeSlot == null) untimed.indexOfFirst { it.id == routine.id } else -1
                    RoutineRow(
                        routine = routine,
                        done = done,
                        timeColumn = timeColumn,
                        selected = routine.id == selectedRoutineId,
                        onToggle = { repository.toggleRoutineLog(routine.id, dateKey); onChanged() },
                        onEdit = { onEdit(routine) },
                        onSelect = { onSelect(routine.id) },
                        onMoveUp = if (untimedIdx > 0) ({ onSwap(routine.id, untimed[untimedIdx - 1].id) }) else null,
                        onMoveDown = if (untimedIdx in 0 until untimed.lastIndex) ({ onSwap(routine.id, untimed[untimedIdx + 1].id) }) else null
                    )
                    Hairline()
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }, right = {
        // 91차: 왼쪽에서 고른 루틴의 상세 정보(마스터-디테일). 통계는 "연속 기록" 탭에 있다.
        val selected = routines.find { it.id == selectedRoutineId }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (selected == null) {
                Column(Modifier.fillMaxSize().padding(top = Spacing.xxl)) {
                    Overline("상세")
                    Text("루틴을 고르면\n여기서 자세히 볼 수 있습니다.", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                RoutineDetailPanel(repository, selected, onEdit = { onEdit(selected) })
            }
        }
    })
}

/** 91차 신규: 오른쪽 컬럼에서 선택한 루틴 하나의 상세(요일/시간대/기간/연속 기록). 144차: 큰 숫자 + 라벨-값 줄. */
@Composable
private fun RoutineDetailPanel(repository: Repository, routine: Routine, onEdit: () -> Unit) {
    val today = remember { LocalDate.now() }
    val completedDates = remember(routine.id) { repository.getRoutineCompletedDateKeys(routine.id) }
    val ownStreak = remember(routine.id, completedDates) { routineOwnStreak(routine, completedDates, today) }
    val scheduledDays = (0..6).filter { (routine.daysMask shr it) and 1 == 1 }.joinToString(", ") { ROUTINE_WEEKDAYS_KO[it] }

    Overline("상세")
    Text(
        if (routine.icon.isNotBlank()) "${routine.icon} ${routine.title}" else routine.title,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(Spacing.md))
    Overline("이 루틴 연속 기록")
    BigNumber("$ownStreak", unit = "일", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(Spacing.md))
    Hairline()
    RoutineDetailRow("반복 요일", if (scheduledDays.isEmpty()) "없음" else scheduledDays)
    RoutineDetailRow("시간대", routine.timeSlot ?: "지정 안 함")
    if (routine.startDate != null || routine.endDate != null) {
        RoutineDetailRow("기간", "${routine.startDate ?: "제한 없음"} ~ ${routine.endDate ?: "제한 없음"}")
    }
    RoutineDetailRow("알림", if (routine.notifyEnabled) "켜짐" else "꺼짐")
    RoutineDetailRow("최근 30일 완료", "${completedDates.count { it >= today.minusDays(29).toString() }}일")
    Spacer(Modifier.height(Spacing.md))
    OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("수정", maxLines = 1, softWrap = false) }
}

@Composable
private fun RoutineDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1.5f))
    }
}

/** 이 루틴 하나만 놓고 오늘부터 거슬러 올라가며 세는 연속 완료 일수 — 예정 안 된 날은 건너뛴다.
 *  RoutineEngine의 currentStreak()는 "그날 예정된 루틴 전부"를 기준으로 하는 전역 스트릭이라 이 용도엔 안 맞는다. */
private fun routineOwnStreak(routine: Routine, completedDates: Set<String>, today: LocalDate): Int {
    var streak = 0
    var d = today
    var daysChecked = 0
    while (daysChecked < 3650) {
        if (isScheduledOn(routine, d)) {
            if (d.toString() in completedDates) streak++ else break
        }
        d = d.minusDays(1)
        daysChecked++
    }
    return streak
}

private data class RoutineDayStat(val date: LocalDate, val scheduled: Int, val done: Int)

/** dateKey(yyyy-MM-dd)가 [fromInclusive, toInclusive] 범위 안에서, 예정된 루틴 대비 완료 개수. */
private fun routineRateInRange(
    routines: List<Routine>,
    completedByRoutine: Map<Long, Set<String>>,
    from: LocalDate,
    to: LocalDate
): Pair<Int, Int> {
    var done = 0
    var total = 0
    var d = from
    while (!d.isAfter(to)) {
        val key = d.toString()
        routines.forEach { r ->
            if (isScheduledOn(r, d)) {
                total++
                if (key in (completedByRoutine[r.id] ?: emptySet())) done++
            }
        }
        d = d.plusDays(1)
    }
    return done to total
}

/**
 * 루틴 통계(51차 최고 스트릭 중심) — 144차: 현재 연속 기록을 아주 큰 숫자로, 오늘·최고는 세 칸, 주간 비교는 큰 퍼센트 + 증감,
 * 30일 추이는 막대(색은 테마 팔레트). 넓은 창은 좌(요약)/우(그래프). 안드로이드판과 대칭.
 */
@Composable
private fun RoutineStatsTab(repository: Repository, routines: List<Routine>) {
    val palette = LocalPhoneLockPalette.current
    val today = remember { LocalDate.now() }
    val dateKey = remember { today.toString() }
    val completedByRoutine = remember(routines) {
        routines.associate { it.id to repository.getRoutineCompletedDateKeys(it.id) }
    }

    val scheduledToday = routines.filter { isScheduledOn(it, today) }
    val doneToday = scheduledToday.count { dateKey in (completedByRoutine[it.id] ?: emptySet()) }
    val todayRate = if (scheduledToday.isNotEmpty()) Math.round(doneToday * 100.0 / scheduledToday.size).toInt() else 0

    val freezePerWeek = remember { repository.routineStreakFreezePerWeek }
    val currentStreak = RoutineEngine.currentStreak(routines, completedByRoutine, today, freezePerWeek)
    val bestStreak = RoutineEngine.bestStreak(routines, completedByRoutine, today, freezePerWeek)
    val freezeUsed = RoutineEngine.freezeUsedThisWeek(routines, completedByRoutine, today, freezePerWeek)

    val (thisDone, thisTotal) = routineRateInRange(routines, completedByRoutine, today.minusDays(6), today)
    val (lastDone, lastTotal) = routineRateInRange(routines, completedByRoutine, today.minusDays(13), today.minusDays(7))

    val dayStats = (0 until 30).map { i ->
        val d = today.minusDays((29 - i).toLong())
        val (done, total) = routineRateInRange(routines, completedByRoutine, d, d)
        RoutineDayStat(d, total, done)
    }
    val maxDayCnt = maxOf(1, dayStats.maxOf { it.scheduled })

    com.phonelock.desktop.ui.components.ResponsiveSplit(leftWeight = 1f, rightWeight = 1.4f, left = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(end = Spacing.lg)) {
            Spacer(Modifier.height(Spacing.md))
            Overline("현재 연속 기록")
            BigNumber(
                "$currentStreak",
                unit = "일",
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 96.sp, lineHeight = 98.sp, letterSpacing = (-3).sp),
                unitStyle = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                if (freezePerWeek > 0) "하루라도 예정 루틴을 다 끝내면 이어집니다 · 이번 주 방지권 $freezeUsed/$freezePerWeek 사용"
                else "하루라도 예정 루틴을 다 끝내면 이어집니다",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.lg))
            Hairline()
            Spacer(Modifier.height(Spacing.md))
            StatRow {
                StatBlock("오늘 완료", "$doneToday/${scheduledToday.size}", Modifier.weight(1f))
                StatBlock("오늘 완료율", "$todayRate", Modifier.weight(1f), unit = "%")
                StatBlock("최고 기록", "$bestStreak", Modifier.weight(1f), unit = "일")
            }
            Spacer(Modifier.height(Spacing.lg))
            if (thisTotal > 0 || lastTotal > 0) {
                val thisRate = if (thisTotal > 0) Math.round(thisDone * 100.0 / thisTotal).toInt() else 0
                LedgerSection("최근 7일 완료율") {
                    Row(verticalAlignment = Alignment.Bottom) {
                        BigNumber("$thisRate", unit = "%", style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.width(Spacing.md))
                        if (lastTotal == 0) {
                            Text("지난주 예정 루틴이 없어 비교할 수 없어요", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                        } else {
                            val lastRate = Math.round(lastDone * 100.0 / lastTotal).toInt()
                            val diff = thisRate - lastRate
                            val diffColor = when {
                                diff > 0 -> palette.success
                                diff < 0 -> palette.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text("지난주 대비 " + if (diff > 0) "+$diff%p" else "$diff%p", style = MaterialTheme.typography.titleMedium, color = diffColor, modifier = Modifier.alignByBaseline())
                        }
                    }
                    Spacer(Modifier.height(Spacing.lg))
                }
            }
        }
    }, right = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            LedgerSection("최근 30일", divider = false) {
                Text("막대 높이는 예정 개수, 색은 완료율", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.sm))
                Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dayStats.forEach { ds ->
                        val pct = if (ds.scheduled > 0) Math.round(ds.done * 100.0 / ds.scheduled).toInt() else 0
                        val barColor = when {
                            ds.scheduled == 0 -> MaterialTheme.colorScheme.outlineVariant
                            pct == 100 -> palette.success
                            pct > 0 -> palette.warning
                            else -> palette.error
                        }
                        val isToday = ds.date == today
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Column(Modifier.fillMaxWidth().height(80.dp), verticalArrangement = Arrangement.Bottom) {
                                val heightPct = (ds.scheduled.toFloat() / maxDayCnt).coerceIn(if (ds.scheduled > 0) 0.08f else 0.03f, 1f)
                                Box(Modifier.fillMaxWidth().height((80 * heightPct).dp).background(barColor, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${ds.date.dayOfMonth}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isToday) FontWeight.W700 else FontWeight.W600,
                                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    })
}

/**
 * 루틴 한 줄 — 144차: 카드 대신 줄(아래 가는 선은 호출 쪽). 줄을 누르면 오른쪽 상세에 열리고(데스크탑 마스터-디테일),
 * 왼쪽 원형 체크를 누르면 완료/해제. 시간 칸(시간대 루틴이 하나라도 있으면 모든 줄에) → 체크 → 제목/주기 → 순서·수정.
 */
@Composable
private fun RoutineRow(
    routine: Routine,
    done: Boolean,
    timeColumn: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    selected: Boolean = false,
    onSelect: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .let { if (onSelect != null) it.clickable(onClick = onSelect) else it }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (timeColumn) {
            Text(
                routine.timeSlot?.substringBefore("-")?.trim().orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(52.dp)
            )
        }
        Box(Modifier.clip(CircleShape).clickable(onClick = onToggle).padding(4.dp)) { CheckCircle(done) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (routine.icon.isNotBlank()) "${routine.icon} ${routine.title}" else routine.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.W600,
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None
                ),
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground
            )
            // 134차: 요일 반복이 아닌 루틴(며칠마다 · 매월 날짜)은 목록에서도 주기를 알 수 있게 함께 보여준다.
            val repeatLabel = if (routine.repeatMode == RoutineRepeat.MODE_WEEKLY) null
            else RoutineRepeat.describe(routine.repeatMode, routine.daysMask, routine.repeatIntervalDays, routine.repeatMonthDaysCsv)
            if (repeatLabel != null) {
                Text(repeatLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onMoveUp != null || onMoveDown != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                com.phonelock.desktop.ui.components.IconChip(Icons.Filled.KeyboardArrowUp, enabled = onMoveUp != null, size = 24.dp, iconSize = 16.dp, onClick = { onMoveUp?.invoke() })
                com.phonelock.desktop.ui.components.IconChip(Icons.Filled.KeyboardArrowDown, enabled = onMoveDown != null, size = 24.dp, iconSize = 16.dp, onClick = { onMoveDown?.invoke() })
            }
        }
        IconButton(onClick = onEdit) {
            androidx.compose.material3.Icon(Icons.Outlined.Edit, contentDescription = "수정", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** 원형 체크(28dp) — 완료되면 강조색으로 차오르고 체크 표시가 살짝 커졌다 자리 잡는다(성능 모드에선 바로 바뀐다). */
@Composable
private fun CheckCircle(done: Boolean) {
    val motion = LocalAppMotion.current
    val fill by animateColorAsState(if (done) MaterialTheme.colorScheme.primary else Color.Transparent, motion.quick(), label = "checkFill")
    val markScale by animateFloatAsState(if (done) 1f else 0f, motion.press(), label = "checkMark")
    Box(
        Modifier.size(28.dp)
            .background(fill, CircleShape)
            .border(2.dp, if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            Icons.Filled.Check,
            contentDescription = if (done) "완료" else "미완료",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp).graphicsLayer { scaleX = markScale; scaleY = markScale }
        )
    }
}
