package com.phonelock.desktop.ui

import com.phonelock.desktop.ui.components.LedgerAlertDialog
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab as MaterialTab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.monitor.AuthManager
import com.phonelock.desktop.monitor.SocialGroupSyncClient
import com.phonelock.desktop.monitor.TtsPlayer
import com.phonelock.desktop.monitor.VoicePlayer
import com.phonelock.desktop.ui.components.GroupShareSettingsDialog
import com.phonelock.desktop.ui.components.GroupWalkieSettingsDialog
import com.phonelock.desktop.ui.components.TextMessageDialog
import com.phonelock.desktop.ui.components.VoiceRecordDialog
import com.phonelock.desktop.ui.components.WakeOptionsDialog
import com.phonelock.desktop.ui.theme.Spacing
import java.util.Base64

/** 멤버 이름 첫 글자를 원형 배지로 — 목록 판독성 개선(GroupAvatar와 같은 패턴, 파일 분리). 82차(§6 UX
 *  폴리싱, 안드로이드판과 대칭): 선택 안 된 상태에선 이름 해시로 테마의 3가지 container 색상 중 하나를
 *  고정 배정해 전원이 같은 색으로 밋밋해 보이던 걸 개선 — 선택된(highlighted) 상태는 기존처럼 primary로
 *  그대로 둬 "지금 보고 있는 멤버"라는 신호가 색 변주에 묻히지 않게 한다. */
