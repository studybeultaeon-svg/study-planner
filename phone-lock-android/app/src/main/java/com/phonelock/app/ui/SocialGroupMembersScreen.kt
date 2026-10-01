package com.phonelock.app.ui

import com.phonelock.app.ui.components.LedgerAlertDialog
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import android.content.Intent
import android.util.Base64
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ButtonDefaults
import com.phonelock.app.ui.theme.LocalPhoneLockPalette
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab as MaterialTab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.*
import com.phonelock.app.service.AuthManager
import com.phonelock.app.service.SocialGroupSyncClient
import com.phonelock.app.service.TtsPlayer
import com.phonelock.app.service.VoicePlayer
import com.phonelock.app.service.VoiceRecorder
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.ui.components.GroupShareSettingsDialog
import com.phonelock.app.ui.components.GroupWalkieSettingsDialog
import com.phonelock.app.ui.components.TextMessageDialog
import com.phonelock.app.ui.components.VoiceRecordDialog
import com.phonelock.app.ui.components.WakeOptionsDialog
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.launch

private data class MemberRow(
    val uid: String,
    val displayName: String,
    val todayRate: Int?,
    val weekRate: Int?,
    val streak: Int?,
    val hasStats: Boolean,
    val shareRoutines: Boolean,
    val profileImage: String? = null,
    val plantLevel: Int? = null,
    val plantTitle: String? = null
)

/** 멤버 이름 첫 글자를 원형 배지로(데스크탑판 MemberAvatar와 대칭). 82차(§6 UX 폴리싱): 전원이 같은
 *  색이면 목록에서 서로 구분이 안 돼 밋밋했다는 지적 — 이름 해시로 앱 테마의 3가지 container 색상 중
 *  하나를 고정 배정해(같은 사람은 항상 같은 색) 목록에 시각적 구분을 준다. 하드코딩 hex 대신 테마
 *  컬러스킴을 쓰므로 10종 테마 어느 걸 골라도 자동으로 어울린다. */
@Composable
private fun MemberAvatar(name: String, profileImage: String? = null) {
    val trimmed = name.trim()
    val idx = (trimmed.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % 3
    val (bg, fg) = when (idx) {
        0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        1 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    val emoji = com.phonelock.app.ui.components.AvatarCatalog.emojiFor(profileImage)
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        if (emoji != null) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
        } else {
            Text(trimmed.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleSmall, color = fg)
        }
    }
}

