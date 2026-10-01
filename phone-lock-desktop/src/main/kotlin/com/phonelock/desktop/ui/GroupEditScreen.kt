package com.phonelock.desktop.ui

import com.phonelock.desktop.ui.components.LedgerAlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.phonelock.shared.PERSUASION_MESSAGES
import com.phonelock.shared.randomPersuasionStepDelaysMs
import com.phonelock.desktop.data.Group
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.applyGroupSettingsJson
import com.phonelock.desktop.data.findRemoteGroupSettingByName
import com.phonelock.desktop.monitor.LockEvaluator
import com.phonelock.desktop.ui.components.CompactDateField
import com.phonelock.desktop.ui.components.CompactField
import com.phonelock.desktop.ui.components.CompactNumberField
import com.phonelock.desktop.ui.components.DurationFieldsRow
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.components.SegmentedTabs
import com.phonelock.desktop.ui.components.Hairline
import com.phonelock.desktop.ui.components.Overline
import com.phonelock.desktop.ui.components.ToggleRow
import com.phonelock.desktop.ui.components.hmsTextToSeconds
import com.phonelock.desktop.ui.components.secondsToHmsText
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

// 96차(안드로이드판과 대칭): 사용자가 그려준 시안대로 사각 FilterChip 대신 동그란 요일 칩으로 교체.
@Composable
private fun DayMaskRow(mask: Int, onMaskChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DAY_LABELS.forEachIndexed { index, label ->
            val checked = (mask shr index) and 1 == 1
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        onMaskChange(if (checked) mask and (1 shl index).inv() else mask or (1 shl index))
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** "09:00 ~ 18:00" 시간대 입력 두 칸(146차, 스케줄·한도·실행 전 대기 공용 — 안드로이드판과 대칭). 비워 두면 그 시간대를 쓰지 않는다. */
@Composable
private fun TimeRangeFields(start: String, onStartChange: (String) -> Unit, end: String, onEndChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompactField(
            value = start,
            onValueChange = onStartChange,
            placeholder = "시작",
            leadingIcon = Icons.Outlined.Schedule,
            centerValue = true,
            modifier = Modifier.weight(1f)
        )
        Text("~", modifier = Modifier.padding(horizontal = Spacing.sm))
        CompactField(
            value = end,
            onValueChange = onEndChange,
            placeholder = "끝",
            leadingIcon = Icons.Outlined.Schedule,
            centerValue = true,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun GroupEditScreen(repository: Repository, groupId: Long?, onDone: () -> Unit) {
    val evaluator = remember { LockEvaluator(repository) }
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selfMessageText by remember { mutableStateOf("") }
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
    var snoozeEnabled by remember { mutableStateOf(true) }
    var snoozeMinutesText by remember { mutableStateOf("30") }
    var snoozeDailyLimitText by remember { mutableStateOf("3") }
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
    var processNames by remember { mutableStateOf(setOf<String>()) }
    var newProcessName by remember { mutableStateOf("") }
    var domains by remember { mutableStateOf(setOf<String>()) }
    var newDomain by remember { mutableStateOf("") }
    var originalProcessNames by remember { mutableStateOf(setOf<String>()) }
    var originalDomains by remember { mutableStateOf(setOf<String>()) }
    var originalGroup by remember { mutableStateOf<Group?>(null) }
    var weakeningInfoExpanded by remember { mutableStateOf(false) }

    var pendingGroup by remember { mutableStateOf<Group?>(null) }
    var pendingMessageIndex by remember { mutableIntStateOf(0) }
    var pendingMessage by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf(false) }
    var confirmPlainDelete by remember { mutableStateOf(false) }
    var pendingDeleteMessageIndex by remember { mutableIntStateOf(0) }
    // 동기화(94차 신규, 안드로이드판과 대칭) — on/off 스위치 자체는 95차부터 이 편집 화면이 아니라
    // GroupListScreen(잠깐 풀기 버튼 옆)에 있다 — 여기서는 저장 시 기존 값을 그대로 유지해 전달하는
    // 용도로만 상태를 들고 있는다. 새 규칙 생성 시 이름 충돌 확인은 pendingCreateCollisionEntry가 담당.
    var syncEnabled by remember { mutableStateOf(false) }
    // 전체 잠금 방식(142차) — 켜면 아래 프로그램/사이트 목록이 "막을 대상"이 아니라 "허용할 대상"이 된다. 목록처럼
    // 기기마다 따로 두는 값이라 동기화로 받아오는 설정(applyGroupToForm)에는 들어 있지 않다.
    var allowlistMode by remember { mutableStateOf(false) }
    var pendingCreateCollisionEntry by remember { mutableStateOf<JSONObject?>(null) }

    fun applyGroupToForm(group: Group) {
        description = group.description
        scheduleEnabled = group.scheduleEnabled
        dailyLimitEnabled = group.dailyLimitSeconds != null
        val (dlh, dlm, dls) = secondsToHmsText(group.dailyLimitSeconds ?: 0)
        dailyLimitHoursText = dlh
        dailyLimitMinutesText = dlm
        dailyLimitSecondsText = dls
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
        snoozeEnabled = group.snoozeEnabled
        snoozeMinutesText = group.snoozeMinutes.toString()
        snoozeDailyLimitText = group.snoozeDailyLimit.toString()
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
    }

    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (windowInfo.isWindowFocused) return@LaunchedEffect
        if (pendingGroup != null) {
            pendingGroup = null
            pendingMessageIndex = 0
            pendingMessage = "창을 벗어나서 변경사항이 취소되었습니다. 다시 시도해주세요."
        }
        if (pendingDelete) {
            pendingDelete = false
            pendingDeleteMessageIndex = 0
            pendingMessage = "창을 벗어나서 삭제가 취소되었습니다. 다시 시도해주세요."
        }
    }

    LaunchedEffect(groupId) {
        if (groupId != null) {
            repository.getGroup(groupId)?.let { group ->
                name = group.name
                selfMessageText = group.selfMessageText
                syncEnabled = group.syncEnabled
                allowlistMode = group.allowlistMode
                applyGroupToForm(group)
                processNames = group.processNames.toSet()
                domains = group.domains.toSet()
                originalProcessNames = group.processNames.toSet()
                originalDomains = group.domains.toSet()
                originalGroup = group
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.md)
    ) {
        // 146차: 편집형 머리 — 작은 경로 라벨(오른쪽에 복사·삭제 아이콘) + 큰 제목(규칙 이름) + 가는 선. 아래엔 "저장" 하나만.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Overline(if (groupId == null) "관리 · 새 차단 규칙" else "관리 · 차단 규칙 편집", Modifier.weight(1f))
            if (groupId != null && pendingGroup == null && !pendingDelete) {
                IconButton(onClick = { repository.copyGroup(groupId); onDone() }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "복사")
                }
                IconButton(onClick = {
                    val original = originalGroup
                    if (original != null && evaluator.requiresDeleteGate(original)) {
                        pendingDelete = true
                        pendingMessage = null
                    } else {
                        // 작은 아이콘이 된 만큼 잘못 눌러 바로 지워지지 않게 한 번 묻는다(146차).
                        confirmPlainDelete = true
                    }
                }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        Text(
            name.ifBlank { if (groupId == null) "새 차단 규칙" else "차단 규칙" },
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Spacing.sm))
        Hairline()

        SectionCard("기본 정보", divider = false) {
            CompactField(
                value = name,
                onValueChange = { name = it },
                label = "이름"
            )
            Spacer(Modifier.height(Spacing.sm))
            CompactField(
                value = description,
                onValueChange = { description = it },
                label = "설명 (선택 · 모임에 함께 표시)",
                placeholder = "선택 입력"
            )
            Spacer(Modifier.height(Spacing.sm))
            CompactField(
                value = selfMessageText,
                onValueChange = { selfMessageText = it },
                label = "미래의 나에게 (선택 · 잠길 때 표시)",
                placeholder = "예: 오늘 밤 11시 이후엔 진짜 그만 봐."
            )
        }
        Spacer(Modifier.height(Spacing.md))

        // 142차(사용자 요청): 프로그램을 특정해서 막는 방식 말고, PC 전체를 잠그고 허용한 프로그램만 쓰는 방식.
        SectionCard("차단 방식") {
            SegmentedTabs(
                labels = listOf("고른 것만 차단", "전체 잠금"),
                selectedIndex = if (allowlistMode) 1 else 0,
                onSelect = { allowlistMode = it == 1 },
                modifier = Modifier.widthIn(max = 420.dp)
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                if (allowlistMode) "걸린 동안 고른 프로그램·사이트와 바탕화면(탐색기)만 열립니다." else "고른 프로그램·사이트만 막습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Spacing.md))

        SectionCard("관리 종류") {
            ToggleRow(
                title = "스케줄",
                description = "정한 시간·요일에 막습니다.",
                checked = scheduleEnabled,
                onCheckedChange = { scheduleEnabled = it }
            )
            ToggleRow(
                title = "일일 사용 한도",
                description = "정한 시간을 다 쓰면 막습니다.",
                checked = dailyLimitEnabled,
                onCheckedChange = { dailyLimitEnabled = it }
            )
            // 전체 잠금 방식엔 실행 전 대기를 쓰지 않는다 — PC의 모든 프로그램에 확인창이 뜨게 된다.
            if (!allowlistMode) {
                ToggleRow(
                    title = "실행 전 대기",
                    description = "열 때마다 기다리고, 열수록 길어집니다.",
                    checked = confirmEnabled,
                    onCheckedChange = { confirmEnabled = it }
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))

        if (scheduleEnabled) {
            SectionCard("스케줄") {
                TimeRangeFields(
                    start = scheduleStartText, onStartChange = { scheduleStartText = it },
                    end = scheduleEndText, onEndChange = { scheduleEndText = it }
                )
                Spacer(Modifier.height(Spacing.sm))
                DayMaskRow(mask = daysMask, onMaskChange = { daysMask = it })
            }
            Spacer(Modifier.height(Spacing.md))
        }

        if (dailyLimitEnabled) {
            SectionCard("일일 사용 한도") {
                DurationFieldsRow(
                    label = "한도",
                    hoursText = dailyLimitHoursText,
                    onHoursChange = { dailyLimitHoursText = it },
                    minutesText = dailyLimitMinutesText,
                    onMinutesChange = { dailyLimitMinutesText = it },
                    secondsText = dailyLimitSecondsText,
                    onSecondsChange = { dailyLimitSecondsText = it }
                )
                Spacer(Modifier.height(Spacing.md))
                Text("적용 시간대 (비우면 하루 종일)", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(Spacing.xs))
                TimeRangeFields(
                    start = dailyLimitApplyStartText, onStartChange = { dailyLimitApplyStartText = it },
                    end = dailyLimitApplyEndText, onEndChange = { dailyLimitApplyEndText = it }
                )
                Text(
                    "이 시간대에만 잠기고, 사용 시간은 늘 쌓입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                DayMaskRow(mask = dailyLimitDaysMask, onMaskChange = { dailyLimitDaysMask = it })
            }
            Spacer(Modifier.height(Spacing.md))
        }

        if (confirmEnabled && !allowlistMode) {
            SectionCard("실행 전 대기") {
                Text("적용 시간대 (비우면 하루 종일)", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(Spacing.xs))
                TimeRangeFields(
                    start = confirmApplyStartText, onStartChange = { confirmApplyStartText = it },
                    end = confirmApplyEndText, onEndChange = { confirmApplyEndText = it }
                )
                Text(
                    "시간대 밖에선 묻지 않고 열립니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                DayMaskRow(mask = confirmDaysMask, onMaskChange = { confirmDaysMask = it })
                Spacer(Modifier.height(Spacing.md))
                DurationFieldsRow(
                    label = "처음 대기",
                    hoursText = initialWaitHoursText,
                    onHoursChange = { initialWaitHoursText = it },
                    minutesText = initialWaitMinutesText,
                    onMinutesChange = { initialWaitMinutesText = it },
                    secondsText = initialWaitSecondsText,
                    onSecondsChange = { initialWaitSecondsText = it }
                )
                Spacer(Modifier.height(Spacing.sm))
                DurationFieldsRow(
                    label = "다시 열 때마다 늘어나는 시간",
                    hoursText = waitIncrementHoursText,
                    onHoursChange = { waitIncrementHoursText = it },
                    minutesText = waitIncrementMinutesText,
                    onMinutesChange = { waitIncrementMinutesText = it },
                    secondsText = waitIncrementSecondsText,
                    onSecondsChange = { waitIncrementSecondsText = it }
                )
                Spacer(Modifier.height(Spacing.sm))
                DurationFieldsRow(
                    label = "다시 묻지 않는 시간",
                    hoursText = confirmCooldownHoursText,
                    onHoursChange = { confirmCooldownHoursText = it },
                    minutesText = confirmCooldownMinutesText,
                    onMinutesChange = { confirmCooldownMinutesText = it },
                    secondsText = confirmCooldownSecondsText,
                    onSecondsChange = { confirmCooldownSecondsText = it }
                )
                Text(
                    "그동안 이 규칙의 다른 프로그램·사이트도 묻지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                ToggleRow(
                    title = "시간 지나면 자동 완화",
                    description = "마지막 확인부터 간격마다 한 단계씩 줄어듭니다.",
                    checked = levelDecayEnabled,
                    onCheckedChange = { levelDecayEnabled = it }
                )
                if (levelDecayEnabled) {
                    Spacer(Modifier.height(Spacing.xs))
                    DurationFieldsRow(
                        label = "완화 간격",
                        hoursText = levelDecayHoursText,
                        onHoursChange = { levelDecayHoursText = it },
                        minutesText = levelDecayMinutesText,
                        onMinutesChange = { levelDecayMinutesText = it },
                        secondsText = levelDecaySecondsText,
                        onSecondsChange = { levelDecaySecondsText = it }
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                ToggleRow(
                    title = "남은 시간 화면 덮개",
                    description = "다시 묻지 않는 동안 화면 구석에 표시합니다.",
                    checked = usageOverlayEnabled,
                    onCheckedChange = { usageOverlayEnabled = it }
                )
                if (usageOverlayEnabled) {
                    Spacer(Modifier.height(Spacing.xs))
                    CompactNumberField(
                        value = overlayLevelStepsToMaxText,
                        onValueChange = { overlayLevelStepsToMaxText = it },
                        label = "몇 번 다시 열면 가장 진해질지"
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }

        // "관리 종류"(스케줄/일일한도/실행 전 대기)와 성격이 달라 별도 섹션으로 분리(95차, 사용자
        // 지적) — 켜고 끄는 스위치와 세부 설정(시간/횟수)을 한 묶음에 같이 둔다. 동기화 on/off 스위치는
        // 편집 화면이 아니라 목록 화면(잠깐 풀기 버튼 옆)으로 이동했다.
        SectionCard("잠깐 풀기") {
            ToggleRow(
                title = "잠깐 풀기 사용",
                description = "목록에서 확인 없이 바로 잠시 풉니다.",
                checked = snoozeEnabled,
                onCheckedChange = { snoozeEnabled = it }
            )
            if (snoozeEnabled) {
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    CompactNumberField(
                        value = snoozeMinutesText,
                        onValueChange = { snoozeMinutesText = it },
                        label = "한 번에 (분)",
                        modifier = Modifier.weight(1f)
                    )
                    CompactNumberField(
                        value = snoozeDailyLimitText,
                        onValueChange = { snoozeDailyLimitText = it },
                        label = "하루 횟수",
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "횟수는 하루 시작 기준 시각에 다시 채워집니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))

        SectionCard("뽀모도로") {
            ToggleRow(
                title = "휴식 시간엔 풀기",
                description = "집중 타이머 휴식 동안 이 규칙을 풉니다.",
                checked = pomodoroUnlockEnabled,
                onCheckedChange = { pomodoroUnlockEnabled = it }
            )
        }
        Spacer(Modifier.height(Spacing.md))

        SectionCard("끄기 금지 기간") {
            Text(
                "시험기간처럼, 이 기간엔 꺼도 켜진 채로 둡니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactDateField(
                    value = forceEnabledFromText,
                    onValueChange = { forceEnabledFromText = it },
                    placeholder = "시작일",
                    modifier = Modifier.weight(1f)
                )
                Text("~", modifier = Modifier.padding(horizontal = Spacing.sm))
                CompactDateField(
                    value = forceEnabledUntilText,
                    onValueChange = { forceEnabledUntilText = it },
                    placeholder = "종료일(포함)",
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))

        TextButton(onClick = { weakeningInfoExpanded = !weakeningInfoExpanded }) {
            Text(if (weakeningInfoExpanded) "접기" else "차단 중에 제한을 약하게 바꾸면?", maxLines = 1, softWrap = false)
        }
        AnimatedVisibility(weakeningInfoExpanded) {
            Text(
                "지금 차단 중인 규칙을 약하게 바꾸면(한도 늘리기, 시간대·요일 줄이기, 대기 줄이기, 항목 빼기, 끄기 등) " +
                    "확인 질문 ${PERSUASION_MESSAGES.size}개를 거쳐야 저장됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.sm)
            )
        }
        Spacer(Modifier.height(Spacing.md))

        SectionCard(if (allowlistMode) "허용할 프로그램·사이트" else "막을 프로그램·사이트") {
            Text(
                if (allowlistMode) "허용할 프로그램 (실행파일 이름, 예: chrome.exe)" else "막을 프로그램 (실행파일 이름, 예: chrome.exe)",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactField(
                    value = newProcessName,
                    onValueChange = { newProcessName = it },
                    placeholder = "실행파일 이름",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.sm))
                Button(onClick = {
                    val processName = newProcessName.trim()
                    if (processName.isNotBlank()) {
                        processNames = processNames + processName
                        newProcessName = ""
                    }
                }) {
                    Text("추가", maxLines = 1, softWrap = false)
                }
            }
            processNames.sorted().forEach { processName ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(processName, modifier = Modifier.weight(1f))
                    IconButton(onClick = { processNames = processNames - processName }) {
                        Icon(Icons.Outlined.Close, contentDescription = "빼기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Hairline()
            }

            Spacer(Modifier.height(Spacing.md))
            Text(
                if (allowlistMode) "허용할 사이트 (도메인, 예: youtube.com)" else "막을 사이트 (도메인, 예: youtube.com)",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactField(
                    value = newDomain,
                    onValueChange = { newDomain = it },
                    placeholder = "예: youtube.com",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.sm))
                Button(onClick = {
                    val domain = newDomain.trim().lowercase()
                    if (domain.isNotBlank()) {
                        domains = domains + domain
                        newDomain = ""
                    }
                }) {
                    Text("추가", maxLines = 1, softWrap = false)
                }
            }
            Text(
                if (allowlistMode) "비워 두면 브라우저 안 사이트는 모두 막힙니다(브라우저 확장 필요)." else "브라우저 확장이 켜져 있어야 막힙니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            domains.sorted().forEach { domain ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(domain, modifier = Modifier.weight(1f))
                    IconButton(onClick = { domains = domains - domain }) {
                        Icon(Icons.Outlined.Close, contentDescription = "빼기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Hairline()
            }
        }
        Spacer(Modifier.height(Spacing.lg))

        val staged = pendingGroup
        if (staged != null) {
            val isLast = pendingMessageIndex == PERSUASION_MESSAGES.lastIndex
            val stepDelaysMs = remember(staged) { randomPersuasionStepDelaysMs() }
            var stepStarted by remember(pendingMessageIndex) { mutableStateOf(false) }
            var stepRemainingSeconds by remember(pendingMessageIndex) { mutableIntStateOf(0) }
            LaunchedEffect(pendingMessageIndex, stepStarted) {
                if (!stepStarted) return@LaunchedEffect
                stepRemainingSeconds = ((stepDelaysMs[pendingMessageIndex] + 999) / 1000).toInt()
                while (stepRemainingSeconds > 0) {
                    delay(1000)
                    stepRemainingSeconds -= 1
                }
                if (isLast) {
                    if (groupId == null) repository.createGroup(staged) else repository.updateGroup(staged)
                    pendingGroup = null
                    pendingMessageIndex = 0
                    pendingMessage = null
                    onDone()
                } else {
                    pendingMessageIndex++
                }
            }
            Text(
                "제한을 약화시키는 변경입니다 (%d/%d)".format(pendingMessageIndex + 1, PERSUASION_MESSAGES.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(PERSUASION_MESSAGES[pendingMessageIndex], style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.sm))
            Button(
                onClick = { stepStarted = true },
                enabled = !stepStarted,
                modifier = Modifier.fillMaxWidth()
            ) {
                val label = if (isLast) "적용" else "예"
                Text(if (stepStarted && stepRemainingSeconds > 0) "$label (${stepRemainingSeconds}초)" else label)
            }
            Spacer(Modifier.height(Spacing.sm))
            OutlinedButton(
                onClick = {
                    pendingGroup = null
                    pendingMessageIndex = 0
                    pendingMessage = "변경을 취소했습니다."
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("취소")
            }
        } else if (pendingDelete) {
            val isLast = pendingDeleteMessageIndex == PERSUASION_MESSAGES.lastIndex
            val stepDelaysMs = remember(pendingDelete) { randomPersuasionStepDelaysMs() }
            var stepStarted by remember(pendingDeleteMessageIndex) { mutableStateOf(false) }
            var stepRemainingSeconds by remember(pendingDeleteMessageIndex) { mutableIntStateOf(0) }
            LaunchedEffect(pendingDeleteMessageIndex, stepStarted) {
                if (!stepStarted) return@LaunchedEffect
                stepRemainingSeconds = ((stepDelaysMs[pendingDeleteMessageIndex] + 999) / 1000).toInt()
                while (stepRemainingSeconds > 0) {
                    delay(1000)
                    stepRemainingSeconds -= 1
                }
                if (isLast) {
                    if (groupId != null) repository.deleteGroup(groupId)
                    pendingDelete = false
                    pendingDeleteMessageIndex = 0
                    pendingMessage = null
                    onDone()
                } else {
                    pendingDeleteMessageIndex++
                }
            }
            Text(
                "지금 차단 중인 차단 규칙의 삭제입니다 (%d/%d)".format(pendingDeleteMessageIndex + 1, PERSUASION_MESSAGES.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(PERSUASION_MESSAGES[pendingDeleteMessageIndex], style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.sm))
            Button(
                onClick = { stepStarted = true },
                enabled = !stepStarted,
                modifier = Modifier.fillMaxWidth()
            ) {
                val label = if (isLast) "삭제" else "예"
                Text(if (stepStarted && stepRemainingSeconds > 0) "$label (${stepRemainingSeconds}초)" else label)
            }
            Spacer(Modifier.height(Spacing.sm))
            OutlinedButton(
                onClick = {
                    pendingDelete = false
                    pendingDeleteMessageIndex = 0
                    pendingMessage = "삭제를 취소했습니다."
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("취소")
            }
        } else {
            pendingMessage?.let {
                Text(it)
                Spacer(Modifier.height(Spacing.sm))
            }
            Button(
                onClick = {
                    val finalName = name.ifBlank { "이름 없는 그룹" }
                    // 98차 버그 수정: originalGroup은 이 화면 진입 시점의 스냅샷이라, 편집 중에
                    // 왼쪽 목록 패널에서 켜짐/스누즈/차단 시도 등이 바뀌어도 반영이 안 돼 저장 시
                    // 그 변경을 그대로 덮어써버리는 버그가 있었다 — 저장 직전에 최신 상태를 다시 읽어와
                    // groupEnabled 등 "이 화면에서 편집하지 않는" 필드는 항상 최신값을 쓴다.
                    val currentGroup = groupId?.let { repository.getGroup(it) } ?: originalGroup
                    fun buildGroup() = Group(
                        id = groupId ?: 0,
                        name = finalName,
                        description = description,
                        selfMessageText = selfMessageText,
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
                        usageOverlayEnabled = usageOverlayEnabled,
                        overlayLevelStepsToMax = overlayLevelStepsToMaxText.trim().toIntOrNull()?.coerceAtLeast(1) ?: 5,
                        snoozeEnabled = snoozeEnabled,
                        snoozeMinutes = snoozeMinutesText.trim().toIntOrNull()?.coerceAtLeast(1) ?: 30,
                        snoozeDailyLimit = snoozeDailyLimitText.trim().toIntOrNull()?.coerceAtLeast(1) ?: 3,
                        snoozedUntilEpochMillis = currentGroup?.snoozedUntilEpochMillis,
                        snoozeUsedDate = currentGroup?.snoozeUsedDate ?: "",
                        snoozeUsedCount = currentGroup?.snoozeUsedCount ?: 0,
                        forceEnabledFrom = forceEnabledFromText.trim().ifBlank { null },
                        forceEnabledUntil = forceEnabledUntilText.trim().ifBlank { null },
                        pomodoroUnlockEnabled = pomodoroUnlockEnabled,
                        initialWaitSeconds = hmsTextToSeconds(initialWaitHoursText, initialWaitMinutesText, initialWaitSecondsText),
                        waitIncrementSeconds = hmsTextToSeconds(waitIncrementHoursText, waitIncrementMinutesText, waitIncrementSecondsText),
                        confirmCooldownSeconds = hmsTextToSeconds(confirmCooldownHoursText, confirmCooldownMinutesText, confirmCooldownSecondsText),
                        levelDecayEnabled = levelDecayEnabled,
                        levelDecayIntervalSeconds = hmsTextToSeconds(levelDecayHoursText, levelDecayMinutesText, levelDecaySecondsText),
                        scheduleEnabled = scheduleEnabled,
                        groupEnabled = currentGroup?.groupEnabled ?: true,
                        groupOffPending = currentGroup?.groupOffPending ?: false,
                        groupOffMessageIndex = currentGroup?.groupOffMessageIndex ?: 0,
                        // 98차 발견: 이 두 필드도 이 폼에 없어서 저장할 때마다 조롱 문구 강도(오늘 시도
                        // 횟수)가 매번 0으로 리셋되고 있었다 — 위와 동일하게 최신값을 그대로 이어받는다.
                        blockAttemptDate = currentGroup?.blockAttemptDate ?: "",
                        blockAttemptCount = currentGroup?.blockAttemptCount ?: 0,
                        processNames = processNames.toList(),
                        domains = domains.toList(),
                        syncEnabled = syncEnabled,
                        allowlistMode = allowlistMode
                    )
                    if (groupId == null) {
                        // 새 규칙을 만드는데 그 이름이 불러오기 목록(원격)에 이미 있으면, 저장하기 전에
                        // 먼저 물어본다(94차). 새 그룹은 originalGroup이 없어 약화 감지 대상이 아니므로
                        // 곧바로 만든다.
                        scope.launch {
                            val remoteMatch = withContext(Dispatchers.IO) {
                                repository.findRemoteGroupSettingByName(finalName)
                            }
                            if (remoteMatch != null) {
                                pendingCreateCollisionEntry = remoteMatch
                            } else {
                                repository.createGroup(buildGroup())
                                onDone()
                            }
                        }
                    } else {
                        val group = buildGroup()
                        val original = originalGroup
                        val weakening = original != null && evaluator.detectWeakeningEdit(
                            original = original,
                            updated = group,
                            originalProcessNames = originalProcessNames,
                            updatedProcessNames = processNames,
                            originalDomains = originalDomains,
                            updatedDomains = domains
                        )
                        if (weakening) {
                            pendingGroup = group
                            pendingMessage = null
                        } else {
                            repository.updateGroup(group)
                            onDone()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("저장", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
            }
        }
    }

    if (confirmPlainDelete && groupId != null) {
        LedgerAlertDialog(
            onDismissRequest = { confirmPlainDelete = false },
            title = { Text("차단 규칙 삭제") },
            text = { Text("\"${name.ifBlank { "이름 없는 그룹" }}\" 규칙을 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPlainDelete = false
                    repository.deleteGroup(groupId)
                    onDone()
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmPlainDelete = false }) { Text("취소") } }
        )
    }

    // 새 규칙 이름이 불러오기 목록(원격)과 겹칠 때(94차) — "예"면 불러온 설정으로 만들고 동기화를 켜고,
    // "아니오"면 지금 입력한 내용 그대로 동기화 꺼짐으로 만든다.
    pendingCreateCollisionEntry?.let { remoteEntry ->
        LedgerAlertDialog(
            onDismissRequest = { pendingCreateCollisionEntry = null },
            title = { Text("같은 이름의 규칙이 있습니다") },
            text = { Text("불러오기 목록에 있는 규칙과 동기화할까요? \"예\"면 지금 입력 대신 불러온 설정으로 만듭니다.") },
            confirmButton = {
                TextButton(onClick = {
                    val finalName = name.ifBlank { "이름 없는 그룹" }
                    val importedGroup = Group(id = 0, name = finalName, syncEnabled = true).applyGroupSettingsJson(remoteEntry)
                    repository.createGroup(importedGroup)
                    pendingCreateCollisionEntry = null
                    onDone()
                }) { Text("예") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val finalName = name.ifBlank { "이름 없는 그룹" }
                    val group = Group(
                        id = 0,
                        name = finalName,
                        description = description,
                        selfMessageText = selfMessageText,
                        dailyLimitSeconds = if (dailyLimitEnabled) {
                            hmsTextToSeconds(dailyLimitHoursText, dailyLimitMinutesText, dailyLimitSecondsText)
                        } else null,
                        dailyLimitApplyStartMinute = textToMinutes(dailyLimitApplyStartText),
                        dailyLimitApplyEndMinute = textToMinutes(dailyLimitApplyEndText),
                        dailyLimitDaysMask = dailyLimitDaysMask,
                        scheduleStartMinute = textToMinutes(scheduleStartText),
                        scheduleEndMinute = textToMinutes(scheduleEndText),
                        scheduleDaysMask = daysMask,
                        confirmEnabled = confirmEnabled,
                        confirmApplyStartMinute = textToMinutes(confirmApplyStartText),
                        confirmApplyEndMinute = textToMinutes(confirmApplyEndText),
                        confirmDaysMask = confirmDaysMask,
                        usageOverlayEnabled = usageOverlayEnabled,
                        overlayLevelStepsToMax = overlayLevelStepsToMaxText.trim().toIntOrNull()?.coerceAtLeast(1) ?: 5,
                        snoozeEnabled = snoozeEnabled,
                        snoozeMinutes = snoozeMinutesText.trim().toIntOrNull()?.coerceAtLeast(1) ?: 30,
                        snoozeDailyLimit = snoozeDailyLimitText.trim().toIntOrNull()?.coerceAtLeast(1) ?: 3,
                        forceEnabledFrom = forceEnabledFromText.trim().ifBlank { null },
                        forceEnabledUntil = forceEnabledUntilText.trim().ifBlank { null },
                        pomodoroUnlockEnabled = pomodoroUnlockEnabled,
                        initialWaitSeconds = hmsTextToSeconds(initialWaitHoursText, initialWaitMinutesText, initialWaitSecondsText),
                        waitIncrementSeconds = hmsTextToSeconds(waitIncrementHoursText, waitIncrementMinutesText, waitIncrementSecondsText),
                        confirmCooldownSeconds = hmsTextToSeconds(confirmCooldownHoursText, confirmCooldownMinutesText, confirmCooldownSecondsText),
                        levelDecayEnabled = levelDecayEnabled,
                        levelDecayIntervalSeconds = hmsTextToSeconds(levelDecayHoursText, levelDecayMinutesText, levelDecaySecondsText),
                        scheduleEnabled = scheduleEnabled,
                        processNames = processNames.toList(),
                        domains = domains.toList(),
                        syncEnabled = false,
                        allowlistMode = allowlistMode
                    )
                    repository.createGroup(group)
                    pendingCreateCollisionEntry = null
                    onDone()
                }) { Text("아니오") }
            }
        )
    }

}
