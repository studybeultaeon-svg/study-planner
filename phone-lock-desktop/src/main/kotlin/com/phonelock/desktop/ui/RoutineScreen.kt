package com.phonelock.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
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
import com.phonelock.shared.CharacterGrowth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    var subTab by remember { mutableIntStateOf(0) }
    // 루틴 모드(98차) — 데스크탑은 위젯이 없어 지속 저장 없이 앱 재시작 시 첫 모드로 리셋(subTab과 동일 패턴).
    var modes by remember { mutableStateOf(repository.getRoutineModes().ifEmpty { listOf(RoutineMode(id = repository.ensureDefaultRoutineMode(), name = "기본")) }) }
    var activeModeId by remember { mutableStateOf(modes.first().id) }
    var showAddModeDialog by remember { mutableStateOf(false) }
    var showManageModesDialog by remember { mutableStateOf(false) }
    var routines by remember { mutableStateOf(repository.getRoutines(activeModeId)) }
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
        routines = repository.getRoutines(activeModeId)
        refreshTick++
    }
    fun refreshModes() {
        val loaded = repository.getRoutineModes()
        modes = loaded
        if (loaded.none { it.id == activeModeId }) {
            activeModeId = loaded.firstOrNull()?.id ?: activeModeId
        }
        refresh()
    }
    fun selectMode(id: Long) {
        activeModeId = id
        refresh()
    }

    LaunchedEffect(Unit) {
        // 98차(온라인/오프라인 모드): 오프라인이면 네트워크 타임아웃만 기다리게 되므로 아예 건너뛴다.
        withContext(Dispatchers.IO) {
            if (!repository.isEffectivelyOffline()) {
                repository.syncRoutinesFromFirebase()
                repository.syncPointsFromFirebase()
            }
        }
        refreshModes()
    }

    Column(Modifier.fillMaxSize().padding(Spacing.md)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("🌱 루틴", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text("반복 할 일 · 통계", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                // 98차(사용자 요청, 안드로이드판은 당겨서 새로고침) — 데스크탑은 스와이프 제스처가 없어 버튼으로.
                androidx.compose.material3.IconButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (!repository.isEffectivelyOffline()) {
                                repository.syncRoutinesFromFirebase()
                                repository.syncPointsFromFirebase()
                            }
                        }
                        refreshModes()
                    }
                }) { Text("🔄") }
                OutlinedButton(onClick = { showAddDialog = true }) { Text("+ 루틴 추가") }
            }
        }
        Spacer(Modifier.height(Spacing.sm))

        // 루틴 모드(98차) — 상황별로 별개 루틴 묶음을 전환한다. 다른 모드의 루틴은 숨겨질 뿐 삭제되지 않는다.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            modes.forEach { mode ->
                val selected = mode.id == activeModeId
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { selectMode(mode.id) }
                ) {
                    Text(
                        mode.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                    )
                }
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { showAddModeDialog = true }
            ) {
                Text("+", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs))
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { showManageModesDialog = true }
            ) {
                Text("⚙", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs))
            }
        }
        Spacer(Modifier.height(Spacing.sm))

        TabRow(
            selectedTabIndex = subTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            Tab(selected = subTab == 0, onClick = { subTab = 0 }, text = { Text("오늘") })
            Tab(selected = subTab == 1, onClick = { subTab = 1 }, text = { Text("🔥 연속 기록") })
            Tab(selected = subTab == 2, onClick = { subTab = 2 }, text = { Text("🎁 포인트") })
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
                                // 다른 주로 이동하면 "오늘"이 어디였는지 알 방법이 전혀 없었다 —
                                // 선택 표시와 별개로 오늘 날짜는 항상 굵게+포인트 색으로 구분한다(안드로이드판과 동일).
                                val isRealToday = d == realToday
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(ROUTINE_WEEKDAYS_SUN_FIRST[i], style = MaterialTheme.typography.labelSmall)
                                    Text(
                                        "${d.dayOfMonth}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isRealToday) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isRealToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        )
                    }
                }
                IconButton(onClick = { weekOffset++ }) { androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "다음 주") }
            }
            Spacer(Modifier.height(Spacing.sm))
        }

        if (subTab == 2) {
            Box(Modifier.weight(1f)) { RoutinePointsTab(repository) }
        } else if (routines.isEmpty()) {
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

    if (showAddDialog) {
        RoutineEditDialog(
            routine = null,
            modes = modes,
            initialModeId = activeModeId,
            onDismiss = { showAddDialog = false },
            onSave = { r -> repository.addRoutine(r); showAddDialog = false; refresh() }
        )
    }
    editing?.let { r ->
        RoutineEditDialog(
            routine = r,
            modes = modes,
            initialModeId = r.modeId ?: activeModeId,
            onDismiss = { editing = null },
            onSave = { updated -> repository.updateRoutine(updated); editing = null; refresh() },
            onDelete = { repository.deleteRoutine(r.id); editing = null; refresh() },
            onCopy = { repository.copyRoutine(r); editing = null; refresh() }
        )
    }
    if (showAddModeDialog) {
        RoutineModeAddDialog(
            onDismiss = { showAddModeDialog = false },
            onSave = { name ->
                val newId = repository.addRoutineMode(name)
                showAddModeDialog = false
                modes = repository.getRoutineModes()
                selectMode(newId)
            }
        )
    }
    if (showManageModesDialog) {
        RoutineModeManageDialog(
            modes = modes,
            onDismiss = { showManageModesDialog = false },
            onRename = { id, name -> repository.renameRoutineMode(id, name); refreshModes() },
            onDelete = { id -> repository.deleteRoutineMode(id); refreshModes() },
            onSwap = { a, b -> repository.swapRoutineModeOrder(a, b); refreshModes() }
        )
    }
}

