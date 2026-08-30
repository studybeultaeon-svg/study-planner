package com.phonelock.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Group
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.monitor.LockEvaluator
import com.phonelock.desktop.ui.components.formatHms
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.delay

@Composable
fun GroupListScreen(
    repository: Repository,
    groups: List<Group>,
    selectedGroupId: Long? = null,
    onAddClick: () -> Unit,
    onEditClick: (Long) -> Unit
) {
    val evaluator = remember { LockEvaluator(repository) }
    var penaltyMessage by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(Spacing.md)) {
        Text("🗂️ 그룹", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.sm))
        Button(onClick = onAddClick, modifier = Modifier.fillMaxWidth()) {
            Text("그룹 추가")
        }
        penaltyMessage?.let { Text(it, modifier = Modifier.padding(top = Spacing.sm)) }
        if (groups.isEmpty()) {
            Text("아직 그룹이 없습니다.", modifier = Modifier.padding(top = Spacing.md))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(top = Spacing.sm)) {
                items(groups, key = { it.id }) { group ->
                    GroupRow(
                        group = group,
                        selected = group.id == selectedGroupId,
                        restrictingNow = evaluator.isAnyManagementActiveToday(group),
                        snoozeActive = evaluator.isSnoozeActive(group),
                        snoozeRemainingToday = repository.snoozeRemainingToday(group),
                        onClick = { onEditClick(group.id) },
                        onSnooze = {
                            if (!repository.snoozeGroup(group.id)) {
                                penaltyMessage = "\"${group.name}\" 그룹은 오늘 스누즈를 이미 다 썼습니다(하루 3회)."
                            }
                        },
                        onGroupToggle = { newValue ->
                            val turningOff = group.groupEnabled && !newValue
                            val currentlyRestricting = evaluator.isAnyManagementActiveToday(group)
                            val exempt = evaluator.isWithinEditExemptionWindow()

                            if (turningOff && currentlyRestricting && !exempt) {
                                // 지금 뭔가(스케줄/일일한도/실행확인) 걸려있는 도중이므로 즉시 끄지 못하게 하고
                                // 회유 멘트를 하나씩 확인해야 진행되는 절차를 건다.
                                repository.updateGroup(
                                    group.copy(groupEnabled = true, groupOffPending = true, groupOffMessageIndex = 0)
                                )
                                penaltyMessage = "\"${group.name}\" 그룹이 지금 제한 중이라 바로 끌 수 없습니다. 회유 멘트 20개에 하나씩 \"예\"를 눌러야 꺼집니다."
                            } else {
                                repository.updateGroup(
                                    group.copy(groupEnabled = newValue, groupOffPending = false, groupOffMessageIndex = 0)
                                )
                                penaltyMessage = null
                            }
                        },
                        onConfirmMessage = {
                            if (group.groupOffMessageIndex >= PERSUASION_MESSAGES.lastIndex) {
                                repository.updateGroup(
                                    group.copy(groupEnabled = false, groupOffPending = false, groupOffMessageIndex = 0)
                                )
                            } else {
                                repository.updateGroup(
                                    group.copy(groupOffMessageIndex = group.groupOffMessageIndex + 1)
                                )
                            }
                        },
                        onCancelPending = {
                            repository.updateGroup(
                                group.copy(groupEnabled = true, groupOffPending = false, groupOffMessageIndex = 0)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: Group,
    selected: Boolean,
    restrictingNow: Boolean,
    snoozeActive: Boolean,
    snoozeRemainingToday: Int,
    onClick: () -> Unit,
    onSnooze: () -> Unit,
    onGroupToggle: (Boolean) -> Unit,
    onConfirmMessage: () -> Unit,
    onCancelPending: () -> Unit
) {
    val pending = group.groupEnabled && group.groupOffPending

    // 창을 벗어나면(다른 창으로 포커스 이동 등) 진행 중이던 끄기 시도를 취소하고 원래 상태(켜짐)로 되돌린다.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(group.id, pending, windowInfo.isWindowFocused) {
        if (pending && !windowInfo.isWindowFocused) {
            onCancelPending()
        }
    }

    // 선택한 그룹은 오른쪽에서 편집 중임을 알 수 있도록 accent 테두리로 강조(마스터-디테일 레이아웃).
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        onClick = onClick
    ) {
        Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                pending -> "(%d/%d) 확인 필요".format(group.groupOffMessageIndex + 1, PERSUASION_MESSAGES.size)
                                snoozeActive -> "😴 스누즈 중"
                                !group.groupEnabled -> "그룹 꺼짐 (아무 제한도 적용 안 됨)"
                                restrictingNow -> "🔒 오늘 제한 중"
                                else -> "오늘은 해당 없음"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        group.dailyLimitSeconds?.let { Text("일일 한도 ${formatHms(it)}", style = MaterialTheme.typography.bodySmall) }
                        if (group.scheduleStartMinute != null && group.scheduleEndMinute != null) {
                            val sh = group.scheduleStartMinute / 60
                            val sm = group.scheduleStartMinute % 60
                            val eh = group.scheduleEndMinute / 60
                            val em = group.scheduleEndMinute % 60
                            Text("%02d:%02d~%02d:%02d 차단".format(sh, sm, eh, em), style = MaterialTheme.typography.bodySmall)
                        }
                        if (group.confirmEnabled) {
                            Text("실행확인", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (!pending && group.groupEnabled && (restrictingNow || snoozeActive)) {
                    OutlinedButton(onClick = onSnooze, enabled = !snoozeActive && snoozeRemainingToday > 0) {
                        Text(if (snoozeActive) "😴 스누즈 중" else "😴 스누즈 ${group.snoozeMinutes}분 ($snoozeRemainingToday/3)")
                    }
                    Spacer(Modifier.width(Spacing.sm))
                }
                Switch(checked = group.groupEnabled, onCheckedChange = onGroupToggle)
            }
            if (pending) {
                val messageIndex = group.groupOffMessageIndex.coerceIn(0, PERSUASION_MESSAGES.lastIndex)
                val isLast = messageIndex == PERSUASION_MESSAGES.lastIndex
                val stepDelaysMs = remember(group.id, group.groupOffPending) { randomPersuasionStepDelaysMs() }
                var stepStarted by remember(group.id, messageIndex) { mutableStateOf(false) }
                var stepRemainingSeconds by remember(group.id, messageIndex) { mutableIntStateOf(0) }
                LaunchedEffect(group.id, messageIndex, stepStarted) {
                    if (!stepStarted) return@LaunchedEffect
                    stepRemainingSeconds = ((stepDelaysMs[messageIndex] + 999) / 1000).toInt()
                    while (stepRemainingSeconds > 0) {
                        delay(1000)
                        stepRemainingSeconds -= 1
                    }
                    onConfirmMessage()
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    PERSUASION_MESSAGES[messageIndex],
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = onCancelPending) { Text("취소") }
                    Button(onClick = { stepStarted = true }, enabled = !stepStarted) {
                        val label = if (isLast) "끄기" else "예"
                        Text(if (stepStarted && stepRemainingSeconds > 0) "$label (${stepRemainingSeconds}초)" else label)
                    }
                }
            }
        }
    }
}
