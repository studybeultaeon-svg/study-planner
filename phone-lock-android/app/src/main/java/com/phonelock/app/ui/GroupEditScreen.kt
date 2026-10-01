package com.phonelock.app.ui

import com.phonelock.app.ui.components.LedgerAlertDialog
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phonelock.app.data.AppGroup
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.applyGroupSettingsJson
import com.phonelock.app.data.findRemoteGroupSettingByName
import com.phonelock.app.service.LockEvaluator
import org.json.JSONObject
import com.phonelock.shared.PERSUASION_MESSAGES
import com.phonelock.app.ui.components.CompactDateField
import com.phonelock.app.ui.components.CompactField
import com.phonelock.app.ui.components.CompactNumberField
import com.phonelock.app.ui.components.DurationFieldsRow
import com.phonelock.app.ui.components.PersuasionStepper
import com.phonelock.app.ui.components.SectionCard
import com.phonelock.app.ui.components.SegmentedTabs
import com.phonelock.app.ui.components.Hairline
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

// 96차: 사용자가 그려준 시안대로 사각 FilterChip 대신 동그란 요일 칩(선택 시 primary 채움)으로 교체.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayMaskRow(mask: Int, onMaskChange: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

/** "09:00 ~ 18:00" 시간대 입력 두 칸(146차, 스케줄·한도·실행 전 대기 공용). 비워 두면 그 시간대를 쓰지 않는다. */
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

