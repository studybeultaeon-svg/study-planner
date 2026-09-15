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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.getEarnedPointsTotal
import com.phonelock.desktop.data.getTotalStudyMinutes
import com.phonelock.desktop.data.getPointsBalance
import com.phonelock.desktop.monitor.AuthManager
import com.phonelock.desktop.monitor.SocialGroupSyncClient
import com.phonelock.desktop.ui.theme.Spacing

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

/**
 * 칭호 + 닉네임을 "새싹 홍길동"처럼 **한 덩어리의 텍스트**로 보여준다(121차, 사용자 요청).
 *
 * 그전까지는 칭호를 [SectionPill] 알약 배지로 따로 띄우고 그 옆에 닉네임 Text를 두는 2박스 구조였는데,
 * 안드로이드에서 칭호·닉네임이 둘 다 길면 같은 Row 안에서 서로 밀어내며 뭉개져 읽을 수 없었다. 배지를
 * 없애고 하나의 [Text]로 합치면 줄바꿈/말줄임을 Compose 텍스트 레이아웃이 통째로 계산하므로 길이가
 * 얼마든 겹치거나 잘리지 않는다. 칭호 부분만 색/굵기를 달리해 여전히 구분된다.
 *
 * 칭호 값은 상대가 "홈" 공유를 켠 경우에만 존재하고([SocialGroupSyncClient.MemberStats.plantTitle]),
 * 없으면 닉네임만 그대로 나온다. 레벨 숫자는 이름 줄을 길게 만들어 이 결합 표기에서는 빼고,
 * 멤버 상세 화면의 성장 카드(이미 "Lv.N"을 크게 보여주는 자리)에만 남긴다.
 */
@Composable
internal fun MemberDisplayName(
    title: String?,
    name: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    fontWeight: FontWeight? = null,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.tertiary,
    maxLines: Int = 2,
    modifier: Modifier = Modifier
) {
    val text = androidx.compose.ui.text.buildAnnotatedString {
        if (!title.isNullOrBlank()) {
            withStyle(androidx.compose.ui.text.SpanStyle(color = titleColor, fontWeight = FontWeight.Bold)) {
                append(title.trim())
            }
            append(" ")
        }
        append(name)
    }
    Text(
        text,
        style = style,
        fontWeight = fontWeight,
        color = color,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier
    )
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
    onSelectGroup: (String) -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var summaries by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }

    fun refresh() { refreshTrigger++ }

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

    Column(Modifier.fillMaxSize().background(socialGradientBackground()).verticalScroll(rememberScrollState()).padding(Spacing.md)) {
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
                androidx.compose.material3.IconButton(onClick = { refresh() }) { Text("🔄") }
                OutlinedButton(onClick = { showJoinDialog = true }) { Text("참여하기") }
                Button(onClick = { showCreateDialog = true }) { Text("+ 모임 만들기") }
            }
        }
        Spacer(Modifier.height(Spacing.lg))

        val err = errorMsg
        if (err != null) {
            Text(err, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (loading) {
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
