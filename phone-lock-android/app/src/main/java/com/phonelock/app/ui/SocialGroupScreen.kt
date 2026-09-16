package com.phonelock.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.appendInlineContent
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.sp
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

/** [MemberDisplayName] 배지 알약의 안쪽 여백 — 배지 자리를 InlineTextContent로 미리 잡아줘야 해서
 *  글자 폭을 잰 뒤 이 값을 더한 크기를 Placeholder로 넘긴다. */
private val BADGE_H_PADDING = 8.dp
private val BADGE_V_PADDING = 2.dp
private const val BADGE_INLINE_ID = "levelTitleBadge"

/** 배지에 넣는 칭호의 글자 상한 — 고레벨 칭호는 "종말조차 외경하는 푸게의 존재"처럼 20자를 넘기도 해서
 *  ([com.phonelock.shared.GrowthSystem.STAGES]), 그대로 넣으면 배지 하나가 한 줄을 다 삼킨다. 배지는 생략해서
 *  보여주고, 칭호 전부는 모임원 상세 화면의 성장 카드가 그대로 보여준다. */
private const val BADGE_TITLE_MAX_CHARS = 10

/**
 * 레벨·칭호 배지와 닉네임을 **하나의 텍스트 흐름 안에** 함께 배치한다(122차, 사용자 요청).
 *
 * - 120차 이전: `Row { SectionPill("Lv.12 새싹"); Text(name) }` — 배지와 이름이 각자 고정 영역을 차지해서
 *   이름에 남는 폭이 좁아지고, 긴 닉네임이 "홍/길동/입니다"처럼 3~4줄로 쪼개졌다.
 * - 121차: 배지를 아예 없애고 칭호까지 전부 평범한 한 줄 텍스트로 합쳤다 — 줄바꿈 문제는 사라졌지만
 *   사용자가 원한 건 버튼형 배지를 유지하는 것이었다.
 * - 122차(지금): 배지는 알약 박스 그대로 두되, 그 박스를 닉네임과 **같은 [Text]** 안에
 *   [androidx.compose.foundation.text.InlineTextContent]로 끼워 넣는다. 줄바꿈/말줄임 계산을 Compose
 *   텍스트 레이아웃이 배지와 이름을 통째로 한 흐름으로 처리하므로 둘 사이에 고정 영역 분할도, 세로
 *   구분선도 없다 — "스타일은 분리하되 레이아웃 영역은 분리하지 않는다".
 *
 * 레벨/칭호 값은 상대가 "홈" 공유를 켠 경우에만 존재하고([SocialGroupSyncClient.MemberStats.plantLevel]/
 * [SocialGroupSyncClient.MemberStats.plantTitle]), 둘 다 없으면 배지 없이 닉네임만 그대로 나온다.
 */
@Composable
internal fun MemberDisplayName(
    title: String?,
    name: String,
    level: Int? = null,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    fontWeight: FontWeight? = null,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    badgeColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.tertiary,
    maxLines: Int = 2,
    modifier: Modifier = Modifier
) {
    val shortTitle = title?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (it.length > BADGE_TITLE_MAX_CHARS) it.take(BADGE_TITLE_MAX_CHARS - 1) + "…" else it
    }
    val badgeLabel = when {
        level != null && shortTitle != null -> "Lv.$level $shortTitle"
        level != null -> "Lv.$level"
        shortTitle != null -> shortTitle
        else -> null
    }
    if (badgeLabel == null) {
        Text(
            name,
            style = style,
            fontWeight = fontWeight,
            color = color,
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = modifier
        )
        return
    }

    val badgeTextStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = badgeColor)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Placeholder 크기는 sp 단위(글자 크기 배율을 따라감)로 미리 알려줘야 하므로 배지 글자를 실제로 재서
    // 좌우/상하 여백을 더한 값을 넘긴다 — 글자 폭에 딱 맞으므로 긴 칭호에도 배지가 잘리지 않는다.
    val badgeSize = remember(badgeLabel, badgeTextStyle, density) {
        val measured = measurer.measure(badgeLabel, badgeTextStyle)
        with(density) {
            (measured.size.width + (BADGE_H_PADDING * 2).toPx()).toSp() to
                (measured.size.height + (BADGE_V_PADDING * 2).toPx()).toSp()
        }
    }

    val text = androidx.compose.ui.text.buildAnnotatedString {
        // 두 번째 인자는 InlineTextContent를 못 찾았을 때의 대체 텍스트 — 배지 문구 그대로 두면
        // 최악의 경우에도 레벨/칭호 정보 자체는 남는다.
        appendInlineContent(BADGE_INLINE_ID, badgeLabel)
        append(" ")
        append(name)
    }
    Text(
        text,
        style = style,
        fontWeight = fontWeight,
        color = color,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        inlineContent = mapOf(
            BADGE_INLINE_ID to androidx.compose.foundation.text.InlineTextContent(
                androidx.compose.ui.text.Placeholder(
                    width = badgeSize.first,
                    height = badgeSize.second,
                    placeholderVerticalAlign = androidx.compose.ui.text.PlaceholderVerticalAlign.Center
                )
            ) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(badgeColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(badgeLabel, style = badgeTextStyle, maxLines = 1)
                }
            }
        ),
        modifier = modifier
    )
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
fun SocialGroupScreen(
    repository: PhoneLockRepository,
    onOpenGroup: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var summaries by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(Unit) { reload() }

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

    Scaffold(topBar = { TopAppBar(title = { Text("👥 모임") }) }) { padding ->
        // 98차(사용자 요청): 당겨서 새로고침 — 서버 최신 상태를 다시 받아온다.
        com.phonelock.app.ui.components.PullToRefreshBox(onRefresh = { reload() }) {
        // 106차(사용자 요청): 소셜 탭 메인 화면 전체 스크롤 — 예전엔 아래 모임 목록만 LazyColumn으로
        // 자체 스크롤하고 위쪽(1:1 대화 목록+헤더)은 스크롤 밖이라, 모임/대화가 많으면 화면 위쪽이
        // 잘려 안 보였다. 전체를 하나의 verticalScroll Column으로 통일.
        Column(
            Modifier.fillMaxSize().background(socialGradientBackground()).padding(padding)
                .verticalScroll(rememberScrollState()).padding(Spacing.md)
        ) {
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
                loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                summaries.isEmpty() -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
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
                else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    summaries.forEach { s ->
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
}
