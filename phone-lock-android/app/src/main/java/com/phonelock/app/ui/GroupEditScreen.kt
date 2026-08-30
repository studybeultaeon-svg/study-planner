package com.phonelock.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phonelock.app.data.AppGroup
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.service.LockEvaluator
import com.phonelock.app.ui.components.DurationFieldsRow
import com.phonelock.app.ui.components.PersuasionStepper
import com.phonelock.app.ui.components.SectionCard
import com.phonelock.app.ui.components.ToggleRow
import com.phonelock.app.ui.components.hmsTextToSeconds
import com.phonelock.app.ui.components.secondsToHmsText
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DAY_LABELS = listOf("월", "화", "수", "목", "금", "토", "일")

private fun minutesToText(minutes: Int?): String =
    minutes?.let { "%02d:%02d".format(it / 60, it % 60) } ?: ""

private fun textToMinutes(text: String): Int? {
    val parts = text.split(":")
    if (parts.size != 2) return null
    val h = parts[0].trim().toIntOrNull() ?: return null
    val m = parts[1].trim().toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayMaskRow(mask: Int, onMaskChange: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DAY_LABELS.forEachIndexed { index, label ->
            val checked = (mask shr index) and 1 == 1
            FilterChip(
                selected = checked,
                onClick = {
                    onMaskChange(if (checked) mask and (1 shl index).inv() else mask or (1 shl index))
                },
                label = { Text(label) }
            )
        }
    }
    Text(
        "체크된 요일에만 적용됩니다.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupEditScreen(
    repository: PhoneLockRepository,
    groupId: Long?,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val evaluator = remember { LockEvaluator(repository) }

    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var scheduleEnabled by remember { mutableStateOf(false) }
    var dailyLimitEnabled by remember { mutableStateOf(false) }
    var dailyLimitHoursText by remember { mutableStateOf("") }
    var dailyLimitMinutesText by remember { mutableStateOf("") }
    var dailyLimitSecondsText by remember { mutableStateOf("") }
    var dailyLimitApplyStartText by remember { mutableStateOf("") }
    var dailyLimitApplyEndText by remember { mutableStateOf("") }
    var scheduleStartText by remember { mutableStateOf("") }
    var scheduleEndText by remember { mutableStateOf("") }
    var daysMask by remember { mutableStateOf(0) }
    var dailyLimitDaysMask by remember { mutableStateOf(127) }
    var confirmEnabled by remember { mutableStateOf(false) }
    var confirmApplyStartText by remember { mutableStateOf("") }
    var confirmApplyEndText by remember { mutableStateOf("") }
    var confirmDaysMask by remember { mutableStateOf(127) }
    var usageOverlayEnabled by remember { mutableStateOf(true) }
    var overlayLevelStepsToMaxText by remember { mutableStateOf("5") }
    var snoozeMinutesText by remember { mutableStateOf("30") }
    var forceEnabledFromText by remember { mutableStateOf("") }
    var forceEnabledUntilText by remember { mutableStateOf("") }
    var pomodoroUnlockEnabled by remember { mutableStateOf(false) }
    var initialWaitHoursText by remember { mutableStateOf("") }
    var initialWaitMinutesText by remember { mutableStateOf("") }
    var initialWaitSecondsText by remember { mutableStateOf("") }
    var waitIncrementHoursText by remember { mutableStateOf("") }
    var waitIncrementMinutesText by remember { mutableStateOf("") }
    var waitIncrementSecondsText by remember { mutableStateOf("") }
    var confirmCooldownHoursText by remember { mutableStateOf("") }
    var confirmCooldownMinutesText by remember { mutableStateOf("") }
    var confirmCooldownSecondsText by remember { mutableStateOf("") }
    var levelDecayEnabled by remember { mutableStateOf(true) }
    var levelDecayHoursText by remember { mutableStateOf("") }
    var levelDecayMinutesText by remember { mutableStateOf("") }
    var levelDecaySecondsText by remember { mutableStateOf("") }
    var selectedPackages by remember { mutableStateOf(setOf<String>()) }
    var selectedSites by remember { mutableStateOf(setOf<String>()) }
    var originalPackages by remember { mutableStateOf(setOf<String>()) }
    var originalSites by remember { mutableStateOf(setOf<String>()) }
    var loaded by remember { mutableStateOf(groupId == null) }
    var searchQuery by remember { mutableStateOf("") }
    var newSiteDomain by remember { mutableStateOf("") }
    var memberTab by remember { mutableIntStateOf(0) }
    var originalGroup by remember { mutableStateOf<AppGroup?>(null) }
    var weakeningInfoExpanded by remember { mutableStateOf(false) }

    // 지금 실제로 제한이 걸려있는 도중에 그 제한을 약화시키는 수정(꼼수)을 감지하면, 회유 멘트를
    // 하나씩 "예"를 눌러 끝까지 확인해야 적용된다.
    var pendingGroup by remember { mutableStateOf<AppGroup?>(null) }
    var pendingPackages by remember { mutableStateOf(setOf<String>()) }
    var pendingSites by remember { mutableStateOf(setOf<String>()) }
    var pendingMessageIndex by remember { mutableIntStateOf(0) }
    var pendingMessage by remember { mutableStateOf<String?>(null) }
    // 그룹 삭제는 그 안의 모든 앱/사이트를 한 번에 무제한으로 풀어주는 가장 강력한 수단이므로,
    // 지금 실제로 제한이 걸려있는 그룹이면 편집과 똑같이 회유 멘트를 다 확인해야 적용된다.
    var pendingDelete by remember { mutableStateOf(false) }
    var pendingDeleteMessageIndex by remember { mutableIntStateOf(0) }

    // 화면을 벗어나면(다른 앱으로 전환, 화면 꺼짐 등) 진행 중이던 회유 멘트 시도를 취소하고 처음부터
    // 다시 하게 한다 — 그냥 두면 백그라운드에서 계속 진행되다가 돌아왔을 때 이미 다 넘어가 있을 수 있다.
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
    LaunchedEffect(isResumed) {
        if (isResumed) return@LaunchedEffect
        if (pendingGroup != null) {
            pendingGroup = null
            pendingMessageIndex = 0
            pendingMessage = "화면을 벗어나서 변경사항이 취소되었습니다. 다시 시도해주세요."
        }
        if (pendingDelete) {
            pendingDelete = false
            pendingDeleteMessageIndex = 0
            pendingMessage = "화면을 벗어나서 삭제가 취소되었습니다. 다시 시도해주세요."
        }
    }

    val installedApps = remember { getLaunchableApps(context) }
    val filteredApps = remember(installedApps, searchQuery, selectedPackages) {
        val base = if (searchQuery.isBlank()) installedApps
        else installedApps.filter { it.label.contains(searchQuery, ignoreCase = true) }
        base.sortedByDescending { selectedPackages.contains(it.packageName) }
    }

    LaunchedEffect(groupId) {
        if (groupId != null) {
            repository.getGroup(groupId)?.let { group ->
                name = group.name
                description = group.description
                scheduleEnabled = group.scheduleEnabled
                dailyLimitEnabled = group.dailyLimitSeconds != null
                val (dh, dm, ds) = secondsToHmsText(group.dailyLimitSeconds ?: 0)
                dailyLimitHoursText = dh
                dailyLimitMinutesText = dm
                dailyLimitSecondsText = ds
                dailyLimitApplyStartText = minutesToText(group.dailyLimitApplyStartMinute)
                dailyLimitApplyEndText = minutesToText(group.dailyLimitApplyEndMinute)
                dailyLimitDaysMask = group.dailyLimitDaysMask
                scheduleStartText = minutesToText(group.scheduleStartMinute)
                scheduleEndText = minutesToText(group.scheduleEndMinute)
                daysMask = group.scheduleDaysMask
                confirmEnabled = group.confirmEnabled
                confirmApplyStartText = minutesToText(group.confirmApplyStartMinute)
                confirmApplyEndText = minutesToText(group.confirmApplyEndMinute)
                confirmDaysMask = group.confirmDaysMask
                usageOverlayEnabled = group.usageOverlayEnabled
                overlayLevelStepsToMaxText = group.overlayLevelStepsToMax.toString()
                snoozeMinutesText = group.snoozeMinutes.toString()
                forceEnabledFromText = group.forceEnabledFrom ?: ""
                forceEnabledUntilText = group.forceEnabledUntil ?: ""
                pomodoroUnlockEnabled = group.pomodoroUnlockEnabled
                val (iwh, iwm, iws) = secondsToHmsText(group.initialWaitSeconds)
                initialWaitHoursText = iwh
                initialWaitMinutesText = iwm
                initialWaitSecondsText = iws
                val (wih, wim, wis) = secondsToHmsText(group.waitIncrementSeconds)
                waitIncrementHoursText = wih
                waitIncrementMinutesText = wim
                waitIncrementSecondsText = wis
                val (cch, ccm, ccs) = secondsToHmsText(group.confirmCooldownSeconds)
                confirmCooldownHoursText = cch
                confirmCooldownMinutesText = ccm
                confirmCooldownSecondsText = ccs
                levelDecayEnabled = group.levelDecayEnabled
                val (ldh, ldm, lds) = secondsToHmsText(group.levelDecayIntervalSeconds)
                levelDecayHoursText = ldh
                levelDecayMinutesText = ldm
                levelDecaySecondsText = lds
                originalGroup = group
            }
            val members = repository.getMembers(groupId).map { it.packageName }.toSet()
            val sites = repository.getGroupSites(groupId).map { it.domain }.toSet()
            selectedPackages = members
            selectedSites = sites
            originalPackages = members
            originalSites = sites
            loaded = true
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (groupId == null) "그룹 추가" else "그룹 편집") }) },
        bottomBar = {
            if (loaded) {
                Column(Modifier.fillMaxWidth().padding(Spacing.md)) {
                    val staged = pendingGroup
                    if (staged != null) {
                        val isLast = pendingMessageIndex == PERSUASION_MESSAGES.lastIndex
                        PersuasionStepper(
                            stepKey = staged,
                            messageIndex = pendingMessageIndex,
                            headerText = "제한을 약화시키는 변경입니다 (%d/%d)".format(pendingMessageIndex + 1, PERSUASION_MESSAGES.size),
                            message = PERSUASION_MESSAGES[pendingMessageIndex],
                            confirmLabel = if (isLast) "적용" else "예",
                            onCancel = {
                                pendingGroup = null
                                pendingMessageIndex = 0
                                pendingMessage = "변경을 취소했습니다."
                            },
                            onConfirmStep = {
                                if (isLast) {
                                    val savedId = if (groupId == null) repository.createGroup(staged) else {
                                        repository.updateGroup(staged)
                                        groupId
                                    }
                                    repository.setMembers(savedId, pendingPackages)
                                    repository.setGroupSites(savedId, pendingSites)
                                    pendingGroup = null
                                    pendingMessageIndex = 0
                                    pendingMessage = null
                                    onDone()
                                } else {
                                    pendingMessageIndex++
                                }
                            }
                        )
                    } else if (pendingDelete) {
                        val isLast = pendingDeleteMessageIndex == PERSUASION_MESSAGES.lastIndex
                        PersuasionStepper(
                            stepKey = pendingDelete,
                            messageIndex = pendingDeleteMessageIndex,
                            headerText = "지금 제한이 걸려있는 그룹의 삭제입니다 (%d/%d)".format(pendingDeleteMessageIndex + 1, PERSUASION_MESSAGES.size),
                            message = PERSUASION_MESSAGES[pendingDeleteMessageIndex],
                            confirmLabel = if (isLast) "삭제" else "예",
                            onCancel = {
                                pendingDelete = false
                                pendingDeleteMessageIndex = 0
                                pendingMessage = "삭제를 취소했습니다."
                            },
                            onConfirmStep = {
                                if (isLast) {
                                    originalGroup?.let { repository.deleteGroup(it) }
                                    pendingDelete = false
                                    pendingDeleteMessageIndex = 0
                                    pendingMessage = null
                                    onDone()
                                } else {
                                    pendingDeleteMessageIndex++
                                }
                            }
                        )
                    } else {
                        pendingMessage?.let {
                            Text(it)
                            Spacer(Modifier.height(Spacing.sm))
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    val group = AppGroup(
                                        id = groupId ?: 0,
                                        name = name.ifBlank { "이름 없는 그룹" },
                                        description = description,
                                        dailyLimitSeconds = if (dailyLimitEnabled) {
                                            hmsTextToSeconds(dailyLimitHoursText, dailyLimitMinutesText, dailyLimitSecondsText)
                                        } else {
                                            null
                                        },
                                        dailyLimitApplyStartMinute = textToMinutes(dailyLimitApplyStartText),
                                        dailyLimitApplyEndMinute = textToMinutes(dailyLimitApplyEndText),
                                        dailyLimitDaysMask = dailyLimitDaysMask,
                                        scheduleStartMinute = textToMinutes(scheduleStartText),
                                        scheduleEndMinute = textToMinutes(scheduleEndText),
                                        scheduleDaysMask = daysMask,
                                        enabled = true,
                                        confirmEnabled = confirmEnabled,
                                        confirmApplyStartMinute = textToMinutes(confirmApplyStartText),
                                        confirmApplyEndMinute = textToMinutes(confirmApplyEndText),
                                        confirmDaysMask = confirmDaysMask,
                                        initialWaitSeconds = hmsTextToSeconds(initialWaitHoursText, initialWaitMinutesText, initialWaitSecondsText),
                                        waitIncrementSeconds = hmsTextToSeconds(waitIncrementHoursText, waitIncrementMinutesText, waitIncrementSecondsText),
                                        confirmCooldownSeconds = hmsTextToSeconds(confirmCooldownHoursText, confirmCooldownMinutesText, confirmCooldownSecondsText),
                                        levelDecayEnabled = levelDecayEnabled,
                                        levelDecayIntervalSeconds = hmsTextToSeconds(levelDecayHoursText, levelDecayMinutesText, levelDecaySecondsText),
                                        usageOverlayEnabled = usageOverlayEnabled,
                                        overlayLevelStepsToMax = overlayLevelStepsToMaxText.trim().toIntOrNull()?.coerceAtLeast(1) ?: 5,
                                        snoozeMinutes = snoozeMinutesText.trim().toIntOrNull()?.coerceAtLeast(1) ?: 30,
                                        snoozedUntilEpochMillis = originalGroup?.snoozedUntilEpochMillis,
                                        snoozeUsedDate = originalGroup?.snoozeUsedDate ?: "",
                                        snoozeUsedCount = originalGroup?.snoozeUsedCount ?: 0,
                                        forceEnabledFrom = forceEnabledFromText.trim().ifBlank { null },
                                        forceEnabledUntil = forceEnabledUntilText.trim().ifBlank { null },
                                        pomodoroUnlockEnabled = pomodoroUnlockEnabled,
                                        scheduleEnabled = scheduleEnabled,
                                        groupEnabled = originalGroup?.groupEnabled ?: true,
                                        groupOffPending = originalGroup?.groupOffPending ?: false,
                                        groupOffMessageIndex = originalGroup?.groupOffMessageIndex ?: 0
                                    )
                                    val original = originalGroup
                                    val weakening = original != null && evaluator.detectWeakeningEdit(
                                        original = original,
                                        updated = group,
                                        originalPackages = originalPackages,
                                        updatedPackages = selectedPackages,
                                        originalSites = originalSites,
                                        updatedSites = selectedSites
                                    )
                                    if (weakening) {
                                        pendingGroup = group
                                        pendingPackages = selectedPackages
                                        pendingSites = selectedSites
                                        pendingMessage = null
                                    } else {
                                        val savedId = if (groupId == null) repository.createGroup(group) else {
                                            repository.updateGroup(group)
                                            groupId
                                        }
                                        repository.setMembers(savedId, selectedPackages)
                                        repository.setGroupSites(savedId, selectedSites)
                                        onDone()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("저장")
                        }

                        if (groupId != null) {
                            Spacer(Modifier.height(Spacing.sm))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        repository.copyGroup(groupId)
                                        onDone()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("복사")
                            }
                            Spacer(Modifier.height(Spacing.sm))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val original = originalGroup
                                        val exempt = evaluator.isWithinEditExemptionWindow()
                                        if (original != null && !exempt && evaluator.isCurrentlyRestricting(original)) {
                                            pendingDelete = true
                                            pendingMessage = null
                                        } else {
                                            repository.getGroup(groupId)?.let { repository.deleteGroup(it) }
                                            onDone()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("삭제")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (!loaded) return@Scaffold

        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
            item {
                SectionCard("기본 정보") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("그룹 이름") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("설명 (선택, \"모임\"에 이 그룹 이름과 함께 표시됩니다)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(Spacing.md))

                SectionCard("관리 종류") {
                    Text(
                        "이 그룹에 적용할 관리 종류를 선택하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    ToggleRow(
                        title = "스케줄",
                        description = "설정한 시간대/요일에 차단합니다.",
                        checked = scheduleEnabled,
                        onCheckedChange = { scheduleEnabled = it }
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    ToggleRow(
                        title = "일일 사용한도 설정",
                        checked = dailyLimitEnabled,
                        onCheckedChange = { dailyLimitEnabled = it }
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    ToggleRow(
                        title = "실행 확인",
                        description = "켜면 실행할 때마다 확인창이 뜨고, 확인할 때마다 대기시간이 늘어납니다.",
                        checked = confirmEnabled,
                        onCheckedChange = { confirmEnabled = it }
                    )
                }
                Spacer(Modifier.height(Spacing.md))

                SectionCard("뽀모도로 연동") {
                    ToggleRow(
                        title = "뽀모도로 휴식 시 자동 해제",
                        description = "공부앱(설정 메뉴에서 Firebase 연동 필요)의 뽀모도로 휴식 시간 동안 이 그룹의 잠금을 임시로 해제합니다. 실행 확인 on/off와 무관하게 작동합니다.",
                        checked = pomodoroUnlockEnabled,
                        onCheckedChange = { pomodoroUnlockEnabled = it }
                    )
                }
                Spacer(Modifier.height(Spacing.md))

                if (scheduleEnabled) {
                    SectionCard("스케줄") {
                        Text("차단 시간대 (비워두면 미적용)", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(Spacing.xs))
                        OutlinedTextField(
                            value = scheduleStartText,
                            onValueChange = { scheduleStartText = it },
                            label = { Text("시작 HH:mm") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        OutlinedTextField(
                            value = scheduleEndText,
                            onValueChange = { scheduleEndText = it },
                            label = { Text("종료 HH:mm") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        DayMaskRow(mask = daysMask, onMaskChange = { daysMask = it })
                    }
                    Spacer(Modifier.height(Spacing.md))
                }

                if (dailyLimitEnabled) {
                    SectionCard("일일 사용한도 설정") {
                        DurationFieldsRow(
                            label = "일일 사용 한도",
                            hoursText = dailyLimitHoursText,
                            onHoursChange = { dailyLimitHoursText = it },
                            minutesText = dailyLimitMinutesText,
                            onMinutesChange = { dailyLimitMinutesText = it },
                            secondsText = dailyLimitSecondsText,
                            onSecondsChange = { dailyLimitSecondsText = it }
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text("적용 시간대 (비워두면 하루 종일 적용)", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(Spacing.xs))
                        OutlinedTextField(
                            value = dailyLimitApplyStartText,
                            onValueChange = { dailyLimitApplyStartText = it },
                            label = { Text("적용 시작 HH:mm") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        OutlinedTextField(
                            value = dailyLimitApplyEndText,
                            onValueChange = { dailyLimitApplyEndText = it },
                            label = { Text("적용 종료 HH:mm") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "이 시간대 안에 있을 때만 한도 초과로 잠깁니다. 사용 시간 누적 자체는 시간대와 무관하게 항상 기록됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        DayMaskRow(mask = dailyLimitDaysMask, onMaskChange = { dailyLimitDaysMask = it })
                    }
                    Spacer(Modifier.height(Spacing.md))
                }

                if (confirmEnabled) {
                    SectionCard("실행 확인") {
                        Text("적용 시간대 (비워두면 하루 종일 적용)", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(Spacing.xs))
                        OutlinedTextField(
                            value = confirmApplyStartText,
                            onValueChange = { confirmApplyStartText = it },
                            label = { Text("적용 시작 HH:mm") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        OutlinedTextField(
                            value = confirmApplyEndText,
                            onValueChange = { confirmApplyEndText = it },
                            label = { Text("적용 종료 HH:mm") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "이 시간대 밖에서는 실행해도 확인창 없이 그냥 허용됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        DayMaskRow(mask = confirmDaysMask, onMaskChange = { confirmDaysMask = it })
                        Spacer(Modifier.height(Spacing.sm))
                        DurationFieldsRow(
                            label = "초기 대기시간",
                            hoursText = initialWaitHoursText,
                            onHoursChange = { initialWaitHoursText = it },
                            minutesText = initialWaitMinutesText,
                            onMinutesChange = { initialWaitMinutesText = it },
                            secondsText = initialWaitSecondsText,
                            onSecondsChange = { initialWaitSecondsText = it }
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        DurationFieldsRow(
                            label = "확인마다 늘어나는 시간",
                            hoursText = waitIncrementHoursText,
                            onHoursChange = { waitIncrementHoursText = it },
                            minutesText = waitIncrementMinutesText,
                            onMinutesChange = { waitIncrementMinutesText = it },
                            secondsText = waitIncrementSecondsText,
                            onSecondsChange = { waitIncrementSecondsText = it }
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        DurationFieldsRow(
                            label = "재확인까지 유예시간",
                            hoursText = confirmCooldownHoursText,
                            onHoursChange = { confirmCooldownHoursText = it },
                            minutesText = confirmCooldownMinutesText,
                            onMinutesChange = { confirmCooldownMinutesText = it },
                            secondsText = confirmCooldownSecondsText,
                            onSecondsChange = { confirmCooldownSecondsText = it }
                        )
                        Text(
                            "유예시간 동안은 같은 그룹의 다른 앱/사이트도 다시 묻지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        ToggleRow(
                            title = "레벨 차감 사용",
                            description = "켜면 마지막 확인 이후 아래 간격이 지날 때마다 대기시간 레벨이 1씩 자연히 줄어듭니다.",
                            checked = levelDecayEnabled,
                            onCheckedChange = { levelDecayEnabled = it }
                        )
                        if (levelDecayEnabled) {
                            Spacer(Modifier.height(Spacing.sm))
                            DurationFieldsRow(
                                label = "레벨 차감 간격",
                                hoursText = levelDecayHoursText,
                                onHoursChange = { levelDecayHoursText = it },
                                minutesText = levelDecayMinutesText,
                                onMinutesChange = { levelDecayMinutesText = it },
                                secondsText = levelDecaySecondsText,
                                onSecondsChange = { levelDecaySecondsText = it }
                            )
                            Text(
                                "정해진 시각이 아니라, 마지막으로 확인한 시점부터 이 간격이 지날 때마다 레벨이 1씩 줄어듭니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        ToggleRow(
                            title = "확인 후 오버레이 표시",
                            description = "유예시간 동안 화면에 남은 시간을 알려주는 오버레이를 이 그룹에서 보여줄지 여부입니다.",
                            checked = usageOverlayEnabled,
                            onCheckedChange = { usageOverlayEnabled = it }
                        )
                        if (usageOverlayEnabled) {
                            Spacer(Modifier.height(Spacing.sm))
                            OutlinedTextField(
                                value = overlayLevelStepsToMaxText,
                                onValueChange = { overlayLevelStepsToMaxText = it },
                                label = { Text("오버레이 최고 밝기까지 재확인 횟수") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "재확인을 이 횟수만큼 반복하면 오버레이가 최고 밝기에 도달합니다. 한 번 재확인할 때마다 오르는 밝기 폭은 이 값에 맞춰 자동으로 계산됩니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                }

                SectionCard("일시정지(스누즈) 시간") {
                    Text(
                        "그룹 목록 화면의 \"😴 스누즈\" 버튼으로 회유 절차 없이 즉시 이 시간만큼 임시 해제할 수 있습니다. " +
                            "남용을 막기 위해 하루 3회까지만 쓸 수 있습니다(자정이 아니라 위 일일 한도 초기화 시각 기준).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = snoozeMinutesText,
                        onValueChange = { snoozeMinutesText = it },
                        label = { Text("스누즈 시간(분)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(Spacing.md))

                SectionCard("기간 지정 자동 강화 (시험기간 등)") {
                    Text(
                        "이 날짜 범위 안에서는 위 \"그룹 전체 사용\" 스위치를 꺼도 실제로는 계속 켜진 것으로 취급됩니다" +
                            "(시간대/한도/실행확인 설정 자체는 그대로 따릅니다). 비워두면 평소처럼 스위치를 그대로 따릅니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = forceEnabledFromText,
                        onValueChange = { forceEnabledFromText = it },
                        label = { Text("시작일 (yyyy-MM-dd)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = forceEnabledUntilText,
                        onValueChange = { forceEnabledUntilText = it },
                        label = { Text("종료일 (yyyy-MM-dd, 포함)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(Spacing.md))

                TextButton(onClick = { weakeningInfoExpanded = !weakeningInfoExpanded }) {
                    Text(if (weakeningInfoExpanded) "꼼수 방지 안내 접기" else "꼼수 방지 안내 자세히 보기")
                }
                AnimatedVisibility(weakeningInfoExpanded) {
                    Text(
                        "지금 제한이 걸려있는 도중에 제한을 약화시키는 수정(한도 늘리기, 시간대 바꾸기, 오늘 요일 빼기, " +
                            "늘어나는 시간 줄이기, 유예시간 늘리기, 레벨 차감을 새로 켜거나 차감 간격 줄이기, 항목 삭제, " +
                            "적용 시간대 좁히기, 스케줄 관리 끄기 등)을 하면 회유 멘트 20개에 하나씩 \"예\"를 눌러야 적용됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(Spacing.md))

                Text("차단 대상", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.sm))
                TabRow(selectedTabIndex = memberTab) {
                    Tab(selected = memberTab == 0, onClick = { memberTab = 0 }, text = { Text("앱") })
                    Tab(selected = memberTab == 1, onClick = { memberTab = 1 }, text = { Text("사이트") })
                }
                Spacer(Modifier.height(Spacing.sm))

                if (memberTab == 0) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("앱 이름 검색") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.sm))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newSiteDomain,
                            onValueChange = { newSiteDomain = it },
                            label = { Text("도메인 (예: youtube.com)") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Button(onClick = {
                            val domain = newSiteDomain.trim().lowercase()
                            if (domain.isNotBlank()) {
                                selectedSites = selectedSites + domain
                                newSiteDomain = ""
                            }
                        }) {
                            Text("추가")
                        }
                    }
                    Text(
                        "Chrome 확장프로그램(별도 설치 필요)이 켜져 있어야 사이트 차단이 동작합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
            }

            if (memberTab == 0) {
                items(filteredApps, key = { it.packageName }) { app ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectedPackages.contains(app.packageName),
                            onCheckedChange = { checked ->
                                selectedPackages = if (checked) selectedPackages + app.packageName
                                else selectedPackages - app.packageName
                            }
                        )
                        AppIcon(app.packageName, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(app.label)
                    }
                }
            } else {
                items(selectedSites.sorted(), key = { it }) { domain ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(domain, modifier = Modifier.weight(1f))
                        IconButton(onClick = { selectedSites = selectedSites - domain }) {
                            Icon(Icons.Filled.Delete, contentDescription = "삭제")
                        }
                    }
                }
            }
        }
    }
}
