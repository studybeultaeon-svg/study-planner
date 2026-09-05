package com.phonelock.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
private fun MemberAvatar(name: String, highlighted: Boolean) {
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
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(trimmed.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** shareRoutines가 켜져 있고 오늘 예정 루틴이 있을 때만 완료율을 계산, 그 외엔 null(정렬 시 맨 뒤로). */
private fun completionRatio(m: SocialGroupSyncClient.MemberStats): Double? =
    if (m.shareRoutines && m.routines.isNotEmpty()) m.routines.count { it.doneToday }.toDouble() / m.routines.size else null

/** 82차(§9 "모임 주간 리더보드") — schedule에 이미 담겨오는 ±버퍼 캘린더 데이터로 최근 7일 완료율을 재집계. */
private fun weekCompletionRatio(m: SocialGroupSyncClient.MemberStats): Double? {
    if (!m.shareSchedule) return null
    val today = java.time.LocalDate.now()
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
fun SocialGroupMembersScreen(repository: Repository, groupId: String, onBack: () -> Unit) {
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
        AlertDialog(
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
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("모임 삭제") },
            text = { Text("\"${info?.name ?: ""}\" 모임을 완전히 삭제합니다(되돌리기 없음, 멤버 전원 제외됨). 계속할까요?") },
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
        AlertDialog(
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
        AlertDialog(
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

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(Spacing.md)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("< 목록") }
                Row {
                    Box {
                        TextButton(onClick = { showSettingsMenu = true }) { Text("⚙ 설정") }
                        // 82차(§6 UX 폴리싱, 안드로이드판과 대칭): 밋밋한 AlertDialog 버튼 목록 대신
                        // 버튼 바로 아래에서 펼쳐지는 앵커된 드롭다운 메뉴로.
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
                    if (isOwner) {
                        OutlinedButton(onClick = { showDeleteConfirm = true }) { Text("모임 삭제") }
                    } else {
                        OutlinedButton(onClick = { showLeaveConfirm = true }) { Text("나가기") }
                    }
                }
            }
            Text(info?.name ?: "", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(Spacing.sm))

            if (voiceInbox.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Column(Modifier.padding(Spacing.sm)) {
                        Text("받은 음성메시지", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        voiceInbox.forEach { msg ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text((if (msg.textMessage.isNotBlank()) "💬 " else "🎙️ ") + msg.fromName, style = MaterialTheme.typography.bodyMedium)
                                    if (msg.textMessage.isNotBlank()) {
                                        Text(msg.textMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        Text("${msg.durationMs / 1000}초", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                TextButton(
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
                                    if (playingMsgId != msg.msgId) {
                                        androidx.compose.material3.Icon(
                                            Icons.Filled.PlayArrow, contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Text(if (playingMsgId == msg.msgId) "재생 중" else "재생")
                                }
                                TextButton(onClick = {
                                    voiceDeleteError = null
                                    Thread {
                                        // 서버 삭제가 실제로 성공했을 때만 목록에서 뺀다 — 무조건 빼면 삭제가
                                        // 서버에서 실패해도(권한 등) 화면상으론 지워진 것처럼 보이다가 다음에
                                        // 다시 조회하면 그대로 남아있어 "삭제가 안 된다"는 혼란을 준다.
                                        val result = SocialGroupSyncClient.deleteVoiceMessage(url, key, msg.groupId, msg.msgId)
                                        if (result.isSuccess) {
                                            voiceInbox = voiceInbox.filter { it.msgId != msg.msgId }
                                        } else {
                                            voiceDeleteError = result.exceptionOrNull()?.message
                                        }
                                    }.start()
                                }) { Text("삭제") }
                            }
                        }
                    }
                }
                voiceDeleteError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(Spacing.sm))
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("초대 코드", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(info?.inviteCode ?: "", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = {
                        info?.inviteCode?.let { clipboard.setText(AnnotatedString(it)) }
                    }) { Text("복사") }
                }
            }
            Spacer(Modifier.height(Spacing.sm))

            // 82차(§9 "모임장 공지사항") — 있으면 항상 상단에, 관리자만 편집 가능.
            if (announcement != null || isAdmin) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("📢 공지", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(announcement?.text ?: "아직 공지가 없습니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        if (isAdmin) {
                            TextButton(onClick = { announcementInput = announcement?.text ?: ""; showAnnouncementDialog = true }) { Text("수정") }
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
            }

            // 82차(§9 "모임 공동 목표").
            if (groupGoal != null || isAdmin) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("🎯 모임 목표", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
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
                            Text("오늘 함께 ${groupGoalTodaySeconds / 60}분 / 목표 ${groupGoal!!.targetMinutes}분", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        } else {
                            Text("아직 목표가 없습니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
            }

            // 82차(§11 "모임 랭킹") — 회유 멘트 저항률 비교(재미 요소).
            if (quoteStats.isNotEmpty()) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                        Text("😤 모임 랭킹 (회유 멘트 저항률)", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(Spacing.xs))
                        quoteStats.sortedByDescending { it.stopRatePercent }.forEachIndexed { idx, qs ->
                            Text("${idx + 1}위 ${qs.displayName} — ${qs.stopRatePercent}% (${qs.totalCount}회 중)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
            }

            // 82차(§9 "모임 주간 리더보드") — 오늘/이번 주 토글.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                TextButton(onClick = { viewWeekly = false }) { Text(if (!viewWeekly) "● 오늘" else "오늘") }
                TextButton(onClick = { viewWeekly = true }) { Text(if (viewWeekly) "● 이번 주" else "이번 주") }
            }

            if (notDoneCount > 0) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        "오늘 아직 안 한 사람 ${notDoneCount}명",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                sortedStats.forEach { m ->
                    val ratio = if (viewWeekly) weekCompletionRatio(m) else completionRatio(m)
                    val percentLabel = if (ratio != null) "${Math.round(ratio * 100)}%" else if (m.shareRoutines) "-" else "비공개"
                    val isSelfRow = m.uid == myUid
                    val isSelected = m.uid == selectedUid
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline),
                        onClick = { selectedUid = m.uid }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MemberAvatar(m.displayName, highlighted = isSelected)
                            Spacer(Modifier.width(Spacing.sm))
                            Column(Modifier.weight(1f)) {
                                Text(m.displayName + if (isSelfRow) " (나)" else "", style = MaterialTheme.typography.bodyLarge)
                                if (m.shareStreak && m.streak > 0) {
                                    Text("🔥 ${m.streak}일", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (ratio != null) {
                                    Spacer(Modifier.height(2.dp))
                                    LinearProgressIndicator(
                                        progress = { ratio.toFloat() },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                                    )
                                }
                            }
                            Spacer(Modifier.width(Spacing.sm))
                            Text(percentLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            if (!isSelfRow) {
                                Spacer(Modifier.width(Spacing.xs))
                                TextButton(onClick = {
                                    wakeTarget = m.uid to m.displayName
                                    wakeStep = "options"
                                }) { Text(if (nudgeSentUid == m.uid) "보냄!" else "😴") }
                            }
                        }
                    }
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
                    Text("멤버를 선택하면 여기서 상세를 볼 수 있습니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showEditInfoDialog) {
        var nameText by remember { mutableStateOf(info?.name ?: "") }
        var regenMessage by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showEditInfoDialog = false },
            title = { Text("✏️ 모임 이름/코드 수정") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        label = { Text("모임 이름") },
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
                    Thread { SocialGroupSyncClient.updateGroupName(url, key, groupId, nameText); refresh() }.start()
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

    if (showShareSettingsDialog) {
        GroupShareSettingsDialog(
            initial = shareSettings,
            onDismiss = { showShareSettingsDialog = false },
            onSave = { settings ->
                showShareSettingsDialog = false
                shareSettings = settings
                repository.setGroupShareSettings(groupId, settings)
                // 설정을 바꾼 즉시 반영되도록 통계를 다시 올린다.
                Thread { SocialGroupSyncClient.pushMyStats(url, key, groupId, repository) }.start()
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
        AlertDialog(
            onDismissRequest = { showRandomNudgeDialog = false },
            title = { Text("🔔 무작위 알림") },
            text = {
                Column {
                    Text(
                        "하루 중 무작위 시각에, 이 모임에서 오늘 할 일을 아직 못 한 멤버에게 이 기기가 자동으로 깨우기를 보냅니다.",
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
