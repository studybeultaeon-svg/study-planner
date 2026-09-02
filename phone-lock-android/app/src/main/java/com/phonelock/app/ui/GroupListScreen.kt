package com.phonelock.app.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phonelock.shared.PERSUASION_MESSAGES
import com.phonelock.shared.randomPersuasionStepDelaysMs
import com.phonelock.app.data.AppGroup
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.service.AccessibilityServiceChecker
import com.phonelock.app.service.LockEvaluator
import com.phonelock.app.ui.components.formatHms
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun GroupListScreen(
    repository: PhoneLockRepository,
    onEditGroup: (Long?) -> Unit
) {
    val context = LocalContext.current
    val groups by repository.observeGroups().collectAsState(initial = emptyList())
    val evaluator = remember { LockEvaluator(repository) }
    val scope = rememberCoroutineScope()

    var accessibilityEnabled by remember { mutableStateOf(AccessibilityServiceChecker.isEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = AccessibilityServiceChecker.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { onEditGroup(null) }) {
                Icon(Icons.Filled.Add, contentDescription = "그룹 추가")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "🗂️ 그룹",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
            )
            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("아직 그룹이 없습니다. + 버튼으로 추가하세요.")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = Spacing.md)) {
                if (!accessibilityEnabled) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Column(Modifier.padding(Spacing.md)) {
                                Text(
                                    "⚠ 접근성 서비스가 꺼져 있습니다 — 지금 어떤 앱/사이트도 차단되지 않습니다",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(Spacing.sm))
                                Button(
                                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("설정에서 켜기")
                                }
                            }
                        }
                    }
                }
                items(groups, key = { it.id }) { group ->
                    GroupRow(
                        group = group,
                        restrictingNow = evaluator.isAnyManagementActiveToday(group),
                        snoozeActive = evaluator.isSnoozeActive(group),
                        snoozeRemainingToday = repository.snoozeRemainingToday(group),
                        onClick = { onEditGroup(group.id) },
                        onSnooze = {
                            scope.launch {
                                if (!repository.snoozeGroup(group.id)) {
                                    Toast.makeText(context, "\"${group.name}\" 그룹은 오늘 스누즈를 이미 다 썼습니다(하루 3회).", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        onGroupToggle = { newValue ->
                            val turningOff = group.groupEnabled && !newValue
                            val currentlyRestricting = evaluator.isAnyManagementActiveToday(group)
                            val exempt = evaluator.isWithinEditExemptionWindow()
                            if (turningOff && currentlyRestricting && !exempt) {
                                // 지금 뭔가(스케줄/일일한도/실행확인) 걸려있는 도중이므로 즉시 끄지 못하게 하고
                                // 회유 멘트를 하나씩 확인해야 진행되는 절차를 건다.
                                repository.updateGroupFireAndForget(
                                    group.copy(groupEnabled = true, groupOffPending = true, groupOffMessageIndex = 0)
                                )
                                Toast.makeText(
                                    context,
                                    "지금 이 그룹이 제한 중이라 바로 끌 수 없습니다. 회유 멘트 20개에 하나씩 \"예\"를 눌러야 꺼집니다.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                repository.updateGroupFireAndForget(
                                    group.copy(groupEnabled = newValue, groupOffPending = false, groupOffMessageIndex = 0)
                                )
                            }
                        },
                        onConfirmMessage = {
                            if (group.groupOffMessageIndex >= PERSUASION_MESSAGES.lastIndex) {
                                repository.updateGroupFireAndForget(
                                    group.copy(groupEnabled = false, groupOffPending = false, groupOffMessageIndex = 0)
                                )
                            } else {
                                repository.updateGroupFireAndForget(
                                    group.copy(groupOffMessageIndex = group.groupOffMessageIndex + 1)
                                )
                            }
                        },
                        onCancelPending = {
                            repository.updateGroupFireAndForget(
                                group.copy(groupEnabled = true, groupOffPending = false, groupOffMessageIndex = 0)
                            )
                        }
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(
    group: AppGroup,
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

    // 화면을 벗어나면(다른 앱으로 전환 등) 진행 중이던 끄기 시도를 취소하고 원래 상태(켜짐)로 되돌린다.
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> isResumed = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(group.id, pending, isResumed) {
        if (pending && !isResumed) {
            onCancelPending()
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
                Switch(checked = group.groupEnabled, onCheckedChange = onGroupToggle)
            }
            if (!pending && group.groupEnabled && (restrictingNow || snoozeActive)) {
                Spacer(Modifier.height(Spacing.xs))
                OutlinedButton(onClick = onSnooze, enabled = !snoozeActive && snoozeRemainingToday > 0) {
                    Text(if (snoozeActive) "😴 스누즈 중" else "😴 스누즈 ${group.snoozeMinutes}분 ($snoozeRemainingToday/3)")
                }
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
                Spacer(Modifier.height(Spacing.xs))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                group.dailyLimitSeconds?.let {
                    Text("일일 한도 ${formatHms(it)}", style = MaterialTheme.typography.bodySmall)
                }
                if (group.scheduleStartMinute != null && group.scheduleEndMinute != null) {
                    val startH = group.scheduleStartMinute / 60
                    val startM = group.scheduleStartMinute % 60
                    val endH = group.scheduleEndMinute / 60
                    val endM = group.scheduleEndMinute % 60
                    Text(
                        "%02d:%02d~%02d:%02d 차단".format(startH, startM, endH, endM),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
