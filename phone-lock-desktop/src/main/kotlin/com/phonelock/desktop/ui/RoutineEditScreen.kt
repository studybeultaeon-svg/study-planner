package com.phonelock.desktop.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@Composable
private fun RoutineDayMaskRow(mask: Int, onMaskChange: (Int) -> Unit) {
    Row {
        ROUTINE_DAY_LABELS.forEachIndexed { index, label ->
            val checked = (mask shr index) and 1 == 1
            FilterChip(
                selected = checked,
                onClick = { onMaskChange(if (checked) mask and (1 shl index).inv() else mask or (1 shl index)) },
                label = { Text(label) },
                modifier = Modifier.padding(2.dp)
            )
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
 */
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (routine == null) "루틴 추가" else "루틴 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("제목") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))

                Text("아이콘 (선택)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = icon,
                        onValueChange = { icon = it.take(2) },
                        modifier = Modifier.width(72.dp)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Row {
                        ROUTINE_ICON_PALETTE.forEach { emoji ->
                            Text(
                                emoji,
                                modifier = Modifier
                                    .clickable { icon = emoji }
                                    .padding(4.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = timeSlotEnabled, onCheckedChange = { timeSlotEnabled = it; if (!it) notifyEnabled = false })
                    Text("시간대 지정 (오늘 탭에서 시간순 정렬)", style = MaterialTheme.typography.bodyMedium)
                }
                if (timeSlotEnabled) {
                    OutlinedTextField(
                        value = timeSlotText,
                        onValueChange = { timeSlotText = it },
                        label = { Text("HH:mm") },
                        modifier = Modifier.width(140.dp)
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = notifyEnabled, onCheckedChange = { notifyEnabled = it })
                        Text("이 시간에 알림 받기", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(Spacing.sm))

                Text("반복", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    listOf(
                        RoutineRepeat.MODE_WEEKLY to "요일마다",
                        RoutineRepeat.MODE_INTERVAL to "며칠마다",
                        RoutineRepeat.MODE_MONTHLY to "매월 날짜"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = repeatMode == mode,
                            onClick = { repeatMode = mode },
                            label = { Text(label) },
                            modifier = Modifier.padding(2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                when (repeatMode) {
                    RoutineRepeat.MODE_INTERVAL -> {
                        OutlinedTextField(
                            value = intervalText,
                            onValueChange = { text -> intervalText = text.filter { it.isDigit() }.take(3) },
                            label = { Text("며칠마다") },
                            modifier = Modifier.width(140.dp)
                        )
                        Text(
                            "기준일부터 이 간격으로 반복됩니다(기준일 = 아래 기간 설정의 시작일, 비워두면 저장할 때 오늘로 잡힙니다).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    RoutineRepeat.MODE_MONTHLY -> {
                        Row {
                            TextButton(onClick = { monthDays = setOf("1") }) { Text("월초") }
                            TextButton(onClick = { monthDays = setOf(RoutineRepeat.LAST_DAY) }) { Text("월말") }
                            TextButton(onClick = { monthDays = setOf("1", RoutineRepeat.LAST_DAY) }) { Text("월초+월말") }
                        }
                        MonthDayPicker(selected = monthDays) { token ->
                            monthDays = if (token in monthDays) monthDays - token else monthDays + token
                        }
                        Text(
                            "고른 날짜마다 반복됩니다. 31일처럼 그 달에 없는 날짜는 그 달의 마지막 날에 실행됩니다.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> RoutineDayMaskRow(daysMask) { daysMask = it }
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "\u2192 " + RoutineRepeat.describe(repeatMode, daysMask, intervalText.toIntOrNull() ?: 3, RoutineRepeat.toMonthDaysCsv(monthDays)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(Spacing.sm))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = periodEnabled, onCheckedChange = { periodEnabled = it })
                    Text("기간 설정 (시작일~종료일에만 적용)", style = MaterialTheme.typography.bodyMedium)
                }
                if (periodEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = startDateText,
                            onValueChange = { startDateText = it },
                            label = { Text("시작일") },
                            placeholder = { Text("yyyy-MM-dd") },
                            modifier = Modifier.width(160.dp)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        OutlinedTextField(
                            value = endDateText,
                            onValueChange = { endDateText = it },
                            label = { Text("종료일") },
                            placeholder = { Text("yyyy-MM-dd") },
                            modifier = Modifier.width(160.dp)
                        )
                    }
                    Text(
                        "비워두면 그쪽은 제한 없음(시작일만 있으면 그날부터 계속, 종료일만 있으면 그날까지).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "연속 기록은 하루 단위로 자동 집계됩니다 — 오늘 예정된 루틴을 전부 완료해야 그날이 연속 기록에 더해지고, 하나라도 놓치면 끊깁니다.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (onCopy != null || onDelete != null) {
                    Spacer(Modifier.height(Spacing.md))
                    Row {
                        if (onCopy != null) {
                            TextButton(onClick = onCopy) { Text("루틴 복사") }
                        }
                        if (onDelete != null) {
                            TextButton(onClick = onDelete) {
                                Text("루틴 삭제", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
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
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}
