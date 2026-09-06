package com.phonelock.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.*
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.launch

private data class GroupSummary(val id: String, val name: String, val memberCount: Int, val avgTodayRate: Int)

/** 소셜 화면 배경(사용자 지적으로 재디자인) — 처음엔 StudyLockActivity와 같은 `Brush.radialGradient`를
 *  그대로 썼는데, 중앙에 빛나는 원 모양은 잠금 화면의 원형 진행률 링과 짝을 이루는 디자인이라 링이 없는
 *  리스트 화면(소셜)에 그대로 가져오면 정체불명의 얼룩처럼 보인다는 지적을 받았다 — 잠금 화면 쪽은
 *  그대로 두고, 소셜 쪽만 위에서 아래로 은은하게 옅어지는 리니어 그라디언트(메신저 앱들의 상단 배너
 *  톤에 가까움)로 교체해 원형 "빛나는 점" 인상 자체를 없앴다. */
@Composable
internal fun socialGradientBackground() = Brush.verticalGradient(
    colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), MaterialTheme.colorScheme.background)
)

/** 섹션 제목 옆에 붙는 작은 pill 라벨(StudyTimerScreen의 PomoPhaseBadge와 같은 알약 배지 언어). */
@Composable
internal fun SectionPill(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

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

/** 모임 이름 첫 글자를 원형 배지로(데스크탑판 GroupAvatar와 대칭). */
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

/**
 * "모임" 탭 메인 화면 — 내가 속한 모임 목록(이름/멤버수/오늘 완료율 평균)과 만들기/참여하기 진입점.
 * 계획 문서(dynamic-shimmying-map.md) 참고 — groups/{id} 데이터는 로컬에 캐싱하지 않고 진입할 때마다
 * Firebase에서 직접 읽는다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialGroupScreen(repository: PhoneLockRepository, onOpenGroup: (String) -> Unit, onOpenDm: (String, String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var summaries by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // 92차 소셜 개편 Phase 2: 1:1 DM — 커스텀 아이디 전역 검색으로 시작.
    var dmChats by remember { mutableStateOf<List<com.phonelock.app.service.ChatSyncClient.DmChatPreview>>(emptyList()) }
    var showNewDmDialog by remember { mutableStateOf(false) }

    fun reload() {
        loading = true
        scope.launch {
            val ids = repository.readMySocialGroupIds()
            summaries = ids.mapNotNull { id ->
                val info = repository.readSocialGroupInfo(id) ?: return@mapNotNull null
                val members = repository.readSocialGroupMembers(id)
                val stats = repository.readSocialGroupStats(id)
                val rates = stats.filter { it.shareRoutines }.map { s ->
                    val total = s.routines?.size ?: 0
                    val done = s.routines?.count { it.doneToday } ?: 0
                    if (total > 0) done * 100 / total else 0
                }
                val avg = if (rates.isNotEmpty()) rates.sum() / rates.size else 0
                GroupSummary(id, info.name, members.size, avg)
            }
            loading = false
        }
    }

    fun reloadDmChats() {
        scope.launch { dmChats = repository.readMyDmChats() }
    }

    LaunchedEffect(Unit) { reload(); reloadDmChats() }

    if (showNewDmDialog) {
        var codeText by remember { mutableStateOf("") }
        var searchError by remember { mutableStateOf<String?>(null) }
        var searching by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showNewDmDialog = false },
            title = { Text("새 대화") },
            text = {
                Column {
                    Text("상대의 커스텀 아이디를 입력하세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Spacing.xs))
                    OutlinedTextField(
                        value = codeText,
                        onValueChange = { codeText = it; searchError = null },
                        label = { Text("커스텀 아이디") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    searchError?.let {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = codeText.isNotBlank() && !searching,
                    onClick = {
                        searching = true
                        scope.launch {
                            val found = repository.searchDmUserByCode(codeText.trim())
                            if (found == null) {
                                searchError = "찾을 수 없습니다."
                                searching = false
                            } else {
                                val (otherUid, otherLabel) = found
                                val result = repository.ensureDmChat(otherUid, otherLabel)
                                searching = false
                                result.onSuccess { chatId ->
                                    showNewDmDialog = false
                                    reloadDmChats()
                                    onOpenDm(chatId, otherUid, otherLabel)
                                }
                                result.onFailure { e -> searchError = e.message ?: "시작에 실패했습니다." }
                            }
                        }
                    }
                ) { Text("시작") }
            },
            dismissButton = { TextButton(onClick = { showNewDmDialog = false }) { Text("취소") } }
        )
    }

    if (showCreateDialog) {
        var nameText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("모임 만들기") },
            text = {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text("모임 이름") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = nameText.trim()
                    if (name.isNotEmpty()) {
                        showCreateDialog = false
                        scope.launch {
                            val result = repository.createSocialGroup(name)
                            result.onFailure { e -> errorMessage = e.message ?: "모임 생성에 실패했습니다." }
                            result.onSuccess { reload() }
                        }
                    }
                }) { Text("만들기") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("취소") } }
        )
    }

    if (showJoinDialog) {
        var codeText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text("모임 참여하기") },
            text = {
                OutlinedTextField(
                    value = codeText,
                    onValueChange = { codeText = it },
                    label = { Text("초대 코드 (6자리)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val code = codeText.trim()
                    if (code.isNotEmpty()) {
                        showJoinDialog = false
                        scope.launch {
                            val result = repository.joinSocialGroup(code)
                            result.onFailure { e -> errorMessage = e.message ?: "참여에 실패했습니다." }
                            result.onSuccess { reload() }
                        }
                    }
                }) { Text("참여") }
            },
            dismissButton = { TextButton(onClick = { showJoinDialog = false }) { Text("취소") } }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("👥 소셜") }) }) { padding ->
        Column(Modifier.fillMaxSize().background(socialGradientBackground()).padding(padding).padding(Spacing.md)) {
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
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    dmChats.forEach { dm ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenDm(dm.chatId, dm.peerUid, dm.peerLabel) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.06f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
                        ) {
                            Row(Modifier.fillMaxWidth().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                DmAvatar(dm.peerLabel)
                                Spacer(Modifier.width(Spacing.sm))
                                Text(dm.peerLabel, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            SectionPill("👥 모임")
            Spacer(Modifier.height(Spacing.sm))
            if (com.phonelock.app.ui.components.isTabletWidth()) {
                // 84차: 데스크탑판 SocialGroupScreen.kt처럼 부제와 버튼을 한 줄에 SpaceBetween으로 —
                // 폰처럼 버튼을 꽉 채운 두 줄로 쌓지 않고 넓은 화면을 가로로 활용한다.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "함께 갓생 사는 사람들과 서로 진행 상황을 확인해요",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        androidx.compose.material3.OutlinedButton(onClick = { showJoinDialog = true }) { Text("참여하기") }
                        Button(onClick = { showCreateDialog = true }) { Text("+ 모임 만들기") }
                    }
                }
            } else {
                Text(
                    "함께 갓생 사는 사람들과 서로 진행 상황을 확인해요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(onClick = { showCreateDialog = true }, modifier = Modifier.weight(1f)) { Text("+ 모임 만들기") }
                    Button(onClick = { showJoinDialog = true }, modifier = Modifier.weight(1f)) { Text("참여하기") }
                }
            }
            Spacer(Modifier.height(Spacing.md))
            errorMessage?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(Spacing.sm))
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                summaries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🌱", style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(Spacing.sm))
                        Text("아직 속한 모임이 없습니다", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "새로 만들거나 초대 코드로 참여해보세요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(summaries) { s ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenGroup(s.id) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        ) {
                            Row(Modifier.fillMaxWidth().padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                                GroupAvatar(s.name)
                                Spacer(Modifier.width(Spacing.md))
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${s.memberCount}명", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(Spacing.xs))
                                    LinearProgressIndicator(
                                        progress = { s.avgTodayRate / 100f },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                    )
                                }
                                Spacer(Modifier.width(Spacing.md))
                                // 예전엔 진행바 옆에 숫자만 덩그러니 있어 무엇의 퍼센트인지 알 수 없었다 —
                                // 92차 재디자인: 그 숫자를 알약 배지로(StudyTimerScreen PomoPhaseBadge 언어 재사용).
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
                                        Text(
                                            "${s.avgTodayRate}%",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                        )
                                    }
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
    }
}
