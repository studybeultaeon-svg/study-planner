package com.phonelock.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.*
import com.phonelock.app.data.Routine
import com.phonelock.app.routine.RoutineEngine
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.launch
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
 * 루틴앱 v1(47~48차 설계, DECISIONS.md 참고) 메인 화면 — 데스크탑판과 대칭. "오늘"(체크리스트, 시간대
 * 지정 루틴은 시간순 정렬로 일과표 역할까지 겸함)/"통계"(활동 기반 집계) 2개 내부 서브탭. 51차: 스트릭을
 * 루틴별이 아니라 "하루" 단위 전역 스트릭으로 전면 개편(RoutineEngine.kt 참고) — 그날 예정된 루틴을 전부
 * 완료해야 그날이 스트릭에 +1되고, 하나라도 미완료면 그 자리에서 끊긴다(방어권 없음). Repository의 루틴
 * 함수가 전부 suspend라 완료 날짜 집합을 미리 한 번에 불러와 캐싱한다(StudyStatsScreen과 같은 패턴).
 */
@Composable
fun RoutineScreen(repository: PhoneLockRepository) {
    val scope = rememberCoroutineScope()
    var subTab by remember { mutableIntStateOf(0) }
    var routines by remember { mutableStateOf<List<Routine>>(emptyList()) }
    var completedByRoutine by remember { mutableStateOf<Map<Long, Set<String>>>(emptyMap()) }
    var editing by remember { mutableStateOf<Routine?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var weekOffset by remember { mutableStateOf(0) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    val today = remember { LocalDate.now() }
    val dateKey = remember { today.toString() }

    LaunchedEffect(Unit) {
        repository.syncRoutinesFromFirebase()
        refreshTick++
    }

    LaunchedEffect(refreshTick) {
        val list = repository.getRoutines()
        routines = list
        completedByRoutine = list.associate { it.id to repository.getRoutineCompletedDateKeys(it.id) }
    }

    fun refresh() { refreshTick++ }
    fun toggle(routineId: Long, forDateKey: String) {
        scope.launch { repository.toggleRoutineLog(routineId, forDateKey); refresh() }
    }

    Column(Modifier.fillMaxSize().padding(Spacing.md)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("🌱 루틴", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text("반복 할 일 · 통계", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { showAddDialog = true }) { Text("+ 추가") }
        }
        Spacer(Modifier.height(Spacing.sm))

        TabRow(selectedTabIndex = subTab) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("오늘") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("통계") })
        }
        Spacer(Modifier.height(Spacing.sm))

        if (subTab == 0) {
            val currentSunday = today.minusDays(today.dayOfWeek.value.toLong() % 7)
            val sunday = currentSunday.plusWeeks(weekOffset.toLong())
            val weekDates = (0..6).map { sunday.plusDays(it.toLong()) }
            // 가로 스크롤 방식이 금/토(맨 끝 요일)가 화면 밖으로 밀려 안 보이는 문제가 있었다(사용자
            // 실기기 확인) — 스크롤 대신 7칸을 weight(1f)로 균등 배분해서 폭이 얼마든 7일이 전부(잘리지
            // 않고) 한 화면에 들어오게 바꿨다. FilterChip 대신 여백이 작은 커스텀 칩을 써서 좁은 칸에서도
            // 요일+날짜 두 줄이 다 보인다.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { weekOffset-- },
                    modifier = Modifier.width(28.dp).semantics { contentDescription = "이전 주" }
                ) { Text("◀") }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    weekDates.forEachIndexed { i, d ->
                        val selected = d == selectedDate
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    MaterialTheme.shapes.small
                                )
                                .border(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    MaterialTheme.shapes.small
                                )
                                .clickable { selectedDate = d }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(ROUTINE_WEEKDAYS_SUN_FIRST[i], style = MaterialTheme.typography.labelSmall)
                            Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                IconButton(
                    onClick = { weekOffset++ },
                    modifier = Modifier.width(28.dp).semantics { contentDescription = "다음 주" }
                ) { Text("▶") }
            }
            Spacer(Modifier.height(Spacing.sm))
        }

        if (routines.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    "아직 등록된 루틴이 없습니다\n오른쪽 위 \"+ 추가\"로 시작해보세요",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            when (subTab) {
                0 -> RoutineTodayTab(
                    routines, completedByRoutine, today, selectedDate,
                    onToggle = { id, dk -> toggle(id, dk) },
                    onEdit = { editing = it },
                    onSwap = { a, b -> scope.launch { repository.swapRoutineOrder(a, b); refresh() } }
                )
                1 -> RoutineStatsTab(routines, completedByRoutine, today, dateKey)
            }
        }
    }

    if (showAddDialog) {
        RoutineEditDialog(
            routine = null,
            onDismiss = { showAddDialog = false },
            onSave = { r -> scope.launch { repository.addRoutine(r); showAddDialog = false; refresh() } }
        )
    }
    editing?.let { r ->
        RoutineEditDialog(
            routine = r,
            onDismiss = { editing = null },
            onSave = { updated -> scope.launch { repository.updateRoutine(updated); editing = null; refresh() } },
            onDelete = { scope.launch { repository.deleteRoutine(r); editing = null; refresh() } },
            onCopy = { scope.launch { repository.copyRoutine(r); editing = null; refresh() } }
        )
    }
}