// 태블릿 무대응(의도적 판단, 84차): 데스크탑판 GroupEditScreen.kt도 SectionCard를 세로로 쌓기만 하는
// 단일 Column이고 ResponsiveSplit 등 좌우 분할이 없다 — 안드로이드가 LazyColumn을 쓰는 이유(설치 앱
// 수백 개를 스크롤해야 하는 성능 문제)도 폭과 무관한 안드로이드 고유 사정이라 태블릿 전용 분기가
// 필요 없다.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupEditScreen(
    repository: PhoneLockRepository,
    groupId: Long?,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val evaluator = remember { LockEvaluator(repository) }

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
    // 동기화(94차 신규) — 이 그룹을 크로스디바이스 설정 동기화에 참여시킬지. on/off 스위치 자체는
    // 95차부터 이 편집 화면이 아니라 GroupListScreen(잠깐 풀기 버튼 옆)에 있다 — 여기서는 저장 시
    // 기존 값을 그대로 유지해 전달하는 용도로만 상태를 들고 있는다. 새 규칙 생성 시 이름 충돌 확인은
    // 아래 pendingCreateCollisionEntry가 담당한다.
    var syncEnabled by remember { mutableStateOf(false) }
    // 전체 잠금 방식(142차) — 켜면 아래 앱/사이트 목록이 "막을 대상"이 아니라 "허용할 대상"이 된다. 목록처럼 기기마다
    // 따로 두는 값이라 동기화로 받아오는 설정(applyGroupToForm)에는 들어 있지 않다.
    var allowlistMode by remember { mutableStateOf(false) }
    var pendingCreateCollisionEntry by remember { mutableStateOf<JSONObject?>(null) }

    // 원격 설정 항목을 화면의 입력 필드들에 반영한다 — 최초 로드(기존 그룹 편집)와 새 규칙 생성 시
    // 이름 충돌 확인창에서 쓴다(94차). name/syncEnabled/selfMessageText는 동기화 대상이 아니므로
    // 여기서 건드리지 않는다.
    fun applyGroupToForm(group: AppGroup) {
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
    var confirmPlainDelete by remember { mutableStateOf(false) }
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

    val installedApps = rememberLaunchableApps()
    val filteredApps = remember(installedApps, searchQuery, selectedPackages) {
        val base = if (searchQuery.isBlank()) installedApps
        else installedApps.filter { it.label.contains(searchQuery, ignoreCase = true) }
        base.sortedByDescending { selectedPackages.contains(it.packageName) }
    }

    LaunchedEffect(groupId) {
        if (groupId != null) {
            repository.getGroup(groupId)?.let { group ->
                name = group.name
                selfMessageText = group.selfMessageText
                syncEnabled = group.syncEnabled
                allowlistMode = group.allowlistMode
                applyGroupToForm(group)
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
        containerColor = MaterialTheme.colorScheme.background,
        // 144차: 앱바 대신 편집형 머리 — 뒤로 가기 + 작은 경로 라벨 + 큰 제목(규칙 이름이 있으면 그 이름).
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDone) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                    com.phonelock.app.ui.components.Overline(
                        if (groupId == null) "관리 · 새 차단 규칙" else "관리 · 차단 규칙 편집",
                        Modifier.weight(1f)
                    )
                    // 146차: 복사·삭제는 화면 아래 버튼 더미 대신 머리 오른쪽 아이콘(확인 질문 진행 중엔 숨김 — 예전과 같다).
                    if (groupId != null && loaded && pendingGroup == null && !pendingDelete) {
                        IconButton(onClick = { scope.launch { repository.copyGroup(groupId); onDone() } }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "복사")
                        }
                        IconButton(onClick = {
                            scope.launch {
                                val original = originalGroup
                                if (original != null && evaluator.requiresDeleteGate(original)) {
                                    pendingDelete = true
                                    pendingMessage = null
                                } else {
                                    // 작은 아이콘이 된 만큼 잘못 눌러 바로 지워지지 않게 한 번 묻는다(146차).
                                    confirmPlainDelete = true
                                }
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
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Spacing.gutter).padding(bottom = Spacing.sm)
                )
                com.phonelock.app.ui.components.Hairline()
            }
        },
        bottomBar = {
            if (loaded) {
                // 146차: 아래엔 "저장" 하나만(복사·삭제는 머리 오른쪽 아이콘). 본문과는 가는 선으로 나눈다.
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                Hairline()
                Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.sm)) {
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
                            headerText = "지금 차단 중인 차단 규칙의 삭제입니다 (%d/%d)".format(pendingDeleteMessageIndex + 1, PERSUASION_MESSAGES.size),
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
                                    val finalName = name.ifBlank { "이름 없는 그룹" }
                                    // 새 규칙을 만드는데 그 이름이 불러오기 목록(원격)에 이미 있으면, 저장하기
                                    // 전에 먼저 물어본다(94차) — 기존 그룹 편집(groupId != null)은 이름이
                                    // 바뀌는 경우가 드물고 원래 있던 규칙이라 이 확인 대상이 아니다.
                                    if (groupId == null) {
                                        val remoteMatch = repository.findRemoteGroupSettingByName(finalName)
                                        if (remoteMatch != null) {
                                            pendingCreateCollisionEntry = remoteMatch
                                            return@launch
                                        }
                                    }
                                    // 98차 버그 수정(데스크탑판과 대칭): originalGroup은 이 화면 진입
                                    // 시점의 스냅샷이라, 편집 중에 그룹 목록에서 켜짐/스누즈/차단 시도
                                    // 등이 바뀌어도 반영이 안 돼 저장 시 그 변경을 그대로 덮어써버리는
                                    // 버그가 있었다 — 저장 직전에 최신 상태를 다시 읽어와 이 화면에서
                                    // 편집하지 않는 필드는 항상 최신값을 쓴다.
                                    val currentGroup = groupId?.let { repository.getGroup(it) } ?: originalGroup
                                    val group = AppGroup(
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
                                        initialWaitSeconds = hmsTextToSeconds(initialWaitHoursText, initialWaitMinutesText, initialWaitSecondsText),
                                        waitIncrementSeconds = hmsTextToSeconds(waitIncrementHoursText, waitIncrementMinutesText, waitIncrementSecondsText),
                                        confirmCooldownSeconds = hmsTextToSeconds(confirmCooldownHoursText, confirmCooldownMinutesText, confirmCooldownSecondsText),
                                        levelDecayEnabled = levelDecayEnabled,
                                        levelDecayIntervalSeconds = hmsTextToSeconds(levelDecayHoursText, levelDecayMinutesText, levelDecaySecondsText),
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
                                        scheduleEnabled = scheduleEnabled,
                                        groupEnabled = currentGroup?.groupEnabled ?: true,
                                        groupOffPending = currentGroup?.groupOffPending ?: false,
                                        groupOffMessageIndex = currentGroup?.groupOffMessageIndex ?: 0,
                                        // 98차 발견: 이 두 필드도 이 폼에 없어서 저장할 때마다 조롱 문구
                                        // 강도(오늘 시도 횟수)가 매번 0으로 리셋되고 있었다.
                                        blockAttemptDate = currentGroup?.blockAttemptDate ?: "",
                                        blockAttemptCount = currentGroup?.blockAttemptCount ?: 0,
                                        syncEnabled = syncEnabled,
                                        allowlistMode = allowlistMode
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
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Text("저장", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                        }
                    }
                }
                }
            }
        }
    ) { padding ->
        if (!loaded) return@Scaffold

        // 146차: 흰 판 묶음 → 가는 선 묶음, 설명은 한 줄로(자세한 동작은 도움말). 폭은 차단 규칙 목록과 같은 760dp까지.
        LazyColumn(
            Modifier.fillMaxSize().widthIn(max = 760.dp).padding(padding),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xs, bottom = Spacing.lg)
        ) {
            item {
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

                // 142차(사용자 요청): 앱을 특정해서 막는 방식 말고, 기기 전체를 잠그고 허용한 앱만 쓰는 방식.
                SectionCard("차단 방식") {
                    SegmentedTabs(
                        labels = listOf("고른 것만 차단", "전체 잠금"),
                        selectedIndex = if (allowlistMode) 1 else 0,
                        onSelect = { allowlistMode = it == 1 }
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        if (allowlistMode) "걸린 동안 고른 앱·사이트와 전화·시계·홈만 열립니다." else "고른 앱·사이트만 막습니다.",
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
                    // 전체 잠금 방식엔 실행 전 대기를 쓰지 않는다 — 기기의 모든 앱에 확인창이 뜨게 된다.
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
                            "그동안 이 규칙의 다른 앱·사이트도 묻지 않습니다.",
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
                            description = "다시 묻지 않는 동안 화면에 표시합니다.",
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

                // "관리 종류"(스케줄/일일한도/실행 전 대기)와 성격이 달라 별도 섹션으로 분리(95차,
                // 사용자 지적) — 켜고 끄는 스위치와 세부 설정(시간/횟수)을 한 묶음에 같이 둔다. 동기화
                // on/off 스위치는 편집 화면이 아니라 목록 화면(잠깐 풀기 버튼 옆)으로 이동했다.
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

                // 146차: 이모지 알약 머리 + Material TabRow → 다른 묶음과 같은 가는 선 머리 + 세그먼트 탭.
                SectionCard(if (allowlistMode) "허용할 앱·사이트" else "막을 앱·사이트") {
                    SegmentedTabs(
                        labels = listOf("앱 ${selectedPackages.size}", "사이트 ${selectedSites.size}"),
                        selectedIndex = memberTab,
                        onSelect = { memberTab = it }
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    if (memberTab == 0) {
                        CompactField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            leadingIcon = Icons.Outlined.Search,
                            placeholder = "앱 이름 검색"
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CompactField(
                                value = newSiteDomain,
                                onValueChange = { newSiteDomain = it },
                                placeholder = "예: youtube.com",
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
                                Text("추가", maxLines = 1, softWrap = false)
                            }
                        }
                        // 안드로이드 사이트 판정은 접근성 서비스가 이 브라우저들의 주소창을 읽어서 한다
                        // (AppMonitorAccessibilityService) — 크롬 확장은 데스크탑 전용이다.
                        Text(
                            if (allowlistMode) "비워 두면 브라우저 안 사이트는 모두 막힙니다." else "Chrome·삼성 인터넷·Google 앱에서 막힙니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
            }

            if (memberTab == 0) {
                // 설치 앱 목록은 IO에서 읽어오므로(rememberLaunchableApps) 첫 프레임엔 아직 비어 있다.
                if (installedApps.isEmpty()) {
                    item {
                        Text(
                            "앱 목록을 불러오는 중…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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

    if (confirmPlainDelete && groupId != null) {
        LedgerAlertDialog(
            onDismissRequest = { confirmPlainDelete = false },
            title = { Text("차단 규칙 삭제") },
            text = { Text("\"${name.ifBlank { "이름 없는 그룹" }}\" 규칙을 삭제할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPlainDelete = false
                    scope.launch {
                        repository.getGroup(groupId)?.let { repository.deleteGroup(it) }
                        onDone()
                    }
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
                    val entryToApply = remoteEntry
                    pendingCreateCollisionEntry = null
                    scope.launch {
                        val finalName = name.ifBlank { "이름 없는 그룹" }
                        val importedGroup = AppGroup(name = finalName, syncEnabled = true, allowlistMode = allowlistMode)
                            .applyGroupSettingsJson(entryToApply)
                        val savedId = repository.createGroup(importedGroup)
                        repository.setMembers(savedId, selectedPackages)
                        repository.setGroupSites(savedId, selectedSites)
                        onDone()
                    }
                }) { Text("예") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingCreateCollisionEntry = null
                    scope.launch {
                        val group = AppGroup(
                            name = name.ifBlank { "이름 없는 그룹" },
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
                            initialWaitSeconds = hmsTextToSeconds(initialWaitHoursText, initialWaitMinutesText, initialWaitSecondsText),
                            waitIncrementSeconds = hmsTextToSeconds(waitIncrementHoursText, waitIncrementMinutesText, waitIncrementSecondsText),
                            confirmCooldownSeconds = hmsTextToSeconds(confirmCooldownHoursText, confirmCooldownMinutesText, confirmCooldownSecondsText),
                            levelDecayEnabled = levelDecayEnabled,
                            levelDecayIntervalSeconds = hmsTextToSeconds(levelDecayHoursText, levelDecayMinutesText, levelDecaySecondsText),
                            usageOverlayEnabled = usageOverlayEnabled,
                            overlayLevelStepsToMax = overlayLevelStepsToMaxText.trim().toIntOrNull()?.coerceAtLeast(1) ?: 5,
                            snoozeEnabled = snoozeEnabled,
                            snoozeMinutes = snoozeMinutesText.trim().toIntOrNull()?.coerceAtLeast(1) ?: 30,
                            snoozeDailyLimit = snoozeDailyLimitText.trim().toIntOrNull()?.coerceAtLeast(1) ?: 3,
                            forceEnabledFrom = forceEnabledFromText.trim().ifBlank { null },
                            forceEnabledUntil = forceEnabledUntilText.trim().ifBlank { null },
                            pomodoroUnlockEnabled = pomodoroUnlockEnabled,
                            scheduleEnabled = scheduleEnabled,
                            syncEnabled = false,
                            allowlistMode = allowlistMode
                        )
                        val savedId = repository.createGroup(group)
                        repository.setMembers(savedId, selectedPackages)
                        repository.setGroupSites(savedId, selectedSites)
                        onDone()
                    }
                }) { Text("아니오") }
            }
        )
    }

}
