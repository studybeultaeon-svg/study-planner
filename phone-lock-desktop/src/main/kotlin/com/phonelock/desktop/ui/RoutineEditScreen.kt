package com.phonelock.desktop.ui

import com.phonelock.desktop.ui.components.LedgerAlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import com.phonelock.shared.routine.RoutineRepeat
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import com.phonelock.desktop.ui.components.CompactDateField
import com.phonelock.desktop.ui.components.CompactField
import com.phonelock.desktop.ui.components.Overline
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.components.SegmentedTabs
import com.phonelock.desktop.ui.components.ToggleRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Routine
import com.phonelock.desktop.ui.theme.Spacing
import java.time.LocalDate

private val ROUTINE_DAY_LABELS = listOf("월", "화", "수", "목", "금", "토", "일")
private val ROUTINE_ICON_PALETTE = listOf("💪", "🏃", "📚", "💧", "🧘", "☀️", "🌙", "🍎", "✍️", "🎯", "🧹", "💊")

private fun isValidDate(text: String): Boolean = runCatching { LocalDate.parse(text.trim()) }.isSuccess

// 146차: 사각 FilterChip → 차단 규칙 편집과 같은 동그란 요일 칸(고른 날 = 강조색 채움, 안드로이드판과 대칭).
@Composable
private fun RoutineDayMaskRow(mask: Int, onMaskChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ROUTINE_DAY_LABELS.forEachIndexed { index, label ->
            val checked = (mask shr index) and 1 == 1
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onMaskChange(if (checked) mask and (1 shl index).inv() else mask or (1 shl index)) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 매월 반복에서 쓰는 1~31일 + "말일" 고르기(안드로이드판과 대칭) — 좁은 다이얼로그라 작은 정사각형 칸으로 그린다. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MonthDayPicker(selected: Set<String>, onToggle: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        (1..31).forEach { day ->
            val token = day.toString()
            val on = token in selected
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onToggle(token) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    token,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val lastOn = RoutineRepeat.LAST_DAY in selected
        Box(
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (lastOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onToggle(RoutineRepeat.LAST_DAY) }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "말일",
                style = MaterialTheme.typography.labelMedium,
                color = if (lastOn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun isValidTimeSlot(text: String): Boolean {
    val parts = text.split(":")
    if (parts.size != 2) return false
    val h = parts[0].trim().toIntOrNull() ?: return false
    val m = parts[1].trim().toIntOrNull() ?: return false
    return h in 0..23 && m in 0..59
}

/**
 * 루틴 추가/수정 다이얼로그(47~48차 설계, DECISIONS.md 참고). 그룹 편집처럼 별도 좌우 분할 화면이
 * 아니라 다이얼로그로 처리 — 루틴은 필드 수가 그룹보다 훨씬 적어 화면 하나를 통째로 쓸 필요가 없다.
 *
 * 146차: 안드로이드판의 상세 페이지와 같은 문법으로 — 작은 경로 라벨(오른쪽에 복사·삭제 아이콘) + 큰 제목, 가는 선 묶음,
 * 스위치 줄, 세그먼트 반복 선택, 동그란 요일 칸, 날짜 칸. 창 안의 대화상자는 그대로 두되 폭을 넓혔다.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RoutineEditDialog(
    routine: Routine?,
    onDismiss: () -> Unit,
    onSave: (Routine) -> Unit,
    onDelete: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null
) {
    var title by remember { mutableStateOf(routine?.title ?: "") }
    var icon by remember { mutableStateOf(routine?.icon ?: "") }
    var timeSlotEnabled by remember { mutableStateOf(routine?.timeSlot != null) }
    var timeSlotText by remember { mutableStateOf(routine?.timeSlot ?: "") }
    var daysMask by remember { mutableStateOf(routine?.daysMask ?: 127) }
    var repeatMode by remember { mutableStateOf(routine?.repeatMode ?: RoutineRepeat.MODE_WEEKLY) }
    var intervalText by remember { mutableStateOf((routine?.repeatIntervalDays ?: 3).toString()) }
    var monthDays by remember { mutableStateOf(RoutineRepeat.parseMonthDays(routine?.repeatMonthDaysCsv ?: "1").toSet()) }
    var notifyEnabled by remember { mutableStateOf(routine?.notifyEnabled ?: false) }
    var periodEnabled by remember { mutableStateOf(routine?.startDate != null || routine?.endDate != null) }
    var startDateText by remember { mutableStateOf(routine?.startDate ?: "") }
    var endDateText by remember { mutableStateOf(routine?.endDate ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    LedgerAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(0.9f),
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Overline(if (routine == null) "루틴 · 새 루틴" else "루틴 · 수정", Modifier.weight(1f))
                    if (onCopy != null) {
                        IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = "복사") }
                    }
                    if (onDelete != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Text(
                    listOf(icon.trim(), title.trim().ifBlank { if (routine == null) "새 루틴" else "루틴" }).filter { it.isNotEmpty() }.joinToString(" "),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                SectionCard("기본 정보") {
                    CompactField(value = title, onValueChange = { title = it }, label = "제목")
                    Spacer(Modifier.height(Spacing.md))
                    Text("아이콘 (선택)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.xs))
                    // 루틴 아이콘은 사용자가 고르는 내용(이모지)이라 그대로 두되, 고른 것은 강조 바탕 원으로 표시한다.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconChoice(selected = icon.isBlank(), onClick = { icon = "" }) {
                            Text("없음", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                        }
                        ROUTINE_ICON_PALETTE.forEach { emoji ->
                            IconChoice(selected = icon == emoji, onClick = { icon = emoji }) { Text(emoji) }
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.md))

                SectionCard("시간") {
                    ToggleRow(
                        title = "시간 정하기",
                        description = "오늘 탭에서 시간순으로 놓입니다.",
                        checked = timeSlotEnabled,
                        onCheckedChange = { timeSlotEnabled = it; if (!it) notifyEnabled = false }
                    )
                    if (timeSlotEnabled) {
                        Spacer(Modifier.height(Spacing.xs))
                        CompactField(
                            value = timeSlotText,
                            onValueChange = { timeSlotText = it },
                            placeholder = "07:30",
                            leadingIcon = Icons.Outlined.Schedule,
                            modifier = Modifier.width(160.dp)
                        )
                        ToggleRow(
                            title = "이 시간에 알림",
                            checked = notifyEnabled,
                            onCheckedChange = { notifyEnabled = it }
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.md))

                SectionCard("반복") {
                    val modes = listOf(RoutineRepeat.MODE_WEEKLY, RoutineRepeat.MODE_INTERVAL, RoutineRepeat.MODE_MONTHLY)
                    SegmentedTabs(
                        labels = listOf("요일마다", "며칠마다", "매월 날짜"),
                        selectedIndex = modes.indexOf(repeatMode).coerceAtLeast(0),
                        onSelect = { repeatMode = modes[it] }
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    when (repeatMode) {
                        RoutineRepeat.MODE_INTERVAL -> {
                            CompactField(
                                value = intervalText,
                                onValueChange = { text -> intervalText = text.filter { it.isDigit() }.take(3) },
                                label = "며칠마다",
                                modifier = Modifier.width(160.dp)
                            )
                            Text(
                                "시작일부터 셉니다(없으면 오늘부터).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RoutineRepeat.MODE_MONTHLY -> {
                            Row {
                                TextButton(onClick = { monthDays = setOf("1") }) { Text("월초", maxLines = 1, softWrap = false) }
                                TextButton(onClick = { monthDays = setOf(RoutineRepeat.LAST_DAY) }) { Text("월말", maxLines = 1, softWrap = false) }
                                TextButton(onClick = { monthDays = setOf("1", RoutineRepeat.LAST_DAY) }) { Text("월초+월말", maxLines = 1, softWrap = false) }
                            }
                            MonthDayPicker(selected = monthDays) { token ->
                                monthDays = if (token in monthDays) monthDays - token else monthDays + token
                            }
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "그 달에 없는 날짜는 마지막 날에 합니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> RoutineDayMaskRow(daysMask) { daysMask = it }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        RoutineRepeat.describe(repeatMode, daysMask, intervalText.toIntOrNull() ?: 3, RoutineRepeat.toMonthDaysCsv(monthDays)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(Spacing.md))

                SectionCard("기간") {
                    ToggleRow(
                        title = "기간 정하기",
                        description = "이 기간에만 합니다. 비운 쪽은 제한 없음.",
                        checked = periodEnabled,
                        onCheckedChange = { periodEnabled = it }
                    )
                    if (periodEnabled) {
                        Spacer(Modifier.height(Spacing.xs))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CompactDateField(
                                value = startDateText,
                                onValueChange = { startDateText = it },
                                placeholder = "시작일",
                                clearable = true,
                                modifier = Modifier.weight(1f)
                            )
                            Text("~", modifier = Modifier.padding(horizontal = Spacing.sm))
                            CompactDateField(
                                value = endDateText,
                                onValueChange = { endDateText = it },
                                placeholder = "종료일",
                                clearable = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val timeSlot = if (timeSlotEnabled && isValidTimeSlot(timeSlotText)) timeSlotText.trim() else null
                    val startDateInput = if (periodEnabled && isValidDate(startDateText)) startDateText.trim() else null
                    // "며칠마다"는 기준일이 없으면 언제 돌아오는지 알 수 없다 — 비어 있으면 오늘부터 센다.
                    val startDate =
                        if (repeatMode == RoutineRepeat.MODE_INTERVAL && startDateInput == null) LocalDate.now().toString()
                        else startDateInput
                    val endDate = if (periodEnabled && isValidDate(endDateText)) endDateText.trim() else null
                    onSave(
                        (routine ?: Routine(id = 0)).copy(
                            title = title.trim(),
                            icon = icon.trim(),
                            timeSlot = timeSlot,
                            daysMask = daysMask,
                            repeatMode = repeatMode,
                            repeatIntervalDays = (intervalText.toIntOrNull() ?: 3)
                                .coerceIn(RoutineRepeat.MIN_INTERVAL_DAYS, RoutineRepeat.MAX_INTERVAL_DAYS),
                            repeatMonthDaysCsv = RoutineRepeat.toMonthDaysCsv(monthDays).ifBlank { "1" },
                            notifyEnabled = timeSlot != null && notifyEnabled,
                            startDate = startDate,
                            endDate = endDate
                        )
                    )
                },
                enabled = title.isNotBlank() && (repeatMode != RoutineRepeat.MODE_MONTHLY || monthDays.isNotEmpty())
            ) { Text("저장", maxLines = 1, softWrap = false) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )

    if (confirmDelete && onDelete != null) {
        LedgerAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("루틴 삭제") },
            text = { Text("\"${title.ifBlank { "이 루틴" }}\"을(를) 삭제할까요? 지난 기록도 함께 지워집니다.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } }
        )
    }
}

/** 아이콘 고르기 한 칸 — 고른 칸만 강조 옅은 바탕 원(146차). */
@Composable
private fun IconChoice(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}
