package com.phonelock.desktop.ui

import com.phonelock.desktop.ui.components.PersuasionStepView
import com.phonelock.desktop.ui.components.LedgerAlertDialog
import com.phonelock.desktop.ui.theme.LocalPhoneLockPalette
import com.phonelock.desktop.ui.components.Overline
import com.phonelock.desktop.ui.components.NoticeTone
import com.phonelock.desktop.ui.components.NoticeStrip
import com.phonelock.desktop.ui.components.Hairline
import com.phonelock.desktop.ui.components.BigNumber
import androidx.compose.ui.draw.clip
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.phonelock.shared.PERSUASION_MESSAGES
import com.phonelock.shared.randomPersuasionStepDelaysMs
import com.phonelock.desktop.data.Group
import com.phonelock.desktop.data.ImportableGroupSetting
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.applyGroupSettingsJson
import com.phonelock.desktop.data.fetchImportableGroupSettings
import com.phonelock.desktop.data.findRemoteGroupSettingByName
import com.phonelock.desktop.data.importGroupSetting
import com.phonelock.desktop.data.isEffectivelyOffline
import com.phonelock.desktop.data.syncGroupSettingsFromFirebase
import com.phonelock.desktop.monitor.LockEvaluator
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun GroupListScreen(
    repository: Repository,
    groups: List<Group>,
    selectedGroupId: Long? = null,
    onAddClick: () -> Unit,
    onEditClick: (Long) -> Unit
) {
    val evaluator = remember { LockEvaluator(repository) }
    val scope = rememberCoroutineScope()
    var penaltyMessage by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    // 동기화 on/off 켜기 시 이름 충돌 확인(94차 신규, 95차에 편집 화면에서 목록 화면으로 이동) — 안드로이드판과 대칭.
    var pendingSyncToggleGroup by remember { mutableStateOf<Group?>(null) }
    var pendingSyncToggleEntry by remember { mutableStateOf<JSONObject?>(null) }

    // 144차(안드로이드판과 같은 언어): 제목은 관리 탭 머리가 보여주므로 "지금 차단 중인 규칙 수"를 크게, 목록은 가는 선으로.
    val lockedCount = groups.count { it.groupEnabled && evaluator.isAnyManagementActiveToday(it) }
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Overline("지금 차단 중")
                BigNumber(
                    "$lockedCount",
                    unit = if (groups.isEmpty()) "개" else "/ ${groups.size}개 규칙",
                    style = MaterialTheme.typography.displayMedium,
                    color = if (lockedCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                )
            }
            // 98차(사용자 요청, 안드로이드판은 당겨서 새로고침) — 데스크탑은 스와이프 제스처가 없어 버튼으로.
            androidx.compose.material3.IconButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { if (!repository.isEffectivelyOffline()) repository.syncGroupSettingsFromFirebase() }
                }
            }) { Icon(Icons.Outlined.Refresh, contentDescription = "새로고침", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            androidx.compose.material3.TextButton(onClick = { showImportDialog = true }) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("불러오기", maxLines = 1, softWrap = false)
            }
        }
        if (showImportDialog) {
            GroupImportDialog(repository = repository, onDismiss = { showImportDialog = false })
        }
        Spacer(Modifier.height(Spacing.md))
        androidx.compose.material3.FilledTonalButton(onClick = onAddClick, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("차단 규칙 추가", maxLines = 1, softWrap = false)
        }
        // 안내가 아니라 "지금 이 동작이 막혔다"는 경고 — 경고 띠로.
        penaltyMessage?.let {
            Spacer(Modifier.height(Spacing.sm))
            NoticeStrip(it, tone = NoticeTone.Warning)
        }
        Spacer(Modifier.height(Spacing.sm))
        Hairline()
        if (groups.isEmpty()) {
            Column(Modifier.padding(top = Spacing.lg)) {
                Text("아직 차단 규칙이 없습니다", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "막고 싶은 프로그램·사이트를 고르고 언제(시간대·하루 한도·열기 전 확인) 막을지 정합니다.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
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
                                // 하루 한도는 그룹마다 다르게 설정할 수 있으므로(87차 snoozeDailyLimit)
                                // 안내 문구도 하드코딩("하루 3회") 대신 실제 설정값을 보여준다.
                                penaltyMessage = "\"${group.name}\" 차단 규칙은 오늘 잠깐 풀기를 이미 다 썼습니다(하루 ${group.snoozeDailyLimit}회)."
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
                                penaltyMessage = "\"${group.name}\" 차단 규칙이 지금 차단 중이라 바로 끌 수 없습니다. 확인 질문 20개에 하나씩 \"예\"를 눌러야 꺼집니다."
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
                        },
                        onSyncToggle = { newValue ->
                            if (newValue) {
                                scope.launch {
                                    val remoteMatch = withContext(Dispatchers.IO) {
                                        repository.findRemoteGroupSettingByName(group.name)
                                    }
                                    if (remoteMatch != null) {
                                        pendingSyncToggleGroup = group
                                        pendingSyncToggleEntry = remoteMatch
                                    } else {
                                        repository.updateGroup(group.copy(syncEnabled = true))
                                    }
                                }
                            } else {
                                repository.updateGroup(group.copy(syncEnabled = false))
                            }
                        }
                    )
                    Hairline()
                }
            }
        }
    }

    // 로컬 전용 규칙의 동기화를 켜려는데 같은 이름이 이미 불러오기 목록에 있을 때(94차, 95차에 이 화면
    // 으로 이동) — "예"면 이 규칙의 설정을 그 원격 내용으로 바로 덮어쓰고 동기화를 켠다, "아니오"면 취소.
    val collisionGroup = pendingSyncToggleGroup
    val collisionEntry = pendingSyncToggleEntry
    if (collisionGroup != null && collisionEntry != null) {
        LedgerAlertDialog(
            onDismissRequest = { pendingSyncToggleGroup = null; pendingSyncToggleEntry = null },
            title = { Text("같은 이름의 규칙이 있습니다") },
            text = { Text("불러오기 목록에 있는 규칙과 동기화할까요? \"예\"면 이 규칙이 불러온 설정으로 바뀝니다.") },
            confirmButton = {
                TextButton(onClick = {
                    val updated = collisionGroup.applyGroupSettingsJson(collisionEntry).copy(syncEnabled = true)
                    repository.updateGroup(updated)
                    pendingSyncToggleGroup = null
                    pendingSyncToggleEntry = null
                }) { Text("예") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSyncToggleGroup = null; pendingSyncToggleEntry = null }) { Text("아니오") }
            }
        )
    }
}

