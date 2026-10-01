package com.phonelock.app.ui

import com.phonelock.app.ui.components.LedgerAlertDialog
import androidx.compose.foundation.layout.widthIn
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phonelock.shared.PERSUASION_MESSAGES
import com.phonelock.shared.randomPersuasionStepDelaysMs
import com.phonelock.app.data.AppGroup
import com.phonelock.app.data.ImportableGroupSetting
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.applyGroupSettingsJson
import com.phonelock.app.data.fetchImportableGroupSettings
import com.phonelock.app.data.findRemoteGroupSettingByName
import com.phonelock.app.data.importGroupSetting
import com.phonelock.app.data.isEffectivelyOffline
import com.phonelock.app.data.syncGroupSettingsFromFirebase
import org.json.JSONObject
import com.phonelock.app.service.AccessibilityServiceChecker
import com.phonelock.app.service.LockEvaluator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import com.phonelock.app.ui.components.BigNumber
import com.phonelock.app.ui.components.Hairline
import com.phonelock.app.ui.components.NoticeStrip
import com.phonelock.app.ui.components.Overline
import com.phonelock.app.ui.theme.LocalPhoneLockPalette
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 태블릿 무대응(의도적 판단, 84차): 데스크탑판 GroupListScreen.kt 자체도 리스트 하나뿐인 동일한
// Column/LazyColumn 레이아웃이고, 데스크탑에서 보이는 좌(목록)/우(편집) 마스터-디테일 분할은 이 파일이
// 아니라 한 단계 위(DesktopApp.kt MainScreen)에서 selectedGroupId로 조립된다. 안드로이드 쪽의 대응되는
// 상위 조립(MainActivity.kt NavigationRail + group_edit 네비게이션)은 83차에서 이미 처리됐고, 이 화면
// 자체엔 desktop 대비 추가할 좌우 분할이 없다.
@Composable
fun GroupListScreen(
    repository: PhoneLockRepository,
    onEditGroup: (Long?) -> Unit
) {
    val context = LocalContext.current
    val groups by repository.observeGroups().collectAsState(initial = emptyList())
    val evaluator = remember { LockEvaluator(repository) }
    val scope = rememberCoroutineScope()

    // 그룹 탭 진입 시 1회 그룹 설정(제어할 앱/사이트·groupEnabled 등 제외) 동기화 — RoutineScreen의
    // syncRoutinesFromFirebase() 진입 시 호출과 동일 패턴(87차+). observeGroups()가 Flow라 동기화로
    // Room이 갱신되면 화면도 자동으로 다시 그려진다.
    LaunchedEffect(Unit) {
        // 98차(온라인/오프라인 모드): 오프라인이면 네트워크 타임아웃만 기다리게 되므로 아예 건너뛴다.
        if (!repository.isEffectivelyOffline()) repository.syncGroupSettingsFromFirebase()
    }

    var showImportDialog by remember { mutableStateOf(false) }
    // 동기화 on/off 켜기 시 이름 충돌 확인(94차 신규, 95차에 편집 화면에서 목록 화면으로 이동) — 켜려는
    // 그룹과, 이름이 일치한 원격 항목을 함께 들고 있다가 다이얼로그에서 예/아니오로 처리한다.
    var pendingSyncToggleGroup by remember { mutableStateOf<AppGroup?>(null) }
    var pendingSyncToggleEntry by remember { mutableStateOf<JSONObject?>(null) }

    var accessibilityEnabled by remember { mutableStateOf(AccessibilityServiceChecker.isEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = AccessibilityServiceChecker.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEditGroup(null) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("규칙 추가", maxLines = 1, softWrap = false) }
            )
        }
    ) { padding ->
        // 98차(사용자 요청): 당겨서 새로고침 — 서버 최신 상태를 다시 받아온다(groups 자체는 Flow라 자동 갱신).
        com.phonelock.app.ui.components.PullToRefreshBox(onRefresh = {
            if (!repository.isEffectivelyOffline()) repository.syncGroupSettingsFromFirebase()
        }) {
        if (showImportDialog) {
            GroupImportDialog(
                repository = repository,
                onDismiss = { showImportDialog = false }
            )
        }
        // 144차: 화면 제목은 관리 탭 머리가 이미 보여주므로 여기선 "지금 차단 중인 규칙 수"를 크게, 나머지는 가는 선 목록으로.
        val lockedCount = groups.count { it.groupEnabled && evaluator.isAnyManagementActiveToday(it) }
        LazyColumn(
            Modifier.fillMaxSize().widthIn(max = 760.dp).padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.md, bottom = 96.dp)
        ) {
            item(key = "hero") {
                Column {
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
                        TextButton(onClick = { showImportDialog = true }) {
                            Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("불러오기", maxLines = 1, softWrap = false)
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                    // 접근성 서비스는 그룹 차단뿐 아니라 릴스/쇼츠 감지·집중 잠금 등 그룹 개수와 무관한 기능도
                    // 전부 이 서비스로 동작하므로, 그룹이 0개라 아래가 빈 화면이어도 경고는 항상 보여야 한다.
                    if (!accessibilityEnabled) {
                        NoticeStrip(
                            "접근성 서비스가 꺼져 있어 지금 어떤 앱·사이트도 차단되지 않습니다",
                            actionLabel = "켜기",
                            onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        )
                        Spacer(Modifier.height(Spacing.md))
                    }
                    Hairline()
                }
            }
            if (groups.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xl)) {
                        Text("아직 차단 규칙이 없습니다", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "막고 싶은 앱·사이트를 고르고 언제(시간대·하루 한도·열기 전 확인) 막을지 정합니다. 아래 \"규칙 추가\"로 시작하세요.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(groups, key = { it.id }) { group ->
                    GroupRow(
                        group = group,
                        restrictingNow = evaluator.isAnyManagementActiveToday(group),
                        snoozeActive = evaluator.isSnoozeActive(group),
                        snoozeRemainingToday = repository.snoozeRemainingToday(group),
                        onClick = { onEditGroup(group.id) },
                        onSnooze = {
                            scope.launch {
                                if (!repository.snoozeGroup(group.id)) {
                                    // 하루 한도는 그룹마다 다르게 설정할 수 있으므로(87차 snoozeDailyLimit)
                                    // 안내 문구도 하드코딩("하루 3회") 대신 실제 설정값을 보여준다.
                                    Toast.makeText(
                                        context,
                                        "\"${group.name}\" 차단 규칙은 오늘 잠깐 풀기를 이미 다 썼습니다(하루 ${group.snoozeDailyLimit}회).",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        onGroupToggle = { newValue ->
                            val turningOff = group.groupEnabled && !newValue
                            val currentlyRestricting = evaluator.isAnyManagementActiveToday(group)
                            val exempt = evaluator.isWithinEditExemptionWindow()
                            if (turningOff && currentlyRestricting && !exempt) {
                                // 지금 뭔가(스케줄/일일한도/실행확인) 걸려있는 도중이므로 즉시 끄지 못하게 하고
                                // 회유 멘트를 하나씩 확인해야 진행되는 절차를 건다.
                                repository.updateGroupFireAndForget(
                                    group.copy(groupEnabled = true, groupOffPending = true, groupOffMessageIndex = 0)
                                )
                                Toast.makeText(
                                    context,
                                    "지금 이 차단 규칙이 차단 중이라 바로 끌 수 없습니다. 확인 질문 20개에 하나씩 \"예\"를 눌러야 꺼집니다.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                repository.updateGroupFireAndForget(
                                    group.copy(groupEnabled = newValue, groupOffPending = false, groupOffMessageIndex = 0)
                                )
                            }
                        },
                        onConfirmMessage = {
                            if (group.groupOffMessageIndex >= PERSUASION_MESSAGES.lastIndex) {
                                repository.updateGroupFireAndForget(
                                    group.copy(groupEnabled = false, groupOffPending = false, groupOffMessageIndex = 0)
                                )
                            } else {
                                repository.updateGroupFireAndForget(
                                    group.copy(groupOffMessageIndex = group.groupOffMessageIndex + 1)
                                )
                            }
                        },
                        onCancelPending = {
                            repository.updateGroupFireAndForget(
                                group.copy(groupEnabled = true, groupOffPending = false, groupOffMessageIndex = 0)
                            )
                        },
                        onSyncToggle = { newValue ->
                            if (newValue) {
                                scope.launch {
                                    val remoteMatch = repository.findRemoteGroupSettingByName(group.name)
                                    if (remoteMatch != null) {
                                        pendingSyncToggleGroup = group
                                        pendingSyncToggleEntry = remoteMatch
                                    } else {
                                        repository.updateGroupFireAndForget(group.copy(syncEnabled = true))
                                    }
                                }
                            } else {
                                repository.updateGroupFireAndForget(group.copy(syncEnabled = false))
                            }
                        }
                    )
                    Hairline()
                }
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
                    repository.updateGroupFireAndForget(updated)
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
 * 차단 규칙 한 줄 — 144차: 카드 대신 줄(아래 가는 선은 호출 쪽). 왼쪽 자물쇠는 "지금 차단 중인가"(강조색=차단 중),
 * 이름 아래 잠깐 풀기, 오른쪽에 동기화 표시와 켜짐 스위치. 꺼진 규칙은 줄 전체를 흐리게(95차) — 테마 바탕이 비쳐
 * 어떤 테마에서도 "흐려진" 색이 저절로 나온다. 끄기 확인(회유 멘트 20개) 절차는 줄 아래에 펼쳐진다.
 */
@Composable
private fun GroupRow(
    group: AppGroup,
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
    val palette = LocalPhoneLockPalette.current

    // 화면을 벗어나면(다른 앱으로 전환 등) 진행 중이던 끄기 시도를 취소하고 원래 상태(켜짐)로 되돌린다.
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> isResumed = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(group.id, pending, isResumed) {
        if (pending && !isResumed) {
            onCancelPending()
        }
    }

    val locked = restrictingNow
    val showSnoozeChip = !pending && group.groupEnabled && group.snoozeEnabled && (restrictingNow || snoozeActive)
    val snoozeClickable = !snoozeActive && snoozeRemainingToday > 0
    val snoozeText = if (snoozeActive) "잠깐 풀기 중" else "잠깐 풀기 ${group.snoozeMinutes}분 · $snoozeRemainingToday/${group.snoozeDailyLimit}회 남음"

    Column(
        Modifier.fillMaxWidth()
            .alpha(if (group.groupEnabled) 1f else 0.5f)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
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
            Column(
                Modifier.fillMaxWidth().padding(start = 50.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(Spacing.md)
            ) {
                Overline("끄기 확인 ${messageIndex + 1}/${PERSUASION_MESSAGES.size}")
                Spacer(Modifier.height(4.dp))
                Text(PERSUASION_MESSAGES[messageIndex], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = onCancelPending) { Text("취소", maxLines = 1, softWrap = false) }
                    Button(onClick = { stepStarted = true }, enabled = !stepStarted) {
                        val label = if (isLast) "끄기" else "예"
                        Text(if (stepStarted && stepRemainingSeconds > 0) "$label (${stepRemainingSeconds}초)" else label, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/**
 * "불러오기" 화면(94차 신규) — 원격에 있고 이 기기엔 아직 동기화로 연결 안 된 차단 규칙 이름들을
 * 보여주고, 고른 것만 로컬로 불러온다(같은 이름의 로컬 규칙이 있으면 설정만 덮어쓰고 동기화를 켠다).
 * 자동으로 전부 병합하던 기존 방식(88차)을 사용자 요청으로 opt-in 방식으로 바꾼 핵심 화면.
 */
@Composable
private fun GroupImportDialog(repository: PhoneLockRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf(listOf<ImportableGroupSetting>()) }
    var importedNames by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        items = repository.fetchImportableGroupSettings()
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
                                            repository.importGroupSetting(item.json)
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
