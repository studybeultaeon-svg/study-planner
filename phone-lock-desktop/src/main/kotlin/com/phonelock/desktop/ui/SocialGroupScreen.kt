package com.phonelock.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.getEarnedPointsTotal
import com.phonelock.desktop.data.getTotalStudyMinutes
import com.phonelock.desktop.data.getPointsBalance
import com.phonelock.desktop.data.getRewards
import com.phonelock.desktop.data.addReward
import com.phonelock.desktop.data.deleteReward
import com.phonelock.desktop.data.redeemReward
import com.phonelock.desktop.monitor.AuthManager
import com.phonelock.desktop.monitor.SocialGroupSyncClient
import com.phonelock.desktop.ui.theme.Spacing
import com.phonelock.shared.CharacterGrowth
import com.phonelock.shared.StudyLevel

/** 소셜 화면 배경(사용자 지적으로 재디자인, 안드로이드판과 대칭) — 공부 잠금 화면과 같은 중앙 원형
 *  `radialGradient`를 그대로 썼더니, 그 "빛나는 원"은 잠금 화면의 원형 진행률 링과 짝을 이루는
 *  디자인이라 링이 없는 리스트 화면(소셜)에선 정체불명의 얼룩처럼 보인다는 지적을 받았다 — 잠금 화면
 *  쪽은 그대로 두고, 소셜 쪽만 위→아래로 옅어지는 리니어 그라디언트(메신저 앱 상단 배너 톤)로 교체해
 *  원형 "빛나는 점" 인상을 없앴다. */
@Composable
internal fun socialGradientBackground() = Brush.verticalGradient(
    colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), MaterialTheme.colorScheme.background)
)