@Composable
private fun RoutineModeAddDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("모드 추가") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("모드 이름") }, modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("추가") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun RoutineModeManageDialog(
    modes: List<RoutineMode>,
    onDismiss: () -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onSwap: (Long, Long) -> Unit
) {
    var renamingId by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    val sorted = modes.sortedBy { it.sortOrder }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("모드 관리") },
        text = {
            Column {
                sorted.forEachIndexed { idx, mode ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(mode.name, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onSwap(mode.id, sorted[idx - 1].id) }, enabled = idx > 0) {
                            androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "위로")
                        }
                        IconButton(onClick = { onSwap(mode.id, sorted[idx + 1].id) }, enabled = idx < sorted.lastIndex) {
                            androidx.compose.material3.Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "아래로")
                        }
                        IconButton(onClick = { renamingId = mode.id; renameText = mode.name }) { Text("✏️") }
                        IconButton(onClick = { onDelete(mode.id) }, enabled = sorted.size > 1) {
                            Text("🗑", color = if (sorted.size > 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                renamingId?.let { id ->
                    Spacer(Modifier.height(Spacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(
                            value = renameText, onValueChange = { renameText = it },
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.material3.TextButton(onClick = {
                            if (renameText.isNotBlank()) onRename(id, renameText.trim())
                            renamingId = null
                        }) { Text("저장") }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("닫기") } }
    )
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
    // 시간대 없는 루틴만 순서를 사용자가 직접 정할 수 있다(52차) — 시간대 지정 루틴은 항상 시간순이라
    // ▲/▼로 옮겨도 다시 시간순으로 재정렬되며 눈에 보이는 변화가 없다.
    val untimed = todays.filter { it.timeSlot == null }

    // 90차(사용자 요청): 넓은 창에서 루틴 목록만 세로로 쌓이던 걸 좌(목록)/우(상세)로 나눴다.
    // 목록이 주인공이라 좌:우 = 2:1, 좁아지면 ResponsiveSplit이 알아서 위아래로 쌓는다.
    com.phonelock.desktop.ui.components.ResponsiveSplit(leftWeight = 2f, rightWeight = 1f, left = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "${selectedDate.monthValue}월 ${selectedDate.dayOfMonth}일 (${ROUTINE_WEEKDAYS_KO[bitIndexFor(selectedDate)]})" + if (isToday) " · 오늘" else "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))

            if (todays.isEmpty()) {
                Text("이 날 예정된 루틴이 없습니다", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                todays.forEach { routine ->
                    val done = repository.isRoutineCompleted(routine.id, dateKey)
                    val untimedIdx = if (routine.timeSlot == null) untimed.indexOfFirst { it.id == routine.id } else -1
                    RoutineRow(
                        routine = routine,
                        done = done,
                        selected = routine.id == selectedRoutineId,
                        onToggle = { repository.toggleRoutineLog(routine.id, dateKey); onChanged() },
                        onEdit = { onEdit(routine) },
                        onSelect = { onSelect(routine.id) },
                        onMoveUp = if (untimedIdx > 0) ({ onSwap(routine.id, untimed[untimedIdx - 1].id) }) else null,
                        onMoveDown = if (untimedIdx in 0 until untimed.lastIndex) ({ onSwap(routine.id, untimed[untimedIdx + 1].id) }) else null
                    )
                    Spacer(Modifier.height(Spacing.xs))
                }
            }
        }
    }, right = {
        // 91차: "그날 요약"(연속 기록/완료율) 대신, 왼쪽에서 고른 루틴의 상세 정보를 보여주는
        // 마스터-디테일 패턴으로 교체(사용자 요청) — 통계는 이미 "🔥 연속 기록" 탭에서 볼 수 있다.
        val selected = routines.find { it.id == selectedRoutineId }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (selected == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "루틴을 선택하면 여기서 상세 정보를 볼 수 있습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                RoutineDetailPanel(repository, selected, onEdit = { onEdit(selected) })
            }
        }
    })
}

/** 91차 신규: 오른쪽 컬럼에서 선택한 루틴 하나의 상세 정보(요일/시간대/기간/연속 기록). */
@Composable
private fun RoutineDetailPanel(repository: Repository, routine: Routine, onEdit: () -> Unit) {
    val today = remember { LocalDate.now() }
    val completedDates = remember(routine.id) { repository.getRoutineCompletedDateKeys(routine.id) }
    val ownStreak = remember(routine.id, completedDates) { routineOwnStreak(routine, completedDates, today) }
    val scheduledDays = (0..6).filter { (routine.daysMask shr it) and 1 == 1 }.joinToString(", ") { ROUTINE_WEEKDAYS_KO[it] }

    Text(
        if (routine.icon.isNotBlank()) "${routine.icon} ${routine.title}" else routine.title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(Spacing.md))
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("현재 연속 기록", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${ownStreak}일" + if (ownStreak > 0) " 🔥" else "",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    Spacer(Modifier.height(Spacing.md))
    RoutineDetailRow("반복 요일", if (scheduledDays.isEmpty()) "없음" else scheduledDays)
    RoutineDetailRow("시간대", routine.timeSlot ?: "지정 안 함")
    if (routine.startDate != null || routine.endDate != null) {
        RoutineDetailRow("기간", "${routine.startDate ?: "제한 없음"} ~ ${routine.endDate ?: "제한 없음"}")
    }
    RoutineDetailRow("알림", if (routine.notifyEnabled) "켜짐" else "꺼짐")
    RoutineDetailRow("최근 30일 완료", "${completedDates.count { it >= today.minusDays(29).toString() }}일")
    Spacer(Modifier.height(Spacing.md))
    OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("✏️ 수정") }
}

@Composable
private fun RoutineDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1.5f))
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

    // 90차: StudyStatsScreen과 같은 좌(요약 지표)/우(그래프) 분할.
    com.phonelock.desktop.ui.components.ResponsiveSplit(leftWeight = 1f, rightWeight = 1.4f, left = {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 현재 스트릭을 가장 위, 가장 크게 — 최고 스트릭은 아래 타일 중 하나로.
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("현재 연속 기록", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            RoutineStatTile("최고 연속 기록", "${bestStreak}일" + if (bestStreak > 0) "🔥" else "", Modifier.weight(1f), accentColor = MaterialTheme.colorScheme.secondary)
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
        }
    }
    }, right = {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
    })
}

