package com.phonelock.desktop.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.*
import com.phonelock.desktop.data.Routine
import com.phonelock.desktop.routine.RoutineEngine
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val ROUTINE_WEEKDAYS_KO = arrayOf("월", "화", "수", "목", "금", "토", "일")
private val ROUTINE_WEEKDAYS_SUN_FIRST = arrayOf("일", "월", "화", "수", "목", "금", "토")

private fun bitIndexFor(date: LocalDate): Int = date.dayOfWeek.value - 1
private fun isScheduledOn(routine: Routine, date: LocalDate): Boolean {
    routine.startDate?.let { if (date.isBefore(LocalDate.parse(it))) return false }
    routine.endDate?.let { if (date.isAfter(LocalDate.parse(it))) return false }
    return (routine.daysMask shr bitIndexFor(date)) and 1 == 1
}

/**
 * 루틴앱 v1(47~48차 설계, DECISIONS.md 참고) 메인 화면 — "오늘"(체크리스트, 시간대 지정 루틴은 시간순으로
 * 정렬해 일과표 역할까지 겸함)/"통계"(활동 기반 집계) 2개 내부 서브탭. 51차: 스트릭을 루틴별이 아니라
 * "하루" 단위 전역 스트릭으로 전면 개편(RoutineEngine.kt 참고) — 그날 예정된 루틴을 전부 완료해야 그날이
 * 스트릭에 +1되고, 하나라도 미완료면 그 자리에서 끊긴다(방어권 없음). 편집은 RoutineEditDialog(별도
 * 화면 대신 다이얼로그)로 처리.
 */
@Composable
fun RoutineScreen(repository: Repository) {
    var subTab by remember { mutableIntStateOf(0) }
    var routines by remember { mutableStateOf(repository.getRoutines()) }
    var editing by remember { mutableStateOf<Routine?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var weekOffset by remember { mutableStateOf(0) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    // 체크박스를 눌러도 Routine 목록 자체(제목/요일 등)는 안 바뀌어서 routines를 재할당해도 값이 구조적으로
    // 동일하면 Compose가 변경으로 인식하지 못해 화면이 갱신 안 되는 버그가 있었다(그룹 탭에서도 같은 패턴이
    // 있었음, MainScreen.kt 참고). refreshTick은 매번 다른 값이 되므로 key()로 감싸 확실히 재구성시킨다.
    var refreshTick by remember { mutableIntStateOf(0) }

    fun refresh() {
        routines = repository.getRoutines()
        refreshTick++
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { repository.syncRoutinesFromFirebase() }
        refresh()
    }

    Column(Modifier.fillMaxSize().padding(Spacing.md)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("🌱 루틴", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text("반복 할 일 · 통계", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { showAddDialog = true }) { Text("+ 루틴 추가") }
        }
        Spacer(Modifier.height(Spacing.sm))

        TabRow(
            selectedTabIndex = subTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("오늘") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("통계") })
        }
        Spacer(Modifier.height(Spacing.sm))

        if (subTab == 0) {
            val realToday = LocalDate.now()
            val currentSunday = realToday.minusDays(realToday.dayOfWeek.value.toLong() % 7)
            val sunday = currentSunday.plusWeeks(weekOffset.toLong())
            val weekDates = (0..6).map { sunday.plusDays(it.toLong()) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { weekOffset-- }) { androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "이전 주") }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    weekDates.forEachIndexed { i, d ->
                        FilterChip(
                            selected = d == selectedDate,
                            onClick = { selectedDate = d },
                            label = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(ROUTINE_WEEKDAYS_SUN_FIRST[i], style = MaterialTheme.typography.labelSmall)
                                    Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        )
                    }
                }
                IconButton(onClick = { weekOffset++ }) { androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "다음 주") }
            }
            Spacer(Modifier.height(Spacing.sm))
        }

        if (routines.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    "아직 등록된 루틴이 없습니다\n오른쪽 위 \"루틴 추가\"로 시작해보세요",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
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
                            onSwap = { a, b -> repository.swapRoutineOrder(a, b); refresh() }
                        )
                        1 -> RoutineStatsTab(repository, routines)
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

