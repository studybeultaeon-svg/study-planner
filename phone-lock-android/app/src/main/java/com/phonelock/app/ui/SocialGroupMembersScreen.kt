package com.phonelock.app.ui

import android.content.Intent
import android.util.Base64
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
    val shareRoutines: Boolean
)

/** 멤버 이름 첫 글자를 원형 배지로(데스크탑판 MemberAvatar와 대칭). 82차(§6 UX 폴리싱): 전원이 같은
 *  색이면 목록에서 서로 구분이 안 돼 밋밋했다는 지적 — 이름 해시로 앱 테마의 3가지 container 색상 중
 *  하나를 고정 배정해(같은 사람은 항상 같은 색) 목록에 시각적 구분을 준다. 하드코딩 hex 대신 테마
 *  컬러스킴을 쓰므로 10종 테마 어느 걸 골라도 자동으로 어울린다. */
@Composable
private fun MemberAvatar(name: String) {
    val trimmed = name.trim()
    val idx = (trimmed.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % 3
    val (bg, fg) = when (idx) {
        0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        1 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(trimmed.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleSmall, color = fg)
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val myUid = AuthManager.currentUser?.uid

    var groupName by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var ownerUid by remember { mutableStateOf("") }
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
    var admins by remember { mutableStateOf(emptySet<String>()) }
    // 😴 깨우기 대상 — null이 아닌 동안 선택창(옵션→음성/텍스트) 흐름이 진행 중이다. wakeStep이
    // "options"/"voice"/"text" 중 어느 단계인지로 어느 다이얼로그를 띄울지 정한다(옵션 선택 후에도
    // wakeTarget은 그대로 유지돼야 다음 단계에서 누구에게 보낼지 알 수 있다).
    var wakeTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // uid to 표시이름
    var wakeStep by remember { mutableStateOf<String?>(null) }
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
            inviteCode = info?.inviteCode ?: ""
            ownerUid = info?.ownerUid ?: ""
            // 82차(§9 "모임 주간 리더보드"): schedule에 이미 담겨오는 ±7일 버퍼 캘린더 데이터로
            // "이번 주"(최근 7일) 완료율을 클라이언트에서 재집계 — 서버 집계/신규 API 없음.
            val today = java.time.LocalDate.now()
            val weekAgoKey = today.minusDays(6).toString()
            val todayKey = today.toString()
            rows = members.map { m ->
                val s = stats[m.uid]
                val rate = if (s != null && s.shareRoutines) {
                    val total = s.routines?.size ?: 0
                    val done = s.routines?.count { it.doneToday } ?: 0
                    if (total > 0) done * 100 / total else 0
                } else null
                val weekRate = if (s != null && s.shareSchedule) {
                    val weekTasks = s.schedule?.filter { it.dateKey in weekAgoKey..todayKey } ?: emptyList()
                    if (weekTasks.isNotEmpty()) weekTasks.count { it.status == "O" } * 100 / weekTasks.size else null
                } else null
                MemberRow(m.uid, s?.displayName ?: m.displayName, rate, weekRate, if (s?.shareStreak == true) s.streak else null, s != null, s?.shareRoutines == true)
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
        AlertDialog(
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
        AlertDialog(
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
        AlertDialog(
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

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (groupName.isNotBlank()) groupName else "모임") },
            actions = {
                Box {
                    IconButton(
                        onClick = { showSettingsMenu = true },
                        modifier = Modifier.semantics { contentDescription = "모임 설정" }
                    ) { Text("⚙") }
                    // 82차(§6 UX 폴리싱): 밋밋한 AlertDialog 버튼 목록 대신 앵커된 드롭다운 메뉴로 —
                    // 톱니바퀴 바로 아래에서 펼쳐지는 게 "설정 창"보다 실제 위치와 맞고, Material 기본
                    // 펼침/접힘 애니메이션이 그대로 적용된다.
                    androidx.compose.material3.DropdownMenu(
                        expanded = showSettingsMenu,
                        onDismissRequest = { showSettingsMenu = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("🔒 공유 설정") },
                            onClick = { showSettingsMenu = false; showShareSettingsDialog = true }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("🎙️ 무전기") },
                            onClick = { showSettingsMenu = false; showWalkieSettingsDialog = true }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("🔔 무작위 알림") },
                            onClick = { showSettingsMenu = false; showRandomNudgeDialog = true }
                        )
                        if (isAdmin) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("✏️ 모임 이름/코드 수정") },
                                onClick = { showSettingsMenu = false; showEditInfoDialog = true }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("👥 멤버 관리") },
                                onClick = { showSettingsMenu = false; showMemberManageDialog = true }
                            )
                        }
                    }
                }
            }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                if (voiceInbox.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text("받은 음성메시지", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.height(Spacing.sm))
                            voiceInbox.forEach { msg ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text((if (msg.textMessage.isNotBlank()) "💬 " else "🎙️ ") + msg.fromName, style = MaterialTheme.typography.bodyMedium)
                                        if (msg.textMessage.isNotBlank()) {
                                            Text(msg.textMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } else {
                                            Text("${msg.durationMs / 1000}초", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Button(
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
                                    ) { Text(if (playingMsgId == msg.msgId) "재생 중" else "▶ 재생") }
                                    Spacer(Modifier.width(Spacing.xs))
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            // 서버 삭제가 실제로 성공했을 때만 목록에서 뺀다 — 무조건 빼면 삭제가
                                            // 서버에서 실패해도(권한 등) 화면상으론 지워진 것처럼 보이다가 다음에
                                            // 다시 조회하면 그대로 남아있어 "삭제가 안 된다"는 혼란을 준다. 실패
                                            // 사유(상태코드/응답 본문)를 그대로 보여줘서 "네트워크 확인"처럼
                                            // 얼버무리지 않는다.
                                            val result = repository.deleteVoiceMessage(msg.groupId, msg.msgId)
                                            if (result.isSuccess) {
                                                voiceInbox = voiceInbox.filter { it.msgId != msg.msgId }
                                            } else {
                                                Toast.makeText(context, result.exceptionOrNull()?.message ?: "삭제에 실패했습니다.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }) { Text("삭제") }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                }

                if (inviteCode.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(Modifier.padding(Spacing.md)) {
                            Text("초대 코드", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(inviteCode, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(Spacing.sm))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                OutlinedButton(onClick = {
                                    clipboard.setText(AnnotatedString(inviteCode))
                                    Toast.makeText(context, "복사했습니다", Toast.LENGTH_SHORT).show()
                                }) { Text("복사") }
                                OutlinedButton(onClick = {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "\"$groupName\" 모임 초대 코드: $inviteCode")
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "초대 코드 공유"))
                                }) { Text("공유") }
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                }

                if (rows.isNotEmpty() && notDoneCount > 0) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            "오늘 아직 안 한 사람 ${notDoneCount}명",
                            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }

                actionMessage?.let { msg ->
                    Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(Spacing.sm))
                }

                // 82차(§9 "모임장 공지사항") — 있으면 항상 상단에, 관리자만 편집 가능.
                if (announcement != null || isAdmin) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Row(Modifier.fillMaxWidth().padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("📢 공지", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                Text(
                                    announcement?.text ?: "아직 공지가 없습니다.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            if (isAdmin) {
                                TextButton(onClick = { announcementInput = announcement?.text ?: ""; showAnnouncementDialog = true }) { Text("수정") }
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }

                // 82차(§9 "모임 공동 목표") — 관리자가 목표(분)를 정하면 오늘 공유된 멤버들의 공부시간 합으로 진행바를 보여준다.
                if (groupGoal != null || isAdmin) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Column(Modifier.fillMaxWidth().padding(Spacing.md)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("🎯 모임 목표", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
                                if (isAdmin) {
                                    TextButton(onClick = { goalInput = groupGoal?.targetMinutes?.toString() ?: ""; showGoalDialog = true }) { Text("설정") }
                                }
                            }
                            if (groupGoal != null) {
                                val targetSeconds = groupGoal!!.targetMinutes * 60
                                val progress = if (targetSeconds > 0) (groupGoalTodaySeconds.toFloat() / targetSeconds).coerceIn(0f, 1f) else 0f
                                Spacer(Modifier.height(Spacing.xs))
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                                Spacer(Modifier.height(Spacing.xs))
                                Text(
                                    "오늘 함께 ${groupGoalTodaySeconds / 60}분 / 목표 ${groupGoal!!.targetMinutes}분",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            } else {
                                Text("아직 목표가 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }

                // 82차(§11 "모임 랭킹") — 회유 멘트 저항률 비교(재미 요소, 딱딱한 통계 톤과 차별화).
                if (quoteStats.isNotEmpty()) {
                    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(Spacing.md)) {
                            Text("😤 모임 랭킹 (회유 멘트 저항률)", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(Spacing.xs))
                            quoteStats.sortedByDescending { it.stopRatePercent }.forEachIndexed { idx, qs ->
                                Text(
                                    "${idx + 1}위 ${qs.displayName} — ${qs.stopRatePercent}% (${qs.totalCount}회 중)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }

                // 82차(§9 "모임 주간 리더보드"): 오늘/이번 주 토글 — 서버 집계 없이 이미 불러온 rows를 다시 정렬만 한다.
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    FilterChip(selected = !viewWeekly, onClick = { viewWeekly = false }, label = { Text("오늘") })
                    FilterChip(selected = viewWeekly, onClick = { viewWeekly = true }, label = { Text("이번 주") })
                }
                Spacer(Modifier.height(Spacing.sm))
                val displayRows = if (viewWeekly) rows.sortedWith(compareBy { it.weekRate ?: -1 }) else rows

                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(displayRows, key = { it.uid }) { row ->
                        val rate = if (viewWeekly) row.weekRate else row.todayRate
                        Surface(
                            // 82차(§6 UX 폴리싱): "오늘"/"이번 주" 토글로 정렬이 바뀔 때 카드가 순간이동
                            // 대신 부드럽게 이동하도록 — 신규 의존성 없이 Compose foundation 기본 제공.
                            modifier = Modifier.fillMaxWidth().animateItemPlacement().clickable { onOpenMember(row.uid) },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(Spacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MemberAvatar(row.displayName)
                                Spacer(Modifier.width(Spacing.sm))
                                Column(Modifier.weight(1f)) {
                                    Text(row.displayName + if (row.uid == myUid) " (나)" else "", style = MaterialTheme.typography.titleMedium)
                                    if (row.streak != null && row.streak > 0) {
                                        Text("🔥 ${row.streak}일", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (row.hasStats && rate != null) {
                                        Spacer(Modifier.height(2.dp))
                                        LinearProgressIndicator(
                                            progress = { rate / 100f },
                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                                        )
                                    } else if (!row.hasStats) {
                                        Text("아직 동기화된 통계가 없습니다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.width(Spacing.sm))
                                if (row.hasStats) {
                                    val percentLabel = if (rate != null) "${rate}%" else if (row.shareRoutines) "-" else "비공개"
                                    Text(percentLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                if (row.uid != myUid) {
                                    IconButton(
                                        onClick = {
                                            wakeTarget = row.uid to row.displayName
                                            wakeStep = "options"
                                        },
                                        modifier = Modifier.semantics {
                                            contentDescription = "${row.displayName} 깨우기"
                                        }
                                    ) { Text(if (nudgeSentUid == row.uid) "보냄!" else "😴") }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.md))
                Button(
                    onClick = { showLeaveConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (myUid != null && myUid == ownerUid) "모임 삭제" else "모임 나가기") }
            }
        }
    }

    if (showEditInfoDialog) {
        var nameText by remember { mutableStateOf(groupName) }
        var regenMessage by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showEditInfoDialog = false },
            title = { Text("✏️ 모임 이름/코드 수정") },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        label = { Text("모임 이름") },
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
                    }) { Text("🔄 코드 재발급") }
                    regenMessage?.let {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showEditInfoDialog = false
                    scope.launch { repository.updateSocialGroupName(groupId, nameText); reload() }
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { showEditInfoDialog = false }) { Text("취소") } }
        )
    }

    if (showMemberManageDialog) {
        AlertDialog(
            onDismissRequest = { showMemberManageDialog = false },
            title = { Text("👥 멤버 관리") },
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

    if (showShareSettingsDialog) {
        GroupShareSettingsDialog(
            initial = shareSettings,
            onDismiss = { showShareSettingsDialog = false },
            onSave = { settings ->
                showShareSettingsDialog = false
                shareSettings = settings
                repository.setGroupShareSettings(groupId, settings)
                // 설정을 바꾼 즉시 반영되도록 통계를 다시 올린다.
                scope.launch { repository.pushMySocialStats(groupId) }
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
        AlertDialog(
            onDismissRequest = { showRandomNudgeDialog = false },
            title = { Text("🔔 무작위 알림") },
            text = {
                Column {
                    Text(
                        "하루 중 무작위 시각에, 이 모임에서 오늘 할 일을 아직 못 한 멤버가 있으면 이 기기로 알려드립니다. 직접 확인하고 필요하면 깨우기를 보내주세요.",
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
