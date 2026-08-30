package com.phonelock.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonelock.app.service.SocialGroupSyncClient
import com.phonelock.app.ui.theme.Spacing
import kotlin.math.roundToInt

private val DAY_LABELS = listOf("월", "화", "수", "목", "금", "토", "일")

/**
 * 모임 하나의 무전기 수신 설정 — 켜짐/받는 방식/볼륨은 물론, 요일×시간대 일정을 여러 개 두고 그중 하나라도
 * 맞으면 허용되게 할 수 있다(관리앱 그룹의 스케줄 설정과 같은 개념, 다만 여러 개를 둘 수 있다는 점이 다름).
 * 일정을 하나도 안 두면 항상 허용.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupWalkieSettingsDialog(
    initial: SocialGroupSyncClient.GroupWalkieSettings,
    onDismiss: () -> Unit,
    onSave: (SocialGroupSyncClient.GroupWalkieSettings) -> Unit
) {
    var enabled by remember { mutableStateOf(initial.enabled) }
    var mode by remember { mutableStateOf(initial.mode) }
    var volume by remember { mutableStateOf(initial.volume) }
    var schedules by remember { mutableStateOf(initial.schedules) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎙️ 무전기 설정") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("이 모임의 무전기 수신", style = MaterialTheme.typography.bodyLarge)
                    androidx.compose.material3.Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (enabled) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text("받는 방식", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        FilterChip(
                            selected = mode == "FORCED",
                            onClick = { mode = "FORCED" },
                            label = { Text("즉시 재생(무전기)") }
                        )
                        FilterChip(
                            selected = mode == "MESSAGE_ONLY",
                            onClick = { mode = "MESSAGE_ONLY" },
                            label = { Text("메시지로 받기") }
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text("볼륨 ${volume}%", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = volume.toFloat(),
                        onValueChange = { volume = it.roundToInt() },
                        valueRange = 0f..100f
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Divider()
                    Spacer(Modifier.height(Spacing.sm))
                    Text("허용 일정 (없으면 항상 허용)", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(Spacing.xs))
                    schedules.forEachIndexed { index, schedule ->
                        WalkieScheduleRow(
                            schedule = schedule,
                            onChange = { updated -> schedules = schedules.toMutableList().also { it[index] = updated } },
                            onDelete = { schedules = schedules.toMutableList().also { it.removeAt(index) } }
                        )
                        Spacer(Modifier.height(Spacing.sm))
                    }
                    OutlinedButton(onClick = {
                        schedules = schedules + SocialGroupSyncClient.WalkieSchedule()
                    }) { Text("+ 일정 추가") }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(SocialGroupSyncClient.GroupWalkieSettings(enabled, mode, volume, schedules))
            }) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WalkieScheduleRow(
    schedule: SocialGroupSyncClient.WalkieSchedule,
    onChange: (SocialGroupSyncClient.WalkieSchedule) -> Unit,
    onDelete: () -> Unit
) {
    var startText by remember(schedule) { mutableStateOf((schedule.startMinute / 60).toString()) }
    var endText by remember(schedule) { mutableStateOf((schedule.endMinute / 60).toString()) }

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DAY_LABELS.forEachIndexed { index, label ->
                    val checked = (schedule.daysMask shr index) and 1 == 1
                    FilterChip(
                        selected = checked,
                        onClick = {
                            val newMask = if (checked) schedule.daysMask and (1 shl index).inv() else schedule.daysMask or (1 shl index)
                            onChange(schedule.copy(daysMask = newMask))
                        },
                        label = { Text(label) }
                    )
                }
            }
            IconButton(onClick = onDelete) { Text("✕") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(
                value = startText,
                onValueChange = { text ->
                    startText = text
                    text.toIntOrNull()?.let { if (it in 0..23) onChange(schedule.copy(startMinute = it * 60)) }
                },
                label = { Text("시작 시(0~23)") },
                modifier = Modifier.width(120.dp)
            )
            OutlinedTextField(
                value = endText,
                onValueChange = { text ->
                    endText = text
                    text.toIntOrNull()?.let { if (it in 0..23) onChange(schedule.copy(endMinute = it * 60)) }
                },
                label = { Text("종료 시(0~23)") },
                modifier = Modifier.width(120.dp)
            )
        }
    }
}