/** 섹션 제목/수치 옆에 붙는 작은 pill 라벨. */
@Composable
internal fun SectionPill(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

/** 모임 이름 첫 글자를 원형 배지로 — 목록에서 항목을 시각적으로 구분하기 쉽게 한다. */
@Composable
private fun GroupAvatar(name: String) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private data class GroupSummary(val id: String, val name: String, val memberCount: Int, val avgTodayPercent: Int)

/** DM 상대 첫 글자를 원형 배지로(모임 [GroupAvatar]와 같은 패턴, 색만 secondary로 구분). */
@Composable
private fun DmAvatar(label: String) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/** 각 멤버의 오늘 루틴 완료율 평균 — shareRoutines가 켜져있고 오늘 예정 루틴이 있는 멤버만 집계한다. */
private fun averageTodayPercent(stats: List<SocialGroupSyncClient.MemberStats>): Int {
    val ratios = stats.filter { it.shareRoutines && it.routines.isNotEmpty() }
        .map { it.routines.count { r -> r.doneToday } * 100.0 / it.routines.size }
    if (ratios.isEmpty()) return 0
    return Math.round(ratios.average()).toInt()
}

/**
 * "모임" 탭 메인 — 내가 속한 모임 목록(이름/멤버수/오늘 평균 완료율)과 "모임 만들기"/"참여하기" 진입점.
 * 모임/멤버/통계는 로컬에 캐싱하지 않고 화면 진입 시마다 Firebase에서 직접 읽는다(DECISIONS.md 참고).
 */
@Composable
fun SocialGroupScreen(
    repository: Repository,
    onSelectGroup: (String) -> Unit,
    onOpenDm: (String, String, String) -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var summaries by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }
    // 92차 소셜 개편 Phase 2: 1:1 DM — 커스텀 아이디 전역 검색으로 시작(안드로이드판과 대칭).
    var dmChats by remember { mutableStateOf<List<com.phonelock.desktop.monitor.ChatSyncClient.DmChatPreview>>(emptyList()) }
    var showNewDmDialog by remember { mutableStateOf(false) }

    fun refresh() { refreshTrigger++ }

    fun reloadDmChats() {
        val url = repository.fbDatabaseUrl; val key = repository.fbApiKey
        Thread { dmChats = com.phonelock.desktop.monitor.ChatSyncClient.readMyDmChats(url, key) }.start()
    }

    LaunchedEffect(refreshTrigger) {
        val url = repository.fbDatabaseUrl
        val key = repository.fbApiKey
        if (!AuthManager.isSignedIn) {
            loading = false
            errorMsg = "먼저 설정 > 공통 탭에서 로그인을 해야 모임을 쓸 수 있습니다."
            return@LaunchedEffect
        }
        if (url.isNullOrBlank() || key.isNullOrBlank()) {
            loading = false
            errorMsg = "설정 > 공통 탭에서 Firebase 연결 설정을 먼저 채워주세요."
            return@LaunchedEffect
        }
        loading = true
        errorMsg = null
        reloadDmChats()
        Thread {
            val ids = SocialGroupSyncClient.readMyGroupIds(url, key)
            val result = ids.mapNotNull { id ->
                val info = SocialGroupSyncClient.readGroupInfo(url, key, id) ?: return@mapNotNull null
                val members = SocialGroupSyncClient.readGroupMembers(url, key, id)
                val stats = SocialGroupSyncClient.readGroupStats(url, key, id)
                GroupSummary(id, info.name, members.size, averageTodayPercent(stats))
            }
            summaries = result
            loading = false
        }.start()
    }

    if (showCreateDialog) {
        var nameText by remember { mutableStateOf("") }
        var creating by remember { mutableStateOf(false) }
        var createError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!creating) showCreateDialog = false },
            title = { Text("모임 만들기") },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameText, onValueChange = { nameText = it },
                        label = { Text("모임 이름") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    createError?.let {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !creating,
                    onClick = {
                        creating = true
                        createError = null
                        val url = repository.fbDatabaseUrl; val key = repository.fbApiKey
                        Thread {
                            val result = SocialGroupSyncClient.createGroup(url, key, nameText)
                            creating = false
                            result.onSuccess {
                                showCreateDialog = false
                                refresh()
                            }.onFailure { e -> createError = e.message ?: "모임 생성에 실패했습니다." }
                        }.start()
                    }
                ) { Text(if (creating) "만드는 중..." else "만들기") }
            },
            dismissButton = {
                TextButton(enabled = !creating, onClick = { showCreateDialog = false }) { Text("취소") }
            }
        )
    }

    if (showJoinDialog) {
        var codeText by remember { mutableStateOf("") }
        var joining by remember { mutableStateOf(false) }
        var joinError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!joining) showJoinDialog = false },
            title = { Text("모임 참여하기") },
            text = {
                Column {
                    OutlinedTextField(
                        value = codeText, onValueChange = { codeText = it.uppercase() },
                        label = { Text("초대 코드 (6자리)") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    joinError?.let {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !joining,
                    onClick = {
                        joining = true
                        joinError = null
                        val url = repository.fbDatabaseUrl; val key = repository.fbApiKey
                        Thread {
                            val result = SocialGroupSyncClient.joinGroupByCode(url, key, codeText)
                            joining = false
                            result.onSuccess {
                                showJoinDialog = false
                                refresh()
                            }.onFailure { e -> joinError = e.message ?: "참여에 실패했습니다." }
                        }.start()
                    }
                ) { Text(if (joining) "참여하는 중..." else "참여") }
            },
            dismissButton = {
                TextButton(enabled = !joining, onClick = { showJoinDialog = false }) { Text("취소") }
            }
        )
    }

    if (showNewDmDialog) {
        var codeText by remember { mutableStateOf("") }
        var searching by remember { mutableStateOf(false) }
        var searchError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!searching) showNewDmDialog = false },
            title = { Text("새 대화") },
            text = {
                Column {
                    Text("상대의 커스텀 아이디를 입력하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.xs))
                    OutlinedTextField(
                        value = codeText, onValueChange = { codeText = it; searchError = null },
                        label = { Text("커스텀 아이디") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    searchError?.let {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = codeText.isNotBlank() && !searching,
                    onClick = {
                        searching = true
                        searchError = null
                        val url = repository.fbDatabaseUrl; val key = repository.fbApiKey
                        Thread {
                            val found = com.phonelock.desktop.monitor.ChatSyncClient.searchUserByCode(url, key, codeText.trim())
                            if (found == null) {
                                searching = false
                                searchError = "찾을 수 없습니다."
                            } else {
                                val (otherUid, otherLabel) = found
                                val result = com.phonelock.desktop.monitor.ChatSyncClient.ensureDmChat(url, key, otherUid, otherLabel)
                                searching = false
                                result.onSuccess { chatId ->
                                    showNewDmDialog = false
                                    reloadDmChats()
                                    onOpenDm(chatId, otherUid, otherLabel)
                                }.onFailure { e -> searchError = e.message ?: "시작에 실패했습니다." }
                            }
                        }.start()
                    }
                ) { Text(if (searching) "찾는 중..." else "시작") }
            },
            dismissButton = { TextButton(enabled = !searching, onClick = { showNewDmDialog = false }) { Text("취소") } }
        )
    }

    Column(Modifier.fillMaxSize().background(socialGradientBackground()).verticalScroll(rememberScrollState()).padding(Spacing.md)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionPill("💬 1:1 대화")
            TextButton(onClick = { showNewDmDialog = true }) { Text("+ 새 대화") }
        }
        Spacer(Modifier.height(Spacing.sm))
        if (dmChats.isEmpty()) {
            Text(
                "아직 시작한 대화가 없습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            dmChats.forEach { dm ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.06f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)),
                    onClick = { onOpenDm(dm.chatId, dm.peerUid, dm.peerLabel) }
                ) {
                    Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        DmAvatar(dm.peerLabel)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(dm.peerLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.lg))
        SocialPointsSection(repository)
        Spacer(Modifier.height(Spacing.lg))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("👥 모임", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "함께 갓생 사는 사람들과 서로 진행 상황을 확인해요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                // 98차(사용자 요청, 안드로이드판은 당겨서 새로고침) — 데스크탑은 스와이프 제스처가 없어 버튼으로.
                androidx.compose.material3.IconButton(onClick = { refresh(); reloadDmChats() }) { Text("🔄") }
                OutlinedButton(onClick = { showJoinDialog = true }) { Text("참여하기") }
                Button(onClick = { showCreateDialog = true }) { Text("+ 모임 만들기") }
            }
        }
        Spacer(Modifier.height(Spacing.lg))

        errorMsg?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        if (loading) {
            Text("불러오는 중...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (summaries.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🌱", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "아직 속한 모임이 없습니다",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "새로 만들거나 초대 코드로 참여해보세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            summaries.forEach { g ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    onClick = { onSelectGroup(g.id) }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GroupAvatar(g.name)
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(g.name, style = MaterialTheme.typography.titleMedium)
                            Text("${g.memberCount}명", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(Spacing.xs))
                            LinearProgressIndicator(
                                progress = { g.avgTodayPercent / 100f },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                            )
                        }
                        Spacer(Modifier.width(Spacing.md))
                        // 92차 재디자인: 퍼센트를 알약 배지로(안드로이드판과 동일).
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SectionPill("${g.avgTodayPercent}%")
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "오늘 루틴 평균",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 포인트/보상 + 캐릭터 성장(102~103차, "루틴" 섹션에서 이동됨) — 사용자가 "소셜에 넣을 기능"이라고
 * 지정한 걸 문서화 없이 놓쳤던 걸 뒤늦게 바로잡음(103차 후속). 로직(적립 기준/원장 합산/8단계 성장)은
 * RoutineScreen.kt에 있던 것과 동일, 화면 위치와 카드 스타일만 소셜 화면에 맞춰 옮겼다. 데스크탑
 * Repository는 동기 호출이라 RoutinePointsTab과 같은 refreshTick 재조회 패턴을 쓴다.
 */
@Composable
private fun SocialPointsSection(repository: Repository) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val balance = remember(refreshTick) { repository.getPointsBalance() }
    val earnedTotal = remember(refreshTick) { repository.getEarnedPointsTotal() }
    val totalStudyMinutes = remember(refreshTick) { repository.getTotalStudyMinutes() }
    val rewards = remember(refreshTick) { repository.getRewards() }
    var showAddDialog by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf<String?>(null) }
    fun refresh() { refreshTick++ }

    SectionPill("🎁 포인트")
    Spacer(Modifier.height(Spacing.sm))

    val studyLevel = StudyLevel.levelFor(totalStudyMinutes)
    val studyLevelProgress = StudyLevel.progressToNext(totalStudyMinutes)
    val studyLevelMinutesLeft = StudyLevel.minutesToNextLevel(totalStudyMinutes)
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.sm)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Lv.$studyLevel ${StudyLevel.tierLabel(studyLevel)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("누적 공부 ${totalStudyMinutes / 60}시간 ${totalStudyMinutes % 60}분", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(Spacing.xs))
            LinearProgressIndicator(
                progress = { studyLevelProgress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(Spacing.xs))
            Text("다음 레벨까지 공부 ${studyLevelMinutesLeft}분 남음", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(Spacing.sm))

    val stage = CharacterGrowth.stageFor(earnedTotal)
    val progress = CharacterGrowth.progressToNext(earnedTotal)
    val toNext = CharacterGrowth.pointsToNextStage(earnedTotal)
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.md), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stage.emoji, fontSize = 56.sp)
            Spacer(Modifier.height(Spacing.xs))
            Text("${stage.label} (${stage.index + 1}/${CharacterGrowth.STAGES.size}단계)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Spacing.sm))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                if (toNext != null) "다음 단계까지 ${toNext}P 남음" else "최종 단계 도달!",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            Text("보유 ${balance}P", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
    Spacer(Modifier.height(Spacing.sm))
    Text(
        "공부 10분당 1P · 루틴 완료 5P · 일정 완료 5P · 오늘 루틴 전부 완료 시 +10P",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(Spacing.md))

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("오늘의 보상", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { showAddDialog = true }) { Text("+ 보상 추가") }
    }
    Spacer(Modifier.height(Spacing.xs))

    toastMessage?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.xs))
    }

    if (rewards.isEmpty()) {
        Text(
            "등록된 보상이 없습니다\n\"+ 보상 추가\"로 원하는 보상을 만들어보세요",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        rewards.forEach { reward ->
            Surface(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.06f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
            ) {
                Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(reward.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        Text("${reward.cost}P", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        enabled = balance >= reward.cost,
                        onClick = {
                            val ok = repository.redeemReward(reward.id)
                            toastMessage = if (ok) "\"${reward.name}\" 언락했습니다! 🎉" else "포인트가 부족합니다"
                            refresh()
                        }
                    ) { Text("언락") }
                    Spacer(Modifier.width(Spacing.xs))
                    TextButton(onClick = { repository.deleteReward(reward.id); refresh() }) { Text("삭제") }
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var costText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("보상 추가") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("보상 이름") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it.filter { c -> c.isDigit() } },
                        label = { Text("필요 포인트") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && (costText.toIntOrNull() ?: 0) > 0,
                    onClick = {
                        val cost = costText.toIntOrNull() ?: 0
                        repository.addReward(name.trim(), cost)
                        showAddDialog = false
                        refresh()
                    }
                ) { Text("추가") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("취소") } }
        )
    }
}