/**
 * 모임 하나의 멤버 목록 — 오늘 완료율 낮은 순 정렬(처지는 사람 먼저 보이게), 상단에 "오늘 아직 안 한 사람"
 * 배지, 각 멤버(나 제외)에 "😴 깨우기" 버튼, 초대 코드 공유, 나가기/삭제.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SocialGroupMembersScreen(
    repository: PhoneLockRepository,
    groupId: String,
    onOpenMember: (String) -> Unit,
    onOpenDm: (String, String, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val myUid = AuthManager.currentUser?.uid

    var groupName by remember { mutableStateOf("") }
    var groupDescription by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var ownerUid by remember { mutableStateOf("") }
    // 115차(사용자 요청): 모임 "💬 대화" 채널 on/off 설정 — 꺼져 있으면 아래 채널 탭 자체를 숨긴다.
    var chatEnabled by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<MemberRow>>(emptyList()) }
    var viewWeekly by remember { mutableStateOf(false) }
    var announcement by remember { mutableStateOf<SocialGroupSyncClient.Announcement?>(null) }
    var showAnnouncementDialog by remember { mutableStateOf(false) }
    var announcementInput by remember { mutableStateOf("") }
    var groupGoal by remember { mutableStateOf<SocialGroupSyncClient.GroupGoal?>(null) }
    var groupGoalTodaySeconds by remember { mutableStateOf(0) }
    var showGoalDialog by remember { mutableStateOf(false) }
    var goalInput by remember { mutableStateOf("") }
    var quoteStats by remember { mutableStateOf<List<SocialGroupSyncClient.QuoteStat>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var nudgeSentUid by remember { mutableStateOf<String?>(null) }
    var voiceInbox by remember { mutableStateOf<List<SocialGroupSyncClient.VoiceMessageInfo>>(emptyList()) }
    var playingMsgId by remember { mutableStateOf<String?>(null) }
    var walkieSettings by remember { mutableStateOf(SocialGroupSyncClient.GroupWalkieSettings()) }
    var showWalkieSettingsDialog by remember { mutableStateOf(false) }
    var showShareSettingsDialog by remember { mutableStateOf(false) }
    var showRandomNudgeDialog by remember { mutableStateOf(false) }
    var randomNudgeEnabled by remember(groupId) { mutableStateOf(repository.randomNudgeEnabledFor(groupId)) }
    var shareSettings by remember { mutableStateOf(repository.groupShareSettings(groupId)) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showEditInfoDialog by remember { mutableStateOf(false) }
    var showMemberManageDialog by remember { mutableStateOf(false) }
    // 92차 소셜 개편 Phase 4(관리 기능 강화, IDEAS.md 77차) — 모임장 소유권 승계 확인 대상.
    var transferTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // uid to 표시이름
    var admins by remember { mutableStateOf(emptySet<String>()) }
    // 😴 깨우기 대상 — null이 아닌 동안 선택창(옵션→음성/텍스트) 흐름이 진행 중이다. wakeStep이
    // "options"/"voice"/"text" 중 어느 단계인지로 어느 다이얼로그를 띄울지 정한다(옵션 선택 후에도
    // wakeTarget은 그대로 유지돼야 다음 단계에서 누구에게 보낼지 알 수 있다).
    var wakeTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // uid to 표시이름
    var wakeStep by remember { mutableStateOf<String?>(null) }
    // 84차: 태블릿은 데스크탑판 SocialGroupMembersScreen.kt와 같은 좌(멤버 목록)/우(선택한 멤버 상세,
    // SocialGroupMemberDetailScreen을 그대로 내장) 마스터-디테일 — 폰은 기존처럼 onOpenMember로 별도
    // 화면 네비게이션.
    var selectedUid by remember { mutableStateOf<String?>(null) }
    // 92차 소셜 개편 Phase 1: "멤버"/"💬 대화" 채널 전환 — 디스코드식 "서버=모임, 채널=용도별 공간"
    // 구조의 첫 단계(대화 채널만 우선 추가, 공유/관리 채널은 이후 단계에서 검토).
    var channelTab by remember { mutableStateOf(0) }
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) wakeStep = "voice" }

    fun cancelWakeFlow() { wakeTarget = null; wakeStep = null }

    fun reloadInbox() {
        scope.launch {
            voiceInbox = repository.readIncomingVoiceMessages().filter { it.groupId == groupId }
        }
    }

    fun reloadWalkieSettings() {
        scope.launch { walkieSettings = repository.readGroupWalkieSettings(groupId) }
    }

    fun reload() {
        loading = true
        scope.launch {
            val info = repository.readSocialGroupInfo(groupId)
            val members = repository.readSocialGroupMembers(groupId)
            val stats = repository.readSocialGroupStats(groupId).associateBy { it.uid }
            admins = repository.readSocialGroupAdmins(groupId)
            groupName = info?.name ?: ""
            groupDescription = info?.description ?: ""
            inviteCode = info?.inviteCode ?: ""
            ownerUid = info?.ownerUid ?: ""
            chatEnabled = info?.chatEnabled ?: true
            // 82차(§9 "모임 주간 리더보드"): schedule에 이미 담겨오는 ±7일 버퍼 캘린더 데이터로
            // "이번 주"(최근 7일) 완료율을 클라이언트에서 재집계 — 서버 집계/신규 API 없음.
            val fallbackToday = java.time.LocalDate.now()
            rows = members.map { m ->
                val s = stats[m.uid]
                val rate = if (s != null && s.shareRoutines) {
                    val total = s.routines?.size ?: 0
                    val done = s.routines?.count { it.doneToday } ?: 0
                    if (total > 0) done * 100 / total else 0
                } else null
                val weekRate = if (s != null && s.shareSchedule) {
                    // 143차: "최근 7일"의 끝은 그 사람의 하루 시작 기준 오늘(141차) — 옛 버전 데이터면 달력 날짜.
                    val memberToday = s.studyDayKey?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: fallbackToday
                    val weekAgoKey = memberToday.minusDays(6).toString()
                    val todayKey = memberToday.toString()
                    val weekTasks = s.schedule?.filter { it.dateKey in weekAgoKey..todayKey } ?: emptyList()
                    if (weekTasks.isNotEmpty()) weekTasks.count { it.status == "O" } * 100 / weekTasks.size else null
                } else null
                MemberRow(
                    m.uid, s?.displayName ?: m.displayName, rate, weekRate,
                    if (s?.shareStreak == true) s.streak else null, s != null, s?.shareRoutines == true, s?.profileImage,
                    s?.plantLevel, s?.plantTitle
                )
            }.sortedWith(compareBy { it.todayRate ?: -1 })
            groupGoalTodaySeconds = stats.values.filter { it.shareStudy }.sumOf { it.studyTodaySeconds ?: 0 }
            announcement = repository.readSocialGroupAnnouncement(groupId)
            groupGoal = repository.readSocialGroupGoal(groupId)
            quoteStats = repository.readSocialGroupQuoteStats(groupId)
            loading = false
        }
    }

    LaunchedEffect(groupId) {
        // 내 통계를 먼저 올려서(설정에서 공유 켠 항목만) 다른 멤버 화면에도 최신값이 보이게 한다.
        repository.pushMySocialStats(groupId)
        repository.pushMyQuoteStatToGroup(groupId)
        reload()
        reloadInbox()
        reloadWalkieSettings()
    }

    val notDoneCount = rows.count { (it.todayRate ?: 0) < 100 }
    val isOwnerNow = myUid != null && myUid == ownerUid
    val isAdmin = isOwnerNow || (myUid != null && myUid in admins)

    if (showLeaveConfirm) {
        val isOwner = myUid != null && myUid == ownerUid
        LedgerAlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(if (isOwner) "모임 삭제" else "모임 나가기") },
            text = {
                Text(
                    if (isOwner) "모임장이라 삭제하면 모든 멤버가 함께 나가게 됩니다. 계속할까요?"
                    else "이 모임에서 나갈까요?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    scope.launch {
                        if (isOwner) {
                            val result = repository.deleteSocialGroup(groupId)
                            result.onFailure { e -> actionMessage = e.message ?: "삭제에 실패했습니다." }
                            result.onSuccess { onBack() }
                        } else {
                            repository.leaveSocialGroup(groupId)
                            onBack()
                        }
                    }
                }) { Text(if (isOwner) "삭제" else "나가기") }
            },
            dismissButton = { TextButton(onClick = { showLeaveConfirm = false }) { Text("취소") } }
        )
    }

    if (showAnnouncementDialog) {
        LedgerAlertDialog(
            onDismissRequest = { showAnnouncementDialog = false },
            title = { Text("공지 수정") },
            text = {
                OutlinedTextField(
                    value = announcementInput,
                    onValueChange = { announcementInput = it },
                    placeholder = { Text("모임원에게 전할 공지를 입력하세요") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAnnouncementDialog = false
                    scope.launch {
                        val result = repository.writeSocialGroupAnnouncement(groupId, announcementInput.trim())
                        result.onFailure { e -> actionMessage = e.message ?: "저장에 실패했습니다." }
                        result.onSuccess { announcement = repository.readSocialGroupAnnouncement(groupId) }
                    }
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
                OutlinedTextField(
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
                        scope.launch {
                            val result = repository.writeSocialGroupGoal(groupId, minutes)
                            result.onFailure { e -> actionMessage = e.message ?: "저장에 실패했습니다." }
                            result.onSuccess { groupGoal = repository.readSocialGroupGoal(groupId) }
                        }
                    }
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { showGoalDialog = false }) { Text("취소") } }
        )
    }

    val isTablet = com.phonelock.app.ui.components.isTabletWidth()
    // 태블릿에선 카드를 눌러도 별도 화면으로 이동하지 않고 오른쪽 패널에 상세를 띄운다.
    val onMemberClick: (String) -> Unit = if (isTablet) { { uid -> selectedUid = uid } } else onOpenMember

    // 146차: 앱바 → 다른 상세 화면과 같은 머리(뒤로 + 작은 라벨 + 큰 모임 이름), 색 바탕 상자들 → 가는 선 묶음.
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                com.phonelock.app.ui.components.LedgerBackButton(onBack)
                com.phonelock.app.ui.components.Overline("모임", Modifier.weight(1f))
                Box {
                    IconButton(
                        onClick = { showSettingsMenu = true },
                        modifier = Modifier.semantics { contentDescription = "모임 설정" }
                    ) { Icon(androidx.compose.material.icons.Icons.Outlined.Settings, contentDescription = null) }
                    // 82차(§6 UX 폴리싱): 톱니바퀴 바로 아래에서 펼쳐지는 앵커 드롭다운 메뉴.
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
                            // 115차(사용자 요청): 모임 대화 채널 자체를 켜고 끌 수 있게.
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(if (chatEnabled) "모임 대화 끄기" else "모임 대화 켜기") },
                                onClick = {
                                    showSettingsMenu = false
                                    val next = !chatEnabled
                                    chatEnabled = next
                                    if (!next) channelTab = 0
                                    scope.launch {
                                        val result = repository.setSocialGroupChatEnabled(groupId, next)
                                        result.onFailure { e ->
                                            chatEnabled = !next
                                            actionMessage = e.message ?: "설정 변경에 실패했습니다."
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
            Text(
                groupName.ifBlank { "모임" },
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.gutter)
            )
            if (groupDescription.isNotBlank()) {
                Text(
                    groupDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Spacing.gutter)
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            if (chatEnabled) {
                com.phonelock.app.ui.components.SectionTabs(listOf("멤버", "대화"), channelTab, { channelTab = it })
            } else {
                com.phonelock.app.ui.components.Hairline()
            }
        }
    }) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            // 86차(사용자 요청): 위쪽 묶음들도 전부 item으로 넣어 하나의 LazyColumn으로 — 화면이 작아도 멤버 목록이
            // 묶음들에 밀려 안 보이는 일이 없다. 98차: 당겨서 새로고침.
            com.phonelock.app.ui.components.PullToRefreshBox(onRefresh = { reload() }) {
            Column(Modifier.fillMaxSize().padding(padding)) {
            if (chatEnabled && channelTab == 1) {
                GroupChatScreen(repository, groupId)
            } else {
            val displayRows = if (viewWeekly) rows.sortedWith(compareBy { it.weekRate ?: -1 }) else rows
            val membersListContent: @Composable (Modifier) -> Unit = { listModifier ->
            LazyColumn(modifier = listModifier) {
                // 이 화면의 주인공 숫자 — 오늘 아직 다 못 한 사람 수(처지는 사람을 먼저 챙기는 모임의 목적).
                item {
                    Column(Modifier.fillMaxWidth().padding(top = Spacing.md, bottom = Spacing.md)) {
                        com.phonelock.app.ui.components.Overline("오늘 아직 안 한 사람")
                        com.phonelock.app.ui.components.BigNumber(
                            "$notDoneCount",
                            unit = "/ ${rows.size}명",
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
                                com.phonelock.app.ui.components.ProgressLine(
                                    if (targetSeconds > 0) groupGoalTodaySeconds.toFloat() / targetSeconds else 0f,
                                    color = LocalPhoneLockPalette.current.fillGood
                                )
                            }
                        }
                        actionMessage?.let { msg ->
                            Spacer(Modifier.height(Spacing.sm))
                            com.phonelock.app.ui.components.NoticeStrip(msg)
                        }
                    }
                }

                if (voiceInbox.isNotEmpty()) {
                    item {
                        com.phonelock.app.ui.components.LedgerSection("받은 깨우기 메시지 ${voiceInbox.size}") {
                            voiceInbox.forEach { msg ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (msg.textMessage.isNotBlank()) Icons.AutoMirrored.Outlined.Chat else Icons.Outlined.Mic,
                                        contentDescription = if (msg.textMessage.isNotBlank()) "텍스트 메시지" else "음성 메시지",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(Spacing.sm))
                                    Column(Modifier.weight(1f)) {
                                        Text(msg.fromName, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                        Text(
                                            if (msg.textMessage.isNotBlank()) msg.textMessage else "${msg.durationMs / 1000}초",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        enabled = playingMsgId != msg.msgId,
                                        onClick = {
                                            playingMsgId = msg.msgId
                                            if (msg.textMessage.isNotBlank()) {
                                                TtsPlayer.speak(context, msg.textMessage, walkieSettings.volume, walkieSettings.voiceGender) { playingMsgId = null }
                                            } else {
                                                val wavBytes = runCatching { Base64.decode(msg.audioBase64, Base64.NO_WRAP) }.getOrNull()
                                                if (wavBytes != null) {
                                                    VoicePlayer.play(context, wavBytes, walkieSettings.volume) { playingMsgId = null }
                                                } else {
                                                    playingMsgId = null
                                                }
                                            }
                                            // 들은 즉시 지우지 않고 "들었음"만 표시 — 다시 듣고 싶을 수 있어서
                                            // 유예시간(24시간) 동안은 남겨두고, 지나면 다음 조회 때 자동으로 지워진다.
                                            scope.launch { repository.markVoiceMessageListened(msg.groupId, msg) }
                                        }
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = if (playingMsgId == msg.msgId) "재생 중" else "재생", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            // 서버 삭제가 실제로 성공했을 때만 목록에서 뺀다 — 무조건 빼면 서버에서 실패해도(권한 등)
                                            // 지워진 것처럼 보이다가 다음 조회 때 다시 나타난다. 실패 사유는 그대로 보여준다.
                                            val result = repository.deleteVoiceMessage(msg.groupId, msg.msgId)
                                            if (result.isSuccess) {
                                                voiceInbox = voiceInbox.filter { it.msgId != msg.msgId }
                                            } else {
                                                Toast.makeText(context, result.exceptionOrNull()?.message ?: "삭제에 실패했습니다.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Outlined.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Spacer(Modifier.height(Spacing.sm))
                        }
                    }
                }

                // 82차(§9 "모임장 공지사항") — 있으면 항상 상단에, 관리자만 편집 가능.
                if (announcement != null || isAdmin) {
                    item {
                        com.phonelock.app.ui.components.LedgerSection(
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
                }

                // 82차(§9 "모임 주간 리더보드"): 오늘/이번 주 — 서버 집계 없이 이미 불러온 rows를 다시 정렬만 한다.
                item {
                    com.phonelock.app.ui.components.LedgerSection(
                        "멤버 ${rows.size}명",
                        trailing = {
                            com.phonelock.app.ui.components.SegmentedTabs(
                                listOf("오늘", "이번 주"),
                                if (viewWeekly) 1 else 0,
                                { viewWeekly = it == 1 },
                                Modifier.width(168.dp)
                            )
                        }
                    ) {}
                }

                items(displayRows, key = { it.uid }) { row ->
                    val rate = if (viewWeekly) row.weekRate else row.todayRate
                    Column(
                        // 82차(§6 UX 폴리싱): "오늘"/"이번 주"로 정렬이 바뀔 때 줄이 순간이동 대신 부드럽게 이동한다.
                        Modifier.fillMaxWidth().animateItemPlacement().clickable { onMemberClick(row.uid) }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MemberAvatar(row.displayName, row.profileImage)
                            Spacer(Modifier.width(Spacing.md))
                            Column(Modifier.weight(1f)) {
                                MemberDisplayName(
                                    title = row.plantTitle,
                                    name = row.displayName + if (row.uid == myUid) " (나)" else "",
                                    level = row.plantLevel
                                )
                                val meta = buildList {
                                    if (row.streak != null && row.streak > 0) add("연속 ${row.streak}일")
                                    if (!row.hasStats) add("아직 동기화된 기록 없음")
                                    else if (rate == null && !row.shareRoutines) add("비공개")
                                }
                                if (meta.isNotEmpty()) {
                                    Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (row.hasStats && rate != null) {
                                    Spacer(Modifier.height(6.dp))
                                    com.phonelock.app.ui.components.ProgressLine(
                                        rate / 100f,
                                        color = if (rate >= 100) LocalPhoneLockPalette.current.fillGood else MaterialTheme.colorScheme.primary,
                                        thickness = 3.dp
                                    )
                                }
                            }
                            if (row.hasStats && rate != null) {
                                Spacer(Modifier.width(Spacing.sm))
                                Text("${rate}%", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
                            }
                            if (row.uid != myUid) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            repository.ensureDmChat(row.uid, row.displayName).onSuccess { chatId ->
                                                onOpenDm(chatId, row.uid, row.displayName)
                                            }
                                        }
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = "${row.displayName}에게 DM 보내기"
                                    }
                                ) { Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                IconButton(
                                    onClick = {
                                        wakeTarget = row.uid to row.displayName
                                        wakeStep = "options"
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = "${row.displayName} 깨우기"
                                    }
                                ) {
                                    Icon(
                                        if (nudgeSentUid == row.uid) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationsActive,
                                        contentDescription = null,
                                        tint = if (nudgeSentUid == row.uid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        com.phonelock.app.ui.components.Hairline()
                    }
                }

                // 82차(§11 "모임 랭킹") — 확인 질문 저항률 비교(재미 요소).
                if (quoteStats.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(Spacing.lg))
                        com.phonelock.app.ui.components.LedgerSection("확인 질문 저항률 순위", divider = false) {
                            quoteStats.sortedByDescending { it.stopRatePercent }.forEachIndexed { idx, qs ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${idx + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                                    Text(qs.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text("${qs.stopRatePercent}% · ${qs.totalCount}회", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }

                if (inviteCode.isNotBlank()) {
                    item {
                        Spacer(Modifier.height(Spacing.md))
                        com.phonelock.app.ui.components.LedgerSection("초대 코드") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(inviteCode, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(inviteCode))
                                    Toast.makeText(context, "복사했습니다", Toast.LENGTH_SHORT).show()
                                }) { Text("복사", maxLines = 1, softWrap = false) }
                                TextButton(onClick = {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "\"$groupName\" 모임 초대 코드: $inviteCode")
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "초대 코드 공유"))
                                }) { Text("공유", maxLines = 1, softWrap = false) }
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(Spacing.lg))
                    OutlinedButton(
                        onClick = { showLeaveConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (myUid != null && myUid == ownerUid) "모임 삭제" else "모임 나가기", maxLines = 1, softWrap = false) }
                    Spacer(Modifier.height(Spacing.xl))
                }
            }
            }

            if (isTablet) {
                // 84차: 데스크탑 SocialGroupMembersScreen.kt와 같은 좌우 분할 — 오른쪽엔 이미 태블릿
                // 대응된 SocialGroupMemberDetailScreen을 그대로 내장한다(uid만 바꿔주면 됨, 새 화면
                // 아님). onBack은 "뒤로 화면 전환"이 아니라 "선택 해제"로 자연스럽게 대응된다.
                Row(Modifier.fillMaxSize().padding(horizontal = Spacing.gutter)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        membersListContent(Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(Spacing.lg))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        val uid = selectedUid
                        if (uid == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "멤버를 고르면 여기 보입니다.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            SocialGroupMemberDetailScreen(repository, groupId, uid, onOpenDm = onOpenDm, onBack = { selectedUid = null })
                        }
                    }
                }
            } else {
                membersListContent(Modifier.fillMaxSize().padding(horizontal = Spacing.gutter))
            }
            }
            }
            }
        }
    }

    if (showEditInfoDialog) {
        var nameText by remember { mutableStateOf(groupName) }
        var descriptionText by remember { mutableStateOf(groupDescription) }
        var regenMessage by remember { mutableStateOf<String?>(null) }
        LedgerAlertDialog(
            onDismissRequest = { showEditInfoDialog = false },
            title = { Text("모임 이름·코드 수정") },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        label = { Text("모임 이름") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = descriptionText,
                        onValueChange = { descriptionText = it },
                        label = { Text("설명(선택)") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text("초대 코드", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(inviteCode, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(Spacing.xs))
                    OutlinedButton(onClick = {
                        scope.launch {
                            val result = repository.regenerateSocialGroupInviteCode(groupId)
                            regenMessage = if (result.isSuccess) "새 코드로 바뀌었습니다." else (result.exceptionOrNull()?.message ?: "재발급에 실패했습니다.")
                            reload()
                        }
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
                    scope.launch { repository.updateSocialGroupName(groupId, nameText, descriptionText); reload() }
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
                    rows.filter { it.uid != myUid }.forEach { m ->
                        val targetIsOwner = m.uid == ownerUid
                        val targetIsAdmin = m.uid in admins
                        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    m.displayName + when { targetIsOwner -> " (모임장)"; targetIsAdmin -> " (관리자)"; else -> "" },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            if (isOwnerNow && !targetIsOwner) {
                                TextButton(onClick = {
                                    scope.launch { repository.setSocialGroupAdmin(groupId, m.uid, !targetIsAdmin); reload() }
                                }) { Text(if (targetIsAdmin) "관리자 해제" else "관리자 지정") }
                                TextButton(onClick = { transferTarget = m.uid to m.displayName }) { Text("모임장 넘기기", maxLines = 1, softWrap = false) }
                            }
                            if (!targetIsOwner) {
                                TextButton(onClick = {
                                    scope.launch { repository.kickSocialGroupMember(groupId, m.uid); reload() }
                                }) { Text("내쫓기") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMemberManageDialog = false }) { Text("닫기") } }
        )
    }

    transferTarget?.let { (targetUid, targetName) ->
        LedgerAlertDialog(
            onDismissRequest = { transferTarget = null },
            title = { Text("모임장 넘기기") },
            text = { Text("\"$targetName\"님에게 모임장을 넘길까요? 나는 자동으로 관리자가 됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    transferTarget = null
                    scope.launch {
                        val result = repository.transferSocialGroupOwnership(groupId, targetUid)
                        result.onFailure { e -> actionMessage = e.message ?: "소유권 승계에 실패했습니다." }
                        result.onSuccess { showMemberManageDialog = false; reload() }
                    }
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
                // 98차 버그 수정: 통계만 다시 올리고 화면의 rows(멤버별 공유 통계)는 안 새로고침해서,
                // 공유 설정을 꺼도 화면 나갔다 와야 반영되던 버그 — push가 끝난 뒤 reload()로 다시 읽는다
                // (순서가 바뀌면 reload가 아직 반영 안 된 옛 통계를 읽어올 수 있음).
                scope.launch { repository.pushMySocialStats(groupId); reload() }
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
                scope.launch {
                    val result = repository.writeGroupWalkieSettings(groupId, settings)
                    result.onFailure { e -> Toast.makeText(context, e.message ?: "저장에 실패했습니다.", Toast.LENGTH_LONG).show() }
                }
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
                        "하루 한 번 무작위 시각에, 아직 못 한 멤버가 있으면 알려 드립니다.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("이 모임에서 켜기", style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.material3.Switch(
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
                    scope.launch {
                        repository.sendSocialGroupNudge(groupId, targetUid)
                        nudgeSentUid = targetUid
                        Toast.makeText(context, "깨우기를 보냈습니다", Toast.LENGTH_SHORT).show()
                    }
                    cancelWakeFlow()
                },
                onOpenVoiceRecorder = {
                    if (VoiceRecorder.hasPermission(context)) wakeStep = "voice"
                    else recordPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                },
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
                    scope.launch {
                        val audioBase64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                        val result = repository.sendVoiceMessage(groupId, targetUid, audioBase64, durationMs)
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "무전을 보냈습니다" else (result.exceptionOrNull()?.message ?: "전송 실패"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
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
                    scope.launch {
                        val result = repository.sendTextMessage(groupId, targetUid, text)
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "메시지를 보냈습니다" else (result.exceptionOrNull()?.message ?: "전송 실패"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                cancelWakeFlow()
            }
        )
    }
}