@Composable
private fun RoutineTodayTab(
    routines: List<Routine>,
    completedByRoutine: Map<Long, Set<String>>,
    realToday: LocalDate,
    selectedDate: LocalDate,
    onToggle: (Long, String) -> Unit,
    onEdit: (Routine) -> Unit,
    onSwap: (Long, Long) -> Unit
) {
    val isToday = selectedDate == realToday
    val dateKey = remember(selectedDate) { selectedDate.toString() }
    // 시간대 지정 루틴이 먼저 시간순으로, 시간대 없는 루틴은 뒤에 붙는다(일과표 탭 통합, 50차).
    val todays = routines.filter { isScheduledOn(it, selectedDate) }
        .sortedWith(compareBy(nullsLast()) { it.timeSlot })
    // 시간대 없는 루틴만 순서를 사용자가 직접 정할 수 있다(52차) — 시간대 지정 루틴은 항상 시간순이라
    // ▲/▼로 옮겨도 다시 시간순으로 재정렬되며 눈에 보이는 변화가 없다.
    val untimed = todays.filter { it.timeSlot == null }
    val doneCount = todays.count { dateKey in (completedByRoutine[it.id] ?: emptySet()) }
    val currentStreak = RoutineEngine.currentStreak(routines, completedByRoutine, realToday)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일 (${ROUTINE_WEEKDAYS_KO[bitIndexFor(selectedDate)]})" + if (isToday) " · 오늘" else "",
                style = MaterialTheme.typography.titleMedium
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
            val completed = completedByRoutine[routine.id] ?: emptySet()
            val done = dateKey in completed
            val untimedIdx = if (routine.timeSlot == null) untimed.indexOfFirst { it.id == routine.id } else -1
            RoutineRow(
                routine = routine,
                done = done,
                onToggle = { onToggle(routine.id, dateKey) },
                onEdit = { onEdit(routine) },
                onMoveUp = if (untimedIdx > 0) ({ onSwap(routine.id, untimed[untimedIdx - 1].id) }) else null,
                onMoveDown = if (untimedIdx in 0 until untimed.lastIndex) ({ onSwap(routine.id, untimed[untimedIdx + 1].id) }) else null
            )
            Spacer(Modifier.height(Spacing.xs))
        }
    }
}

private data class RoutineDayStat(val date: LocalDate, val scheduled: Int, val done: Int)

