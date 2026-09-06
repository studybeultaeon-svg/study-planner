package com.phonelock.desktop.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import com.phonelock.shared.PERSUASION_MESSAGES
import com.phonelock.shared.randomPersuasionStepDelaysMs
import com.phonelock.desktop.data.Group
import com.phonelock.desktop.data.ImportableGroupSetting
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.applyGroupSettingsJson
import com.phonelock.desktop.data.fetchImportableGroupSettings
import com.phonelock.desktop.data.findRemoteGroupSettingByName
import com.phonelock.desktop.data.importGroupSetting
import com.phonelock.desktop.monitor.LockEvaluator
import com.phonelock.desktop.ui.components.formatHms
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

    Column(Modifier.fillMaxSize().padding(Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "🗂️ 차단 규칙",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { showImportDialog = true }) {
                Text("⬇ 불러오기")
            }
        }
        if (showImportDialog) {
            GroupImportDialog(repository = repository, onDismiss = { showImportDialog = false })
        }
        Spacer(Modifier.height(Spacing.sm))
        Button(onClick = onAddClick, modifier = Modifier.fillMaxWidth()) {
            Text("차단 규칙 추가")
        }
        // 안내가 아니라 "지금 이 동작이 막혔다"는 경고이므로 본문과 같은 톤이 아니라 경고 색 카드로 —
        // 예전엔 기본 색 평문이라 목록 위쪽에 조용히 얹혀 못 보고 지나치기 쉬웠다.
        penaltyMessage?.let {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(Spacing.sm)
                )
            }
        }
        if (groups.isEmpty()) {
            Text("아직 차단 규칙이 없습니다.", modifier = Modifier.padding(top = Spacing.md))
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
                }
            }
        }
    }

    // 로컬 전용 규칙의 동기화를 켜려는데 같은 이름이 이미 불러오기 목록에 있을 때(94차, 95차에 이 화면
    // 으로 이동) — "예"면 이 규칙의 설정을 그 원격 내용으로 바로 덮어쓰고 동기화를 켠다, "아니오"면 취소.
    val collisionGroup = pendingSyncToggleGroup
    val collisionEntry = pendingSyncToggleEntry
    if (collisionGroup != null && collisionEntry != null) {
        AlertDialog(
            onDismissRequest = { pendingSyncToggleGroup = null; pendingSyncToggleEntry = null },
            title = { Text("동기화") },
            text = {
                Text("이미 같은 이름의 차단 규칙이 불러오기 목록에 있습니다. 이 규칙과 동기화하시겠습니까? " +
                    "\"예\"를 선택하면 이 차단 규칙의 설정이 불러온 내용으로 바뀝니다.")
            },
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

// 상태 배지 색 — DECISIONS.md 85차의 상태 3색(성공/경고/실패)을 그대로 재사용한다.
private val STATUS_OK = Color(0xFF34D399)
private val STATUS_WARNING = Color(0xFFFBBF24)
private val STATUS_DANGER = Color(0xFFF87171)

/** 그룹의 현재 상태를 한눈에 알리는 작은 알약 배지(DECISIONS.md 85차 "섹션 헤더 알약" 패턴 재사용). */
@Composable
private fun GroupStatusBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("불러오기") },
        text = {
            Column {
                Text(
                    "다른 기기에서 동기화를 켠 차단 규칙 중, 이 기기엔 아직 없는 것들입니다. 원하는 것만 골라 불러오세요.",
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

    // 선택한 그룹은 오른쪽에서 편집 중임을 알 수 있도록 accent 테두리로 강조(마스터-디테일 레이아웃).
    Surface(
        // 꺼진 차단 규칙은 줄 전체를 흐리게 해서 한눈에 "꺼져 있다"가 보이게 한다(95차, 사용자 요청).
        // 고정 회색을 새로 칠하는 대신 Modifier.alpha로 카드 전체(배경+글자+아이콘)를 낮은 불투명도로
        // 내려서 지금 테마(라이트/다크/커스텀 무엇이든) 배경이 그대로 비쳐 보이게 한다.
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)
            .alpha(if (group.groupEnabled) 1f else 0.55f),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        onClick = onClick
    ) {
        Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            // 잠깐 풀기 버튼(글자가 길어질 수 있는 OutlinedButton)을 동기화 칩과 한 Row에 나란히
            // 두면 좁은 화면에서 UI가 깨진다는 지적(95차, 안드로이드판에서 발견돼 대칭 적용) — 오른쪽을
            // 세로로 두 줄로 쌓는 구조로 바꾼다. 위 줄엔 동기화 칩 + 켜짐/꺼짐 Switch를 이름 줄과 나란히
            // 붙이고, 그 아래(이름+정보 두 줄이 이 위 줄 한 줄보다 길어서 생기는 빈 공간)에 잠깐 풀기를
            // 놓는다. 잠깐 풀기도 세로 크기(높이)를 동기화 칩과 똑같이 맞추기 위해 OutlinedButton 대신
            // 같은 알약(배경+패딩) 스타일로 통일한다.
            // 왼쪽 Row를 Top 정렬로 두면 잠깐 풀기가 없을 때 이름+정보(2줄)가 오른쪽 스택(1줄)보다
            // 길어져 왼쪽 블록이 위로 쏠려 보인다는 지적(95차) — CenterVertically로 두면 두 블록 중
            // 더 짧은 쪽이 항상 전체 줄 높이(칸이 늘어나 잠깐 풀기가 추가되면 그만큼 커진 높이 포함)
            // 가운데에 자동으로 맞춰진다.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium)
                        // 이 화면에서 사용자가 가장 먼저 알고 싶은 건 "지금 이 그룹이 실제로 걸려 있는가"인데,
                        // 예전엔 그 상태가 가장 작고(labelSmall) 가장 흐린(onSurfaceVariant) 텍스트라 그룹
                        // 이름에 완전히 묻혔다 — 상태별 색 배지로 올려 시각적 우선순위를 바로잡는다.
                        GroupStatusBadge(
                            text = when {
                                pending -> "(%d/%d) 확인 필요".format(group.groupOffMessageIndex + 1, PERSUASION_MESSAGES.size)
                                snoozeActive -> "😴 잠깐 풀기 중"
                                !group.groupEnabled -> "차단 규칙 꺼짐 · 아무 차단도 적용 안 됨"
                                restrictingNow -> "🔒 오늘 차단 중"
                                else -> "오늘은 해당 없음"
                            },
                            color = when {
                                pending -> STATUS_WARNING
                                snoozeActive -> STATUS_WARNING
                                !group.groupEnabled -> STATUS_DANGER
                                restrictingNow -> STATUS_OK
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
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
                            Text("실행 전 대기", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.width(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 동기화 칩과 잠깐 풀기 칩을 스위치와 별개인 이 안쪽 Column에 함께 넣고
                    // width(IntrinsicSize.Max)로 폭을 서로 맞춰서(둘 중 더 넓은 텍스트 기준) 두 칩의
                    // 왼쪽·오른쪽 끝이 세로로 정확히 줄맞춤되게 한다(95차, 사용자 지적 — 전체 줄
                    // 가로폭에 맞춰 늘어나는 건 원하는 게 아니라 "동기화 칩과 세로줄이 맞아야 한다"는 뜻이었음).
                    Column(
                        modifier = Modifier.width(IntrinsicSize.Max),
                        horizontalAlignment = Alignment.End
                    ) {
                        // 칩 모양은 공부앱 캘린더의 "N회독" 토글(색 배경 알약+굵은 글씨, CalendarScreen.kt
                        // 참고)과 같은 스타일이되, 이모지는 "🔁"(N회독)과 헷갈리지 않는 "☁️"(클라우드 동기화)를 쓴다.
                        Text(
                            if (group.syncEnabled) "☁️동기화 ON" else "☁️동기화 OFF",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = if (group.syncEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    (if (group.syncEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.15f),
                                    RoundedCornerShape(50)
                                )
                                .clickable { onSyncToggle(!group.syncEnabled) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        if (!pending && group.groupEnabled && group.snoozeEnabled && (restrictingNow || snoozeActive)) {
                            Spacer(Modifier.height(Spacing.xs))
                            val snoozeClickable = !snoozeActive && snoozeRemainingToday > 0
                            Text(
                                if (snoozeActive) "😴 잠깐 풀기 중" else "😴 잠깐 풀기 ${group.snoozeMinutes}분 ($snoozeRemainingToday/${group.snoozeDailyLimit})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                color = STATUS_WARNING,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(STATUS_WARNING.copy(alpha = 0.15f), RoundedCornerShape(50))
                                    .let { if (snoozeClickable) it.clickable(onClick = onSnooze) else it }
                                    .alpha(if (snoozeClickable) 1f else 0.6f)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Switch(checked = group.groupEnabled, onCheckedChange = onGroupToggle)
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
