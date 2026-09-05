package com.phonelock.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.phonelock.app.data.Routine
import com.phonelock.app.ui.theme.Spacing
import java.time.LocalDate

private val ROUTINE_DAY_LABELS = listOf("월", "화", "수", "목", "금", "토", "일")
private val ROUTINE_ICON_PALETTE = listOf("💪", "🏃", "📚", "💧", "🧘", "☀️", "🌙", "🍎", "✍️", "🎯", "🧹", "💊")

private fun isValidDate(text: String): Boolean = runCatching { LocalDate.parse(text.trim()) }.isSuccess

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineDayMaskRow(mask: Int, onMaskChange: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ROUTINE_DAY_LABELS.forEachIndexed { index, label ->
            val checked = (mask shr index) and 1 == 1
            FilterChip(
                selected = checked,
                onClick = { onMaskChange(if (checked) mask and (1 shl index).inv() else mask or (1 shl index)) },
                label = { Text(label) }
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
 * 루틴 추가/수정 다이얼로그(데스크탑판과 대칭, 47~48차 설계 DECISIONS.md 참고). 그룹 편집처럼 별도
 * 화면이 아니라 다이얼로그 — 필드가 적어 화면 전환이 과함.
 *
 * 태블릿 무대응(의도적 판단, 84차): 데스크탑판도 AlertDialog 안에 세로 Column 하나뿐이고
 * ResponsiveSplit 등 좌우 분할이 없다 — 애초에 필드가 적어 다이얼로그로 처리한다는 설계 자체가
 * 폭에 따라 레이아웃을 바꿀 이유를 없앤다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutineEditDialog(routine: Routine?, onDismiss: () -> Unit, onSave: (Routine) -> Unit, onDelete: (() -> Unit)? = null, onCopy: (() -> Unit)? = null) {
    var title by remember { mutableStateOf(routine?.title ?: "") }
    var icon by remember { mutableStateOf(routine?.icon ?: "") }
    var timeSlotEnabled by remember { mutableStateOf(routine?.timeSlot != null) }
    var timeSlotText by remember { mutableStateOf(routine?.timeSlot ?: "") }
    var daysMask by remember { mutableStateOf(routine?.daysMask ?: 127) }
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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

                Text("적용 요일", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RoutineDayMaskRow(daysMask) { daysMask = it }
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
                    "스트릭은 하루 단위로 자동 집계됩니다 — 오늘 예정된 루틴을 전부 완료해야 그날이 스트릭에 더해지고, 하나라도 놓치면 끊깁니다.",
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
                    val startDate = if (periodEnabled && isValidDate(startDateText)) startDateText.trim() else null
                    val endDate = if (periodEnabled && isValidDate(endDateText)) endDateText.trim() else null
                    onSave(
                        (routine ?: Routine(id = 0)).copy(
                            title = title.trim(),
                            icon = icon.trim(),
                            timeSlot = timeSlot,
                            daysMask = daysMask,
                            notifyEnabled = timeSlot != null && notifyEnabled,
                            startDate = startDate,
                            endDate = endDate
                        )
                    )
                },
                enabled = title.isNotBlank()
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}