/**
 * "불러오기" 화면(94차 신규, 안드로이드판과 대칭) — 원격에 있고 이 기기엔 아직 동기화로 연결 안 된
 * 차단 규칙 이름들을 보여주고, 고른 것만 로컬로 불러온다(같은 이름의 로컬 규칙이 있으면 설정만
 * 덮어쓰고 동기화를 켠다). 자동으로 전부 병합하던 기존 방식(88차)을 opt-in 방식으로 바꾼 핵심 화면.
 */
@Composable
private fun GroupImportDialog(repository: Repository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf(listOf<ImportableGroupSetting>()) }
    var importedNames by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) { repository.fetchImportableGroupSettings() }
        loading = false
    }

    LedgerAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("불러오기") },
        text = {
            Column {
                Text(
                    "다른 기기에서 동기화를 켠 규칙 중 이 기기에 없는 것입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    items.isEmpty() -> Text("불러올 수 있는 차단 규칙이 없습니다.", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        items.forEach { item ->
                            val imported = item.name in importedNames
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(item.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if (imported) {
                                    Text("불러옴", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { repository.importGroupSetting(item.json) }
                                            importedNames = importedNames + item.name
                                        }
                                    }) {
                                        Text("불러오기")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } }
    )
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
    onCancelPending: () -> Unit,
    onSyncToggle: (Boolean) -> Unit
) {
    val pending = group.groupEnabled && group.groupOffPending

    // 창을 벗어나면(다른 창으로 포커스 이동 등) 진행 중이던 끄기 시도를 취소하고 원래 상태(켜짐)로 되돌린다.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(group.id, pending, windowInfo.isWindowFocused) {
        if (pending && !windowInfo.isWindowFocused) {
            onCancelPending()
        }
    }

    val palette = LocalPhoneLockPalette.current
    val locked = restrictingNow
    val showSnoozeChip = !pending && group.groupEnabled && group.snoozeEnabled && (restrictingNow || snoozeActive)
    val snoozeClickable = !snoozeActive && snoozeRemainingToday > 0
    val snoozeText = if (snoozeActive) "잠깐 풀기 중" else "잠깐 풀기 ${group.snoozeMinutes}분 · $snoozeRemainingToday/${group.snoozeDailyLimit}회 남음"

    // 선택한 그룹(오른쪽에서 편집 중)은 옅은 판으로 강조 — 마스터-디테일 레이아웃.
    Column(
        Modifier.fillMaxWidth()
            .alpha(if (group.groupEnabled) 1f else 0.5f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).background(
                    if (locked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (locked) Icons.Filled.Lock else Icons.Outlined.LockOpen,
                    contentDescription = if (locked) "오늘 차단 중" else "차단 안 함",
                    tint = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // 전체 잠금 방식 규칙(142차)은 목록의 뜻이 반대라 이름 옆에 표시해 둔다.
                    if (group.allowlistMode) {
                        Text(
                            "전체 잠금",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    if (locked) "지금 차단 중" else if (group.groupEnabled) "지금은 열려 있음" else "꺼짐",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(Spacing.xs))
            // 동기화 표시 — 누르면 켜고 끈다(켤 때 같은 이름의 원격 규칙이 있으면 확인창).
            IconButton(onClick = { onSyncToggle(!group.syncEnabled) }) {
                Icon(
                    if (group.syncEnabled) Icons.Filled.Cloud else Icons.Outlined.CloudOff,
                    contentDescription = if (group.syncEnabled) "다른 기기와 동기화 켜짐" else "다른 기기와 동기화 꺼짐",
                    tint = if (group.syncEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Switch(checked = group.groupEnabled, onCheckedChange = onGroupToggle)
        }
        if (showSnoozeChip) {
            Spacer(Modifier.height(Spacing.sm))
            Row(
                Modifier.padding(start = 50.dp)
                    .clip(RoundedCornerShape(50))
                    .background(palette.warningContainer)
                    .let { if (snoozeClickable) it.clickable(onClick = onSnooze) else it }
                    .alpha(if (snoozeClickable || snoozeActive) 1f else 0.6f)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Bedtime, contentDescription = null, tint = palette.warning, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(snoozeText, style = MaterialTheme.typography.labelMedium, color = palette.warning, maxLines = 1, softWrap = false)
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
            Spacer(Modifier.height(Spacing.sm))
            // 147차: 회색 판 → 확인 질문 공용 모양(라벨 + "n / 20" + 진행 선 + 강조 막대 질문 + 버튼 줄).
            PersuasionStepView(
                context = "\"${group.name}\" 규칙 끄기",
                step = messageIndex + 1,
                total = PERSUASION_MESSAGES.size,
                message = PERSUASION_MESSAGES[messageIndex],
                confirmLabel = (if (isLast) "끄기" else "예").let { if (stepStarted && stepRemainingSeconds > 0) "$it (${stepRemainingSeconds}초)" else it },
                confirmEnabled = !stepStarted,
                onConfirm = { stepStarted = true },
                onCancel = onCancelPending,
                modifier = Modifier.padding(start = 50.dp)
            )
        }
    }
}