/** dateKey(yyyy-MM-dd) 범위 [from, to] 안에서, 예정된 루틴 대비 완료 개수. */
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
 * 루틴 통계(50차 "습관" 탭 대체, 51차 최고 스트릭 중심으로 개편) — 데스크탑판과 대칭. 스트릭은 이제
 * 루틴별이 아니라 하루 단위 전역 값(RoutineEngine 참고) — 최고 스트릭을 가장 크게, 맨 위에 강조한다.
 */
@Composable
private fun RoutineStatsTab(
    routines: List<Routine>,
    completedByRoutine: Map<Long, Set<String>>,
    today: LocalDate,
    dateKey: String
) {
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
        }
        Spacer(Modifier.height(Spacing.sm))
        RoutineStatTile("최고 스트릭", "${bestStreak}일" + if (bestStreak > 0) "🔥" else "", Modifier.fillMaxWidth(), accentColor = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(Spacing.md))

        if (thisTotal > 0 || lastTotal > 0) {
            val thisRate = if (thisTotal > 0) Math.round(thisDone * 100.0 / thisTotal).toInt() else 0
            Text("최근 7일 vs 지난 7일 완료율", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.xs))
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
            Spacer(Modifier.height(Spacing.md))
        }

        // 태블릿(sw600dp 이상)은 폭이 넉넉해 30개 막대를 굳이 스크롤로 몰아넣지 않아도 된다 —
        // 데스크탑과 동일하게 weight(1f) 균등분할로 폭을 꽉 채운다(사용자 요청으로 53차 추가,
        // StudyStatsScreen.kt와 동일 패턴). 폰은 기존 고정폭+가로스크롤 유지.
        val isTablet = LocalConfiguration.current.screenWidthDp >= 600
        Text("최근 30일 완료 추이", style = MaterialTheme.typography.titleMedium)
        Text(
            if (isTablet) "막대 높이 = 예정 개수, 색상 = 완료율" else "막대 높이 = 예정 개수, 색상 = 완료율 · 좌우로 스크롤됩니다",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.sm))
        val barRowModifier = if (isTablet) {
            Modifier.fillMaxWidth().height(90.dp)
        } else {
            // 30개 막대를 폰 폭에 욱여넣으면 짓눌려 보이던 문제(사용자 실기기 확인) — 막대 하나 폭을
            // 고정하고 가로 스크롤로 바꿨다.
            Modifier.fillMaxWidth().height(90.dp).horizontalScroll(rememberScrollState())
        }
        Row(barRowModifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            dayStats.forEach { ds ->
                val pct = if (ds.scheduled > 0) Math.round(ds.done * 100.0 / ds.scheduled).toInt() else 0
                val barColor = when {
                    ds.scheduled == 0 -> MaterialTheme.colorScheme.outlineVariant
                    pct == 100 -> Color(0xFF34D399)
                    pct > 0 -> Color(0xFFFBBF24)
                    else -> Color(0xFFF87171)
                }
                val isToday = ds.date == today
                val columnModifier = if (isTablet) Modifier.weight(1f) else Modifier.width(20.dp)
                Column(columnModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.fillMaxWidth().height(60.dp), verticalArrangement = Arrangement.Bottom) {
                        val heightPct = (ds.scheduled.toFloat() / maxDayCnt).coerceIn(if (ds.scheduled > 0) 0.08f else 0.03f, 1f)
                        Row(Modifier.fillMaxWidth().height((60 * heightPct).dp).background(barColor)) {}
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
                    Text(
                        "▲", fontSize = 10.sp,
                        modifier = Modifier.clickable(enabled = onMoveUp != null) { onMoveUp?.invoke() }
                            .padding(2.dp)
                            .semantics { contentDescription = "위로 이동" }
                    )
                    Text(
                        "▼", fontSize = 10.sp,
                        modifier = Modifier.clickable(enabled = onMoveDown != null) { onMoveDown?.invoke() }
                            .padding(2.dp)
                            .semantics { contentDescription = "아래로 이동" }
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = "수정" }) { Text("✏️") }
        }
    }
}