@Composable
private fun MemberAvatar(name: String, highlighted: Boolean, profileImage: String? = null) {
    val trimmed = name.trim()
    val (bg, fg) = if (highlighted) {
        MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    } else {
        val idx = (trimmed.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % 3
        when (idx) {
            0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            1 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        }
    }
    val emoji = com.phonelock.desktop.ui.components.AvatarCatalog.emojiFor(profileImage)
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        if (emoji != null) {
            Text(emoji, style = MaterialTheme.typography.labelLarge)
        } else {
            Text(trimmed.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}

/** shareRoutines가 켜져 있고 오늘 예정 루틴이 있을 때만 완료율을 계산, 그 외엔 null(정렬 시 맨 뒤로). */
private fun completionRatio(m: SocialGroupSyncClient.MemberStats): Double? =
    if (m.shareRoutines && m.routines.isNotEmpty()) m.routines.count { it.doneToday }.toDouble() / m.routines.size else null

/** 82차(§9 "모임 주간 리더보드") — schedule에 이미 담겨오는 ±버퍼 캘린더 데이터로 최근 7일 완료율을 재집계. */
private fun weekCompletionRatio(m: SocialGroupSyncClient.MemberStats): Double? {
    if (!m.shareSchedule) return null
    // 143차: "최근 7일"의 끝은 그 사람의 하루 시작 기준 오늘(141차) — 옛 버전 데이터면 달력 날짜.
    val today = m.studyDayKey?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: java.time.LocalDate.now()
    val weekAgoKey = today.minusDays(6).toString()
    val todayKey = today.toString()
    val weekTasks = m.schedule.filter { it.dateKey in weekAgoKey..todayKey }
    if (weekTasks.isEmpty()) return null
    return weekTasks.count { it.status == "O" }.toDouble() / weekTasks.size
}

/**
 * 모임 하나 진입 시 멤버 목록(마스터-디테일: 왼쪽 멤버 목록, 오른쪽 선택한 멤버의 상세) — 오늘 완료율
 * 낮은 순으로 정렬해 누가 처지고 있는지 한눈에 보이게 하고, "😴 깨우기" 버튼과 초대 코드 공유,
 * 나가기/삭제(모임장만)를 제공한다.
 */
@Composable
fun SocialGroupMembersScreen(
    repository: Repository,
    groupId: String,
    onOpenDm: (String, String, String) -> Unit,
    onBack: () -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<SocialGroupSyncClient.GroupInfo?>(null) }
    var stats by remember { mutableStateOf<List<SocialGroupSyncClient.MemberStats>>(emptyList()) }
    var selectedUid by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var nudgeSentUid by remember { mutableStateOf<String?>(null) }
    var voiceInbox by remember { mutableStateOf<List<SocialGroupSyncClient.VoiceMessageInfo>>(emptyList()) }
    var playingMsgId by remember { mutableStateOf<String?>(null) }
    var voiceSendError by remember { mutableStateOf<String?>(null) }
    var voiceDeleteError by remember { mutableStateOf<String?>(null) }
    var walkieSettings by remember { mutableStateOf(SocialGroupSyncClient.GroupWalkieSettings()) }
    var showWalkieSettingsDialog by remember { mutableStateOf(false) }
    var showShareSettingsDialog by remember { mutableStateOf(false) }
    var showRandomNudgeDialog by remember { mutableStateOf(false) }
    var randomNudgeEnabled by remember(groupId) { mutableStateOf(repository.randomNudgeEnabledFor(groupId)) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showEditInfoDialog by remember { mutableStateOf(false) }
    var showMemberManageDialog by remember { mutableStateOf(false) }
    // 92차 소셜 개편 Phase 4(관리 기능 강화, IDEAS.md 77차, 안드로이드판과 대칭) — 소유권 승계 확인 대상.
    var transferTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // uid to 표시이름
    var admins by remember { mutableStateOf(emptySet<String>()) }
    var viewWeekly by remember { mutableStateOf(false) }
    var announcement by remember { mutableStateOf<SocialGroupSyncClient.Announcement?>(null) }
    var showAnnouncementDialog by remember { mutableStateOf(false) }
    var announcementInput by remember { mutableStateOf("") }
    var groupGoal by remember { mutableStateOf<SocialGroupSyncClient.GroupGoal?>(null) }
    var showGoalDialog by remember { mutableStateOf(false) }
    var goalInput by remember { mutableStateOf("") }
    var quoteStats by remember { mutableStateOf<List<SocialGroupSyncClient.QuoteStat>>(emptyList()) }
    var shareSettings by remember { mutableStateOf(repository.groupShareSettings(groupId)) }
    // 😴 깨우기 대상 — wakeTarget이 있는 동안 wakeStep("options"/"voice"/"text")에 따라 다이얼로그가 뜬다.
    var wakeTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // uid to 표시이름
    var wakeStep by remember { mutableStateOf<String?>(null) }
    // 92차 소셜 개편 Phase 1: "멤버"/"💬 대화" 채널 전환(안드로이드판 SocialGroupMembersScreen.kt와 대칭).
    var channelTab by remember { mutableStateOf(0) }
    fun cancelWakeFlow() { wakeTarget = null; wakeStep = null }

    val myUid = AuthManager.currentUid
    val url = repository.fbDatabaseUrl
    val key = repository.fbApiKey

    fun refresh() { refreshTrigger++ }

    LaunchedEffect(groupId, refreshTrigger) {
        if (url.isNullOrBlank() || key.isNullOrBlank()) {
            loading = false
            errorMsg = "Firebase 설정이 필요합니다."
            return@LaunchedEffect
        }
        loading = true
        errorMsg = null
        // 내 오늘 통계를 먼저 올려서(공유 토글 반영), 방금 바뀐 값도 이 화면에 바로 반영되게 한다.
        SocialGroupSyncClient.pushMyStats(url, key, groupId, repository)
        info = SocialGroupSyncClient.readGroupInfo(url, key, groupId)
        stats = SocialGroupSyncClient.readGroupStats(url, key, groupId)
        admins = SocialGroupSyncClient.readGroupAdmins(url, key, groupId)
        voiceInbox = if (myUid != null) {
            SocialGroupSyncClient.readIncomingVoiceMessages(url, key, listOf(groupId), myUid)
        } else emptyList()
        walkieSettings = SocialGroupSyncClient.readGroupWalkieSettings(url, key, groupId)
        announcement = SocialGroupSyncClient.readAnnouncement(url, key, groupId)
        groupGoal = SocialGroupSyncClient.readGoal(url, key, groupId)
        // 82차(§11 "모임 랭킹") — 내 회유 멘트 저항률을 먼저 올리고 전체를 읽어온다.
        val myOutcomes = repository.getAllQuoteOutcomesOnce()
        if (myOutcomes.isNotEmpty()) {
            val myStopRate = Math.round(myOutcomes.count { it.choice == "STOP" } * 100.0 / myOutcomes.size).toInt()
            SocialGroupSyncClient.writeMyQuoteStat(url, key, groupId, myStopRate, myOutcomes.size)
        }
        quoteStats = SocialGroupSyncClient.readQuoteStats(url, key, groupId)
        loading = false
        if (selectedUid == null && myUid != null) selectedUid = myUid
    }

    if (showLeaveConfirm) {
        LedgerAlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("모임 나가기") },
            text = { Text("\"${info?.name ?: ""}\" 모임에서 나갑니다. 계속할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    Thread { SocialGroupSyncClient.leaveGroup(url, key, groupId); onBack() }.start()
                }) { Text("나가기") }
            },
            dismissButton = { TextButton(onClick = { showLeaveConfirm = false }) { Text("취소") } }
        )
    }

    if (showDeleteConfirm) {
        LedgerAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("모임 삭제") },
            text = { Text("\"${info?.name ?: ""}\" 모임을 지웁니다. 멤버 모두 빠지고 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    Thread { SocialGroupSyncClient.deleteGroup(url, key, groupId); onBack() }.start()
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("취소") } }
        )
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("불러오는 중...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    errorMsg?.let {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    if (showAnnouncementDialog) {
        LedgerAlertDialog(
            onDismissRequest = { showAnnouncementDialog = false },
            title = { Text("공지 수정") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = announcementInput,
                    onValueChange = { announcementInput = it },
                    placeholder = { Text("모임원에게 전할 공지를 입력하세요") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAnnouncementDialog = false
                    Thread {
                        SocialGroupSyncClient.writeAnnouncement(url, key, groupId, announcementInput.trim())
                        announcement = SocialGroupSyncClient.readAnnouncement(url, key, groupId)
                    }.start()
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { showAnnouncementDialog = false }) { Text("취소") } }
        )
    }

    if (showGoalDialog) {
        LedgerAlertDialog(
            onDismissRequest = { showGoalDialog = false },
            title = { Text("모임 목표 설정") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = goalInput,
                    onValueChange = { v -> goalInput = v.filter { it.isDigit() } },
                    label = { Text("목표 시간(분)") },
                    placeholder = { Text("예: 120") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = goalInput.toIntOrNull() ?: 0
                    showGoalDialog = false
                    if (minutes > 0) {
                        Thread {
                            SocialGroupSyncClient.writeGoal(url, key, groupId, minutes)
                            groupGoal = SocialGroupSyncClient.readGoal(url, key, groupId)
                        }.start()
                    }
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { showGoalDialog = false }) { Text("취소") } }
        )
    }

    val sortedStats = if (viewWeekly) stats.sortedBy { weekCompletionRatio(it) ?: 1.0 } else stats.sortedBy { completionRatio(it) ?: 1.0 }
    val notDoneCount = stats.count { (completionRatio(it) ?: 1.0) <= 0.0 }
    val isOwner = info?.ownerUid == myUid
    val isAdmin = isOwner || (myUid != null && myUid in admins)
    val clipboard = LocalClipboardManager.current
    val groupGoalTodaySeconds = stats.filter { it.shareStudy }.sumOf { it.studyTodaySeconds }

    Row(Modifier.fillMaxSize().background(socialGradientBackground())) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // 144차 리디자인(안드로이드판과 같은 언어): 화살표 뒤로 + 아이콘 동작, 아래에 큰 모임 이름.
                com.phonelock.desktop.ui.components.LedgerBackButton(onBack)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 98차(사용자 요청, 안드로이드판은 당겨서 새로고침) — 데스크탑은 스와이프 제스처가 없어 버튼으로.
                    androidx.compose.material3.IconButton(onClick = { refresh() }) {
                        androidx.compose.material3.Icon(Icons.Outlined.Refresh, contentDescription = "새로고침", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        androidx.compose.material3.IconButton(onClick = { showSettingsMenu = true }) {
                            androidx.compose.material3.Icon(Icons.Outlined.Settings, contentDescription = "모임 설정", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // 82차(§6 UX 폴리싱, 안드로이드판과 대칭): 밋밋한 AlertDialog 버튼 목록 대신
                        // 버튼 바로 아래에서 펼쳐지는 앵커된 드롭다운 메뉴로.
                        androidx.compose.material3.DropdownMenu(
                            expanded = showSettingsMenu,
                            onDismissRequest = { showSettingsMenu = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("공유 설정") },
                                onClick = { showSettingsMenu = false; showShareSettingsDialog = true }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("깨우기 메시지") },
                                onClick = { showSettingsMenu = false; showWalkieSettingsDialog = true }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("무작위 알림") },
                                onClick = { showSettingsMenu = false; showRandomNudgeDialog = true }
                            )
                            if (isAdmin) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("모임 이름·코드 수정") },
                                    onClick = { showSettingsMenu = false; showEditInfoDialog = true }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("멤버 관리") },
                                    onClick = { showSettingsMenu = false; showMemberManageDialog = true }
                                )
                                // 115차(사용자 요청): 모임 "💬 대화" 채널 자체를 켜고 끌 수 있게(안드로이드판과 대칭).
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(if (info?.chatEnabled != false) "모임 대화 끄기" else "모임 대화 켜기") },
                                    onClick = {
                                        showSettingsMenu = false
                                        val next = !(info?.chatEnabled ?: true)
                                        val prev = info
                                        info = info?.copy(chatEnabled = next)
                                        if (!next) channelTab = 0
                                        Thread {
                                            val result = SocialGroupSyncClient.setGroupChatEnabled(url, key, groupId, next)
                                            result.onFailure { info = prev }
                                        }.start()
                                    }
                                )
                            }
                        }
                    }
                    if (isOwner) {
                        TextButton(onClick = { showDeleteConfirm = true }) { Text("모임 삭제", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false) }
                    } else {
                        TextButton(onClick = { showLeaveConfirm = true }) { Text("나가기", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false) }
                    }
                }
            }
            com.phonelock.desktop.ui.components.Overline("모임")
            Text(info?.name ?: "", style = MaterialTheme.typography.headlineMedium, maxLines = 2)
            if (!info?.description.isNullOrBlank()) {
                Text(
                    info?.description ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(Spacing.sm))

            val chatEnabled = info?.chatEnabled != false
            if (chatEnabled) {
                com.phonelock.desktop.ui.components.SectionTabs(listOf("멤버", "대화"), channelTab, { channelTab = it })
                Spacer(Modifier.height(Spacing.sm))
            }

            if (chatEnabled && channelTab == 1) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    GroupChatScreen(repository, groupId)
                }
            } else {

            // 146차(안드로이드판과 대칭): 색 바탕 상자들 → 하나의 스크롤 안에 큰 숫자 + 가는 선 묶음. 멤버 줄이 주인공이고
            // 초대 코드·순위는 아래로 내렸다.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                // 이 화면의 주인공 숫자 — 오늘 아직 안 한 사람 수(처지는 사람을 먼저 챙기는 모임의 목적).
                Column(Modifier.fillMaxWidth().padding(top = Spacing.sm, bottom = Spacing.md)) {
                    com.phonelock.desktop.ui.components.Overline("오늘 아직 안 한 사람")
                    com.phonelock.desktop.ui.components.BigNumber(
                        "$notDoneCount",
                        unit = "/ ${stats.size}명",
                        style = MaterialTheme.typography.displayMedium,
                        color = if (notDoneCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                    )
                    // 82차(§9 "모임 공동 목표") — 관리자가 목표(분)를 정하면 오늘 공유된 멤버들의 집중 시간 합으로 진행선을 그린다.
                    if (groupGoal != null || isAdmin) {
                        Spacer(Modifier.height(Spacing.md))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                groupGoal?.let { "모임 목표 · 오늘 함께 ${groupGoalTodaySeconds / 60}분 / ${it.targetMinutes}분" } ?: "모임 목표가 없습니다",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (isAdmin) {
                                TextButton(onClick = { goalInput = groupGoal?.targetMinutes?.toString() ?: ""; showGoalDialog = true }) {
                                    Text("설정", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        groupGoal?.let { goal ->
                            val targetSeconds = goal.targetMinutes * 60
                            com.phonelock.desktop.ui.components.ProgressLine(
                                if (targetSeconds > 0) groupGoalTodaySeconds.toFloat() / targetSeconds else 0f,
                                color = com.phonelock.desktop.ui.theme.LocalPhoneLockPalette.current.fillGood
                            )
                        }
                    }
                }

                if (voiceInbox.isNotEmpty()) {
                    com.phonelock.desktop.ui.components.LedgerSection("받은 깨우기 메시지 ${voiceInbox.size}") {
                        voiceInbox.forEach { msg ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Icon(
                                    if (msg.textMessage.isNotBlank()) Icons.AutoMirrored.Outlined.Chat else Icons.Outlined.Mic,
                                    contentDescription = if (msg.textMessage.isNotBlank()) "텍스트 메시지" else "음성 메시지",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(Spacing.sm))
                                Column(Modifier.weight(1f)) {
                                    Text(msg.fromName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (msg.textMessage.isNotBlank()) msg.textMessage else "${msg.durationMs / 1000}초",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                androidx.compose.material3.IconButton(
                                    enabled = playingMsgId != msg.msgId,
                                    onClick = {
                                        playingMsgId = msg.msgId
                                        if (msg.textMessage.isNotBlank()) {
                                            TtsPlayer.speak(msg.textMessage, walkieSettings.volume, walkieSettings.voiceGender)
                                            playingMsgId = null
                                        } else {
                                            val wavBytes = runCatching { Base64.getDecoder().decode(msg.audioBase64) }.getOrNull()
                                            if (wavBytes != null) {
                                                VoicePlayer.play(wavBytes, walkieSettings.volume) { playingMsgId = null }
                                            } else {
                                                playingMsgId = null
                                            }
                                        }
                                        // 들은 즉시 지우지 않고 "들었음"만 표시 — 다시 듣고 싶을 수 있어서
                                        // 유예시간(24시간) 동안은 남겨두고, 지나면 다음 조회 때 자동으로 지워진다.
                                        Thread {
                                            SocialGroupSyncClient.markVoiceMessageListened(url, key, msg.groupId, msg)
                                        }.start()
                                    }
                                ) {
                                    androidx.compose.material3.Icon(Icons.Filled.PlayArrow, contentDescription = if (playingMsgId == msg.msgId) "재생 중" else "재생", tint = MaterialTheme.colorScheme.primary)
                                }
                                androidx.compose.material3.IconButton(onClick = {
                                    voiceDeleteError = null
                                    Thread {
                                        // 서버 삭제가 실제로 성공했을 때만 목록에서 뺀다 — 무조건 빼면 서버에서 실패해도(권한 등)
                                        // 지워진 것처럼 보이다가 다음 조회 때 다시 나타난다.
                                        val result = SocialGroupSyncClient.deleteVoiceMessage(url, key, msg.groupId, msg.msgId)
                                        if (result.isSuccess) {
                                            voiceInbox = voiceInbox.filter { it.msgId != msg.msgId }
                                        } else {
                                            voiceDeleteError = result.exceptionOrNull()?.message
                                        }
                                    }.start()
                                }) {
                                    androidx.compose.material3.Icon(Icons.Outlined.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        voiceDeleteError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(Spacing.sm))
                    }
                }

                // 82차(§9 "모임장 공지사항") — 있으면 항상 상단에, 관리자만 편집 가능.
                if (announcement != null || isAdmin) {
                    com.phonelock.desktop.ui.components.LedgerSection(
                        "공지",
                        trailing = if (isAdmin) ({
                            TextButton(onClick = { announcementInput = announcement?.text ?: ""; showAnnouncementDialog = true }) {
                                Text("수정", maxLines = 1, softWrap = false)
                            }
                        }) else null
                    ) {
                        Text(
                            announcement?.text ?: "아직 공지가 없습니다.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (announcement != null) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.md))
                    }
                }

                // 82차(§9 "모임 주간 리더보드") — 오늘/이번 주.
                com.phonelock.desktop.ui.components.LedgerSection(
                    "멤버 ${stats.size}명",
                    trailing = {
                        com.phonelock.desktop.ui.components.SegmentedTabs(
                            listOf("오늘", "이번 주"),
                            if (viewWeekly) 1 else 0,
                            { viewWeekly = it == 1 },
                            Modifier.width(168.dp)
                        )
                    }
                ) {}
                sortedStats.forEach { m ->
                    val ratio = if (viewWeekly) weekCompletionRatio(m) else completionRatio(m)
                    val isSelfRow = m.uid == myUid
                    val isSelected = m.uid == selectedUid
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { selectedUid = m.uid }
                            .padding(horizontal = Spacing.xs, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MemberAvatar(m.displayName, highlighted = isSelected, profileImage = m.profileImage)
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            MemberDisplayName(
                                title = m.plantTitle.takeIf { m.sharePlant },
                                name = m.displayName + if (isSelfRow) " (나)" else "",
                                level = m.plantLevel.takeIf { m.sharePlant },
                                style = MaterialTheme.typography.bodyLarge
                            )
                            val meta = buildList {
                                if (m.shareStreak && m.streak > 0) add("연속 ${m.streak}일")
                                if (ratio == null && !m.shareRoutines) add("비공개")
                            }
                            if (meta.isNotEmpty()) {
                                Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (ratio != null) {
                                Spacer(Modifier.height(6.dp))
                                com.phonelock.desktop.ui.components.ProgressLine(
                                    ratio.toFloat(),
                                    color = if (ratio >= 1.0) com.phonelock.desktop.ui.theme.LocalPhoneLockPalette.current.fillGood else MaterialTheme.colorScheme.primary,
                                    thickness = 3.dp
                                )
                            }
                        }
                        if (ratio != null) {
                            Spacer(Modifier.width(Spacing.sm))
                            Text("${Math.round(ratio * 100)}%", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                        }
                        if (!isSelfRow) {
                            androidx.compose.material3.IconButton(onClick = {
                                val dmUrl = repository.fbDatabaseUrl
                                val dmKey = repository.fbApiKey
                                Thread {
                                    com.phonelock.desktop.monitor.ChatSyncClient.ensureDmChat(dmUrl, dmKey, m.uid, m.displayName).onSuccess { chatId ->
                                        onOpenDm(chatId, m.uid, m.displayName)
                                    }
                                }.start()
                            }) {
                                androidx.compose.material3.Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = "${m.displayName}에게 DM 보내기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            androidx.compose.material3.IconButton(onClick = {
                                wakeTarget = m.uid to m.displayName
                                wakeStep = "options"
                            }) {
                                androidx.compose.material3.Icon(
                                    if (nudgeSentUid == m.uid) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationsActive,
                                    contentDescription = "${m.displayName} 깨우기",
                                    tint = if (nudgeSentUid == m.uid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    com.phonelock.desktop.ui.components.Hairline()
                }

                // 82차(§11 "모임 랭킹") — 확인 질문 저항률 비교(재미 요소).
                if (quoteStats.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.lg))
                    com.phonelock.desktop.ui.components.LedgerSection("확인 질문 저항률 순위", divider = false) {
                        quoteStats.sortedByDescending { it.stopRatePercent }.forEachIndexed { idx, qs ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${idx + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                                Text(qs.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                                Text("${qs.stopRatePercent}% · ${qs.totalCount}회", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.md))
                com.phonelock.desktop.ui.components.LedgerSection("초대 코드") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(info?.inviteCode ?: "", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            info?.inviteCode?.let { clipboard.setText(AnnotatedString(it)) }
                        }) { Text("복사", maxLines = 1, softWrap = false) }
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            val selected = sortedStats.find { it.uid == selectedUid }
            if (selected != null) {
                SocialGroupMemberDetailScreen(
                    repository = repository,
                    groupId = groupId,
                    member = selected,
                    isSelf = selected.uid == myUid,
                    onOpenDm = onOpenDm,
                    onShareSettingsChanged = { refresh() },
                    onNudge = {
                        Thread {
                            SocialGroupSyncClient.sendNudge(url, key, groupId, selected.uid)
                            nudgeSentUid = selected.uid
                        }.start()
                    },
                    onSendVoice = { wavBytes, durationMs ->
                        voiceSendError = null
                        Thread {
                            val audioBase64 = Base64.getEncoder().encodeToString(wavBytes)
                            val result = SocialGroupSyncClient.sendVoiceMessage(url, key, groupId, selected.uid, audioBase64, durationMs)
                            // 이전엔 결과를 완전히 버려서 실패해도 화면에 아무 표시가 없었다 — 실패 원인(상태코드/응답
                            // 본문)이 예외 메시지에 담겨 오므로 그대로 보여준다.
                            voiceSendError = result.exceptionOrNull()?.message
                        }.start()
                    },
                    onSendText = { text ->
                        voiceSendError = null
                        Thread {
                            val result = SocialGroupSyncClient.sendTextMessage(url, key, groupId, selected.uid, text)
                            voiceSendError = result.exceptionOrNull()?.message
                        }.start()
                    }
                )
                voiceSendError?.let {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("멤버를 고르면 여기 보입니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showEditInfoDialog) {
        var nameText by remember { mutableStateOf(info?.name ?: "") }
        var descriptionText by remember { mutableStateOf(info?.description ?: "") }
        var regenMessage by remember { mutableStateOf<String?>(null) }
        LedgerAlertDialog(
            onDismissRequest = { showEditInfoDialog = false },
            title = { Text("모임 이름·코드 수정") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        label = { Text("모임 이름") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    androidx.compose.material3.OutlinedTextField(
                        value = descriptionText,
                        onValueChange = { descriptionText = it },
                        label = { Text("설명(선택)") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text("초대 코드", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(info?.inviteCode ?: "", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(Spacing.xs))
                    OutlinedButton(onClick = {
                        Thread {
                            val result = SocialGroupSyncClient.regenerateInviteCode(url, key, groupId)
                            regenMessage = if (result.isSuccess) "새 코드로 바뀌었습니다." else (result.exceptionOrNull()?.message ?: "재발급에 실패했습니다.")
                            refresh()
                        }.start()
                    }) { Text("코드 재발급", maxLines = 1, softWrap = false) }
                    regenMessage?.let {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showEditInfoDialog = false
                    Thread { SocialGroupSyncClient.updateGroupName(url, key, groupId, nameText, descriptionText); refresh() }.start()
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { showEditInfoDialog = false }) { Text("취소") } }
        )
    }

    if (showMemberManageDialog) {
        LedgerAlertDialog(
            onDismissRequest = { showMemberManageDialog = false },
            title = { Text("멤버 관리") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    stats.filter { it.uid != myUid }.forEach { m ->
                        val targetIsOwner = m.uid == info?.ownerUid
                        val targetIsAdmin = m.uid in admins
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    m.displayName + when { targetIsOwner -> " (모임장)"; targetIsAdmin -> " (관리자)"; else -> "" },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            if (isOwner && !targetIsOwner) {
                                TextButton(onClick = {
                                    Thread { SocialGroupSyncClient.setGroupAdmin(url, key, groupId, m.uid, !targetIsAdmin); refresh() }.start()
                                }) { Text(if (targetIsAdmin) "관리자 해제" else "관리자 지정") }
                                TextButton(onClick = { transferTarget = m.uid to m.displayName }) { Text("모임장 넘기기", maxLines = 1, softWrap = false) }
                            }
                            if (!targetIsOwner) {
                                TextButton(onClick = {
                                    Thread { SocialGroupSyncClient.kickMember(url, key, groupId, m.uid); refresh() }.start()
                                }) { Text("내쫓기") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMemberManageDialog = false } ) { Text("닫기") } }
        )
    }

    transferTarget?.let { (targetUid, targetName) ->
        var transferError by remember { mutableStateOf<String?>(null) }
        LedgerAlertDialog(
            onDismissRequest = { transferTarget = null },
            title = { Text("모임장 넘기기") },
            text = {
                Column {
                    Text("\"$targetName\"님에게 모임장을 넘길까요? 나는 자동으로 관리자가 됩니다.")
                    transferError?.let {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Thread {
                        val result = SocialGroupSyncClient.transferOwnership(url, key, groupId, targetUid)
                        result.onSuccess { transferTarget = null; showMemberManageDialog = false; refresh() }
                        result.onFailure { e -> transferError = e.message ?: "소유권 승계에 실패했습니다." }
                    }.start()
                }) { Text("넘기기") }
            },
            dismissButton = { TextButton(onClick = { transferTarget = null }) { Text("취소") } }
        )
    }

    if (showShareSettingsDialog) {
        GroupShareSettingsDialog(
            initial = shareSettings,
            onDismiss = { showShareSettingsDialog = false },
            onSave = { settings ->
                showShareSettingsDialog = false
                shareSettings = settings
                repository.setGroupShareSettings(groupId, settings)
                // 98차 버그 수정(안드로이드판과 대칭): 통계만 다시 올리고 화면의 멤버별 공유 통계는 안
                // 새로고침돼서, 공유 설정을 꺼도 화면 나갔다 와야 반영되던 버그 — push가 끝난 뒤 refresh().
                Thread { SocialGroupSyncClient.pushMyStats(url, key, groupId, repository); refresh() }.start()
            }
        )
    }

    if (showWalkieSettingsDialog) {
        GroupWalkieSettingsDialog(
            initial = walkieSettings,
            onDismiss = { showWalkieSettingsDialog = false },
            onSave = { settings ->
                showWalkieSettingsDialog = false
                walkieSettings = settings
                Thread { SocialGroupSyncClient.writeGroupWalkieSettings(url, key, groupId, settings) }.start()
            }
        )
    }

    if (showRandomNudgeDialog) {
        LedgerAlertDialog(
            onDismissRequest = { showRandomNudgeDialog = false },
            title = { Text("무작위 알림") },
            text = {
                Column {
                    Text(
                        "하루 한 번 무작위 시각에, 아직 못 한 멤버에게 자동으로 깨우기를 보냅니다.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("이 모임에서 켜기", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = randomNudgeEnabled,
                            onCheckedChange = { checked ->
                                randomNudgeEnabled = checked
                                repository.setRandomNudgeEnabled(groupId, checked)
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRandomNudgeDialog = false }) { Text("닫기") } }
        )
    }

    if (wakeStep == "options") {
        wakeTarget?.let { (targetUid, targetName) ->
            WakeOptionsDialog(
                targetName = targetName,
                onDismiss = { cancelWakeFlow() },
                onNudge = {
                    Thread {
                        SocialGroupSyncClient.sendNudge(url, key, groupId, targetUid)
                        nudgeSentUid = targetUid
                    }.start()
                    cancelWakeFlow()
                },
                onOpenVoiceRecorder = { wakeStep = "voice" },
                onOpenTextMessage = { wakeStep = "text" }
            )
        }
    }

    if (wakeStep == "voice") {
        val targetUid = wakeTarget?.first
        VoiceRecordDialog(
            onDismiss = { cancelWakeFlow() },
            onSend = { wavBytes, durationMs ->
                if (targetUid != null) {
                    Thread {
                        val audioBase64 = Base64.getEncoder().encodeToString(wavBytes)
                        SocialGroupSyncClient.sendVoiceMessage(url, key, groupId, targetUid, audioBase64, durationMs)
                    }.start()
                }
                cancelWakeFlow()
            }
        )
    }

    if (wakeStep == "text") {
        val targetUid = wakeTarget?.first
        TextMessageDialog(
            onDismiss = { cancelWakeFlow() },
            onSend = { text ->
                if (targetUid != null) {
                    Thread { SocialGroupSyncClient.sendTextMessage(url, key, groupId, targetUid, text) }.start()
                }
                cancelWakeFlow()
            }
        )
    }
}
