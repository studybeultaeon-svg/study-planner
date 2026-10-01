package com.phonelock.app.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import com.phonelock.shared.routine.RoutineRepeat
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.collectAsState
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
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.*
import com.phonelock.app.data.Routine
import com.phonelock.app.routine.RoutineEngine
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.phonelock.app.ui.components.BigNumber
import com.phonelock.app.ui.components.Hairline
import com.phonelock.app.ui.components.LedgerSection
import com.phonelock.app.ui.components.Overline
import com.phonelock.app.ui.components.PageMasthead
import com.phonelock.app.ui.components.ProgressLine
import com.phonelock.app.ui.components.SectionTabs
import com.phonelock.app.ui.components.StatBlock
import com.phonelock.app.ui.components.StatRow
import com.phonelock.app.ui.theme.LocalAppMotion
import com.phonelock.app.ui.theme.LocalPhoneLockPalette
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.time.LocalDate

private val ROUTINE_WEEKDAYS_KO = arrayOf("월", "화", "수", "목", "금", "토", "일")
private val ROUTINE_WEEKDAYS_SUN_FIRST = arrayOf("일", "월", "화", "수", "목", "금", "토")

private fun bitIndexFor(date: LocalDate): Int = date.dayOfWeek.value - 1

// 134차: 예정일 판정은 RoutineEngine 하나만 쓴다 — 여기에 같은 로직을 복사해 두었더니 반복 방식이
// 늘었을 때(며칠마다·매월 날짜) 이 화면만 옛 규칙으로 목록을 그렸다(에뮬레이터 확인에서 발견).
private fun isScheduledOn(routine: Routine, date: LocalDate): Boolean = RoutineEngine.isScheduledOn(routine, date)

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
    val freezePerWeek = remember { repository.routineStreakFreezePerWeek }

    LaunchedEffect(Unit) {
        // 98차(온라인/오프라인 모드): 오프라인이면 네트워크 타임아웃만 기다리게 되므로 아예 건너뛴다.
        if (!repository.isEffectivelyOffline()) {
            repository.syncRoutinesFromFirebase()
            repository.syncPointsFromFirebase()
        }
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

    // 98차(사용자 요청): 당겨서 새로고침 — 서버 최신 상태를 다시 받아온다.
    com.phonelock.app.ui.components.PullToRefreshBox(onRefresh = {
        if (!repository.isEffectivelyOffline()) repository.syncRoutinesFromFirebase()
        refresh()
    }) {
    Column(Modifier.fillMaxSize()) {
        PageMasthead(
            title = "루틴",
            overline = "${today.monthValue}월 ${today.dayOfMonth}일 ${ROUTINE_WEEKDAYS_KO[bitIndexFor(today)]}요일"
        ) {
            FilledTonalButton(onClick = { showAddDialog = true }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                androidx.compose.material3.Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("추가", maxLines = 1, softWrap = false)
            }
        }
        SectionTabs(listOf("오늘", "연속 기록"), subTab, { subTab = it })

        // 넓은 화면(태블릿)에서 한 줄 목록이 끝없이 늘어나지 않게 읽기 좋은 폭(760dp)까지만 쓴다.
        Column(Modifier.fillMaxSize().widthIn(max = 760.dp).padding(horizontal = Spacing.gutter)) {
            if (subTab == 0) {
                Spacer(Modifier.height(Spacing.sm))
                val currentSunday = today.minusDays(today.dayOfWeek.value.toLong() % 7)
                val sunday = currentSunday.plusWeeks(weekOffset.toLong())
                val weekDates = (0..6).map { sunday.plusDays(it.toLong()) }
                // 7칸을 weight(1f)로 균등 배분해 폭이 얼마든 일주일이 전부 한 화면에 들어온다(스크롤 방식은 금/토가 밀려 안 보였다).
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { weekOffset-- },
                        modifier = Modifier.width(36.dp).semantics { contentDescription = "이전 주" }
                    ) { androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(Modifier.weight(1f)) {
                        weekDates.forEachIndexed { i, d ->
                            WeekDayCell(
                                weekday = ROUTINE_WEEKDAYS_SUN_FIRST[i],
                                day = d.dayOfMonth,
                                selected = d == selectedDate,
                                isToday = d == today,
                                modifier = Modifier.weight(1f)
                            ) { selectedDate = d }
                        }
                    }
                    IconButton(
                        onClick = { weekOffset++ },
                        modifier = Modifier.width(36.dp).semantics { contentDescription = "다음 주" }
                    ) { androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(Spacing.md))
            }

            if (routines.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text("아직 루틴이 없습니다", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "매일 하는 일, 정해진 시간의 일과, 며칠마다 돌아오는 일을 하나로 관리합니다. 오른쪽 위 \"추가\"로 시작해 보세요.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                when (subTab) {
                    0 -> RoutineTodayTab(
                        routines, completedByRoutine, today, selectedDate, freezePerWeek,
                        onToggle = { id, dk -> toggle(id, dk) },
                        onEdit = { editing = it },
                        onSwap = { a, b -> scope.launch { repository.swapRoutineOrder(a, b); refresh() } }
                    )
                    1 -> RoutineStatsTab(routines, completedByRoutine, today, dateKey, freezePerWeek)
                }
            }
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

/** 주간 띠의 하루 — 요일(작게) + 날짜(굵게). 고른 날은 먹색 원 안에, 오늘은 강조색 점으로 표시(테두리 칩 대신). */
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
    routines: List<Routine>,
    completedByRoutine: Map<Long, Set<String>>,
    realToday: LocalDate,
    selectedDate: LocalDate,
    freezePerWeek: Int,
    onToggle: (Long, String) -> Unit,
    onEdit: (Routine) -> Unit,
    onSwap: (Long, Long) -> Unit
) {
    val isToday = selectedDate == realToday
    val dateKey = remember(selectedDate) { selectedDate.toString() }
    // 시간대 지정 루틴이 먼저 시간순으로, 시간대 없는 루틴은 뒤에 붙는다(일과표 탭 통합, 50차).
    val todays = routines.filter { isScheduledOn(it, selectedDate) }
        .sortedWith(compareBy(nullsLast()) { it.timeSlot })
    // 시간대 없는 루틴만 순서를 사용자가 직접 정할 수 있다(52차) — 시간대 지정 루틴은 항상 시간순이다.
    val untimed = todays.filter { it.timeSlot == null }
    val doneCount = todays.count { dateKey in (completedByRoutine[it.id] ?: emptySet()) }
    val currentStreak = RoutineEngine.currentStreak(routines, completedByRoutine, realToday, freezePerWeek)
    // 시간 지정 루틴이 하나라도 있으면 모든 줄에 시간 칸을 둬서 체크 원이 세로로 한 줄에 선다.
    val timeColumn = todays.any { it.timeSlot != null }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 144차 히어로: 고른 날의 완료 수를 크게, 연속 기록은 옆에 강조색으로.
        Overline(
            "${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일 ${ROUTINE_WEEKDAYS_KO[bitIndexFor(selectedDate)]}요일" + if (isToday) " · 오늘" else ""
        )
        Spacer(Modifier.height(4.dp))
        if (todays.isEmpty()) {
            Text("이 날 예정된 루틴이 없습니다", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            BigNumber(
                "$doneCount/${todays.size}",
                unit = "완료",
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.weight(1f)
            )
            Column(horizontalAlignment = Alignment.End) {
                Overline("연속")
                BigNumber("$currentStreak", unit = "일", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        ProgressLine(if (todays.isEmpty()) 0f else doneCount / todays.size.toFloat())
        Spacer(Modifier.height(Spacing.md))

        todays.forEach { routine ->
            val completed = completedByRoutine[routine.id] ?: emptySet()
            val done = dateKey in completed
            val untimedIdx = if (routine.timeSlot == null) untimed.indexOfFirst { it.id == routine.id } else -1
            RoutineRow(
                routine = routine,
                done = done,
                timeColumn = timeColumn,
                onToggle = { onToggle(routine.id, dateKey) },
                onEdit = { onEdit(routine) },
                onMoveUp = if (untimedIdx > 0) ({ onSwap(routine.id, untimed[untimedIdx - 1].id) }) else null,
                onMoveDown = if (untimedIdx in 0 until untimed.lastIndex) ({ onSwap(routine.id, untimed[untimedIdx + 1].id) }) else null
            )
            Hairline()
        }
        Spacer(Modifier.height(Spacing.xl))
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
 * 루틴 통계(51차 최고 스트릭 중심) — 144차: 현재 연속 기록을 화면 맨 위 아주 큰 숫자로, 오늘/최고 기록은 그 아래 세 칸,
 * 주간 비교는 큰 퍼센트 + 증감, 30일 추이는 막대(색은 테마 팔레트의 성공/경고/오류). 데스크탑판과 대칭.
 */
@Composable
private fun RoutineStatsTab(
    routines: List<Routine>,
    completedByRoutine: Map<Long, Set<String>>,
    today: LocalDate,
    dateKey: String,
    freezePerWeek: Int
) {
    val palette = LocalPhoneLockPalette.current
    val scheduledToday = routines.filter { isScheduledOn(it, today) }
    val doneToday = scheduledToday.count { dateKey in (completedByRoutine[it.id] ?: emptySet()) }
    val todayRate = if (scheduledToday.isNotEmpty()) Math.round(doneToday * 100.0 / scheduledToday.size).toInt() else 0

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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(Spacing.md))
        Overline("현재 연속 기록")
        BigNumber(
            "$currentStreak",
            unit = "일",
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp, lineHeight = 90.sp, letterSpacing = (-3).sp),
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
                        Text(
                            "지난주 대비 " + if (diff > 0) "+$diff%p" else "$diff%p",
                            style = MaterialTheme.typography.titleMedium,
                            color = diffColor,
                            modifier = Modifier.alignByBaseline()
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }
        }

        // 태블릿(sw600dp 이상)은 30개 막대를 균등분할로 폭을 꽉 채우고(53차), 폰은 고정폭 + 가로 스크롤.
        val isTablet = LocalConfiguration.current.screenWidthDp >= 600
        LedgerSection("최근 30일") {
            Text(
                if (isTablet) "막대 높이는 예정 개수, 색은 완료율" else "막대 높이는 예정 개수, 색은 완료율 · 좌우로 넘겨 보세요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            val barRowModifier = if (isTablet) {
                Modifier.fillMaxWidth().height(96.dp)
            } else {
                Modifier.fillMaxWidth().height(96.dp).horizontalScroll(rememberScrollState(Int.MAX_VALUE))
            }
            Row(barRowModifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                dayStats.forEach { ds ->
                    val pct = if (ds.scheduled > 0) Math.round(ds.done * 100.0 / ds.scheduled).toInt() else 0
                    val barColor = when {
                        ds.scheduled == 0 -> MaterialTheme.colorScheme.outlineVariant
                        pct == 100 -> palette.success
                        pct > 0 -> palette.warning
                        else -> palette.error
                    }
                    val isToday = ds.date == today
                    val columnModifier = if (isTablet) Modifier.weight(1f) else Modifier.width(18.dp)
                    Column(columnModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.fillMaxWidth().height(66.dp), verticalArrangement = Arrangement.Bottom) {
                            val heightPct = (ds.scheduled.toFloat() / maxDayCnt).coerceIn(if (ds.scheduled > 0) 0.08f else 0.03f, 1f)
                            Box(Modifier.fillMaxWidth().height((66 * heightPct).dp).background(barColor, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
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
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/**
 * 루틴 한 줄 — 144차: 카드 대신 줄(아래 가는 선은 호출 쪽). 왼쪽에 시간(시간대 지정 루틴만, 일과표처럼), 큰 원형 체크,
 * 제목/주기, 오른쪽에 순서·수정. 완료하면 체크가 강조색으로 차오르고 체크 표시가 튀어나오며 제목은 흐려지고 줄이 그어진다.
 */
@Composable
private fun RoutineRow(
    routine: Routine,
    done: Boolean,
    timeColumn: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onToggle()
        }.padding(vertical = 10.dp),
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
        CheckCircle(done)
        Spacer(Modifier.width(14.dp))
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
            // 시간은 왼쪽 시간 칸이 이미 보여주므로 부제에는 주기만 남긴다.
            val subtitle = repeatLabel.orEmpty()
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onMoveUp != null || onMoveDown != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                com.phonelock.app.ui.components.IconChip(
                    Icons.Filled.KeyboardArrowUp,
                    enabled = onMoveUp != null,
                    contentDescription = "위로 이동",
                    size = 24.dp,
                    iconSize = 16.dp,
                    onClick = { onMoveUp?.invoke() }
                )
                com.phonelock.app.ui.components.IconChip(
                    Icons.Filled.KeyboardArrowDown,
                    enabled = onMoveDown != null,
                    contentDescription = "아래로 이동",
                    size = 24.dp,
                    iconSize = 16.dp,
                    onClick = { onMoveDown?.invoke() }
                )
            }
        }
        IconButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = "수정" }) {
            androidx.compose.material3.Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
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