/**
 * 포인트/보상(101차+, IDEAS.md "최우선 후보" 1차 구현) — 공부시간·루틴완료·캘린더완료·스트릭 보너스로
 * 적립한 포인트 잔액을 보여주고, 사용자가 직접 등록한 보상 목록을 포인트로 교환(언락)한다.
 * 데스크탑 Repository는 동기 호출이라 RoutineStatsTab처럼 remember/refreshTick으로 재조회한다.
 */
@Composable
private fun RoutinePointsTab(repository: Repository) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val balance = remember(refreshTick) { repository.getPointsBalance() }
    val earnedTotal = remember(refreshTick) { repository.getEarnedPointsTotal() }
    val rewards = remember(refreshTick) { repository.getRewards() }
    var showAddDialog by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    fun refresh() { refreshTick++ }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        CharacterGrowthCard(earnedTotal)
        Spacer(Modifier.height(Spacing.sm))
        Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("보유 포인트", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${balance}P", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "공부 10분당 1P · 루틴 완료 5P · 일정 완료 5P · 오늘 루틴 전부 완료 시 +10P",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.md))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("오늘의 보상", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { showAddDialog = true }) { Text("+ 보상 추가") }
        }
        Spacer(Modifier.height(Spacing.sm))

        toastMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.xs))
        }

        if (rewards.isEmpty()) {
            Text(
                "등록된 보상이 없습니다\n\"+ 보상 추가\"로 원하는 보상을 만들어보세요",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            rewards.forEach { reward ->
                Surface(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(reward.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Text("${reward.cost}P", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            enabled = balance >= reward.cost,
                            onClick = {
                                val ok = repository.redeemReward(reward.id)
                                toastMessage = if (ok) "\"${reward.name}\" 언락했습니다! 🎉" else "포인트가 부족합니다"
                                refresh()
                            }
                        ) { Text("언락") }
                        Spacer(Modifier.width(Spacing.xs))
                        TextButton(onClick = { repository.deleteReward(reward.id); refresh() }) { Text("삭제") }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var costText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("보상 추가") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("보상 이름") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it.filter { c -> c.isDigit() } },
                        label = { Text("필요 포인트") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && (costText.toIntOrNull() ?: 0) > 0,
                    onClick = {
                        val cost = costText.toIntOrNull() ?: 0
                        repository.addReward(name.trim(), cost)
                        showAddDialog = false
                        refresh()
                    }
                ) { Text("추가") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("취소") } }
        )
    }
}

/**
 * 캐릭터/식물 키우기(102차+, IDEAS.md "최우선 후보" 게이미피케이션 2번째 항목) — 누적 획득 포인트(보상
 * 교환으로 줄지 않는 값)에 따라 [CharacterGrowth]의 8단계 식물이 자라는 걸 보여준다. 안드로이드판과 대칭.
 */
@Composable
private fun CharacterGrowthCard(earnedTotal: Int) {
    val stage = CharacterGrowth.stageFor(earnedTotal)
    val progress = CharacterGrowth.progressToNext(earnedTotal)
    val toNext = CharacterGrowth.pointsToNextStage(earnedTotal)
    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stage.emoji, fontSize = 64.sp)
            Spacer(Modifier.height(Spacing.xs))
            Text("${stage.label} (${stage.index + 1}/${CharacterGrowth.STAGES.size}단계)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Spacing.sm))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                if (toNext != null) "다음 단계까지 ${toNext}P 남음" else "최종 단계 도달!",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    selected: Boolean = false,
    onSelect: (() -> Unit)? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    Surface(
        if (onSelect != null) Modifier.fillMaxWidth().clickable(onClick = onSelect) else Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            done -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
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