@Composable
private fun RoutineTodayTab(
    repository: Repository,
    routines: List<Routine>,
    selectedDate: LocalDate,
    onEdit: (Routine) -> Unit,
    onChanged: () -> Unit,
    onSwap: (Long, Long) -> Unit
) {
    val realToday = remember { LocalDate.now() }
    val isToday = selectedDate == realToday
    val dateKey = remember(selectedDate) { selectedDate.toString() }
    // 시간대 지정 루틴이 먼저 시간순으로, 시간대 없는 루틴은 뒤에 붙는다(일과표 탭 통합, 50차).
    val todays = routines.filter { isScheduledOn(it, selectedDate) }
        .sortedWith(compareBy(nullsLast()) { it.timeSlot })
    // 시간대 없는 루틴만 순서를 사용자가 직접 정할 수 있다(52차) — 시간대 지정 루틴은 항상 시간순이라
    // ▲/▼로 옮겨도 다시 시간순으로 재정렬되며 눈에 보이는 변화가 없다.
    val untimed = todays.filter { it.timeSlot == null }
    val doneCount = todays.count { repository.isRoutineCompleted(it.id, dateKey) }
    val completedByRoutine = remember(routines) {
        routines.associate { it.id to repository.getRoutineCompletedDateKeys(it.id) }
    }
    val currentStreak = RoutineEngine.currentStreak(routines, completedByRoutine, realToday)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일 (${ROUTINE_WEEKDAYS_KO[bitIndexFor(selectedDate)]})" + if (isToday) " · 오늘" else "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (todays.isNotEmpty()) {
                Text(
                    "🔥 ${currentStreak}일 연속" + if (doneCount == todays.size) "" else " · $doneCount/${todays.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))

        if (todays.isEmpty()) {
            Text("이 날 예정된 루틴이 없습니다", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }

        todays.forEach { routine ->
            val done = repository.isRoutineCompleted(routine.id, dateKey)
            val untimedIdx = if (routine.timeSlot == null) untimed.indexOfFirst { it.id == routine.id } else -1
            RoutineRow(
                routine = routine,
                done = done,
                onToggle = { repository.toggleRoutineLog(routine.id, dateKey); onChanged() },
                onEdit = { onEdit(routine) },
                onMoveUp = if (untimedIdx > 0) ({ onSwap(routine.id, untimed[untimedIdx - 1].id) }) else null,
                onMoveDown = if (untimedIdx in 0 until untimed.lastIndex) ({ onSwap(routine.id, untimed[untimedIdx + 1].id) }) else null
            )
            Spacer(Modifier.height(Spacing.xs))
        }
    }
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
 * 루틴 통계(50차 "습관" 탭 대체, 51차 최고 스트릭 중심으로 개편) — 캘린더 쪽 StudyStatsScreen과 같은
 * 톤(StatTile+SectionCard+30일 막대그래프)으로, 루틴 로그 전체를 집계하는 읽기 전용 파생 뷰. 별도 저장
 * 없이 매번 다시 계산한다. 스트릭은 이제 루틴별이 아니라 하루 단위 전역 값(RoutineEngine 참고) — 최고
 * 스트릭을 가장 크게, 맨 위에 강조한다.
 */
@Composable
private fun RoutineStatsTab(repository: Repository, routines: List<Routine>) {
    val today = remember { LocalDate.now() }
    val dateKey = remember { today.toString() }
    val completedByRoutine = remember(routines) {
        routines.associate { it.id to repository.getRoutineCompletedDateKeys(it.id) }
    }

    val scheduledToday = routines.filter { isScheduledOn(it, today) }
    val doneToday = scheduledToday.count { dateKey in (completedByRoutine[it.id] ?: emptySet()) }
    val todayRate = if (scheduledToday.isNotEmpty()) Math.round(doneToday * 100.0 / scheduledToday.size).toInt() else 0

    val currentStreak = RoutineEngine.currentStreak(routines, completedByRoutine, today)
    val bestStreak = RoutineEngine.bestStreak(routines, completedByRoutine, today)

    val (thisDone, thisTotal) = routineRateInRange(routines, completedByRoutine, today.minusDays(6), today)
    val (lastDone, lastTotal) = routineRateInRange(routines, completedByRoutine, today.minusDays(13), today.minusDays(7))

    val dayStats = (0 until 30).map { i ->
        val d = today.minusDays((29 - i).toLong())
        val (done, total) = routineRateInRange(routines, completedByRoutine, d, d)
        RoutineDayStat(d, total, done)
    }
    val maxDayCnt = maxOf(1, dayStats.maxOf { it.scheduled })

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 현재 스트릭을 가장 위, 가장 크게 — 최고 스트릭은 아래 타일 중 하나로.
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("현재 스트릭", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${currentStreak}일" + if (currentStreak > 0) " 🔥" else "",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RoutineStatTile("오늘 완료", "$doneToday / ${scheduledToday.size}", Modifier.weight(1f), accentColor = Color(0xFF34D399))
            RoutineStatTile("오늘 완료율", "$todayRate%", Modifier.weight(1f), accentColor = Color(0xFFFBBF24))
            RoutineStatTile("최고 스트릭", "${bestStreak}일" + if (bestStreak > 0) "🔥" else "", Modifier.weight(1f), accentColor = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(Spacing.md))

        if (thisTotal > 0 || lastTotal > 0) {
            val thisRate = if (thisTotal > 0) Math.round(thisDone * 100.0 / thisTotal).toInt() else 0
            SectionCard("최근 7일 vs 지난 7일 완료율") {
                if (lastTotal == 0) {
                    Text(
                        "이번 주 완료율 $thisRate% (지난주 예정 루틴 없음, 비교 불가)",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    val lastRate = Math.round(lastDone * 100.0 / lastTotal).toInt()
                    val diff = thisRate - lastRate
                    val diffColor = when {
                        diff > 0 -> Color(0xFF34D399)
                        diff < 0 -> Color(0xFFF87171)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val diffLabel = if (diff > 0) "+$diff%p" else "$diff%p"
                    Text(
                        "이번 주 완료율 $thisRate% (지난주 대비 $diffLabel)",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                        color = diffColor
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }

        SectionCard("최근 30일 완료 추이 (막대 높이 = 예정 개수, 색상 = 완료율)") {
            Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                dayStats.forEach { ds ->
                    val pct = if (ds.scheduled > 0) Math.round(ds.done * 100.0 / ds.scheduled).toInt() else 0
                    val barColor = when {
                        ds.scheduled == 0 -> MaterialTheme.colorScheme.outlineVariant
                        pct == 100 -> Color(0xFF34D399)
                        pct > 0 -> Color(0xFFFBBF24)
                        else -> Color(0xFFF87171)
                    }
                    val isToday = ds.date == today
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(
                            Modifier.fillMaxWidth().height(60.dp),
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            val heightPct = (ds.scheduled.toFloat() / maxDayCnt).coerceIn(if (ds.scheduled > 0) 0.08f else 0.03f, 1f)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height((60 * heightPct).dp)
                                    .background(barColor)
                            ) {}
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${ds.date.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineStatTile(label: String, value: String, modifier: Modifier = Modifier, accentColor: Color = Color.Unspecified) {
    val hasAccent = accentColor != Color.Unspecified
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (hasAccent) accentColor.copy(alpha = 0.07f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (hasAccent) accentColor.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(Spacing.sm), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(
                value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = if (hasAccent) accentColor else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun RoutineRow(
    routine: Routine,
    done: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (done) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = done, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(start = Spacing.xs)) {
                Text(
                    if (routine.icon.isNotBlank()) "${routine.icon} ${routine.title}" else routine.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                routine.timeSlot?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onMoveUp != null || onMoveDown != null) {
                Column {
                    com.phonelock.desktop.ui.components.IconChip(
                        Icons.Filled.KeyboardArrowUp,
                        enabled = onMoveUp != null,
                        onClick = { onMoveUp?.invoke() }
                    )
                    com.phonelock.desktop.ui.components.IconChip(
                        Icons.Filled.KeyboardArrowDown,
                        enabled = onMoveDown != null,
                        onClick = { onMoveDown?.invoke() }
                    )
                }
            }
            IconButton(onClick = onEdit) { Text("✏️") }
        }
    }
}
