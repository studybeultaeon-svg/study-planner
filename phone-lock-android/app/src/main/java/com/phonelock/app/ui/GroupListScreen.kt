package com.phonelock.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.phonelock.app.data.syncGroupSettingsFromFirebase
import org.json.JSONObject
import com.phonelock.app.service.AccessibilityServiceChecker
import com.phonelock.app.service.LockEvaluator
import com.phonelock.app.ui.components.formatHms
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
        repository.syncGroupSettingsFromFirebase()
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
        floatingActionButton = {
            FloatingActionButton(onClick = { onEditGroup(null) }) {
                Icon(Icons.Filled.Add, contentDescription = "차단 규칙 추가")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🗂️ 차단 규칙",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showImportDialog = true }) {
                    Text("⬇ 불러오기")
                }
            }
            if (showImportDialog) {
                GroupImportDialog(
                    repository = repository,
                    onDismiss = { showImportDialog = false }
                )
            }
            // 접근성 서비스는 그룹 차단뿐 아니라 릴스/쇼츠 감지·공부 잠금 등 그룹 개수와 무관한 기능도
            // 전부 이 서비스로 동작하므로, 그룹이 0개라 아래가 빈 화면이어도 경고는 항상 보여야 한다.
            if (!accessibilityEnabled) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(Modifier.padding(Spacing.md)) {
                        Text(
                            "⚠ 접근성 서비스가 꺼져 있습니다 — 지금 어떤 앱/사이트도 차단되지 않습니다",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Button(
                            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("설정에서 켜기")
                        }
                    }
                }
            }
            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("아직 차단 규칙이 없습니다. + 버튼으로 추가하세요.")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = Spacing.md)) {
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

    Surface(
        // 꺼진 차단 규칙은 줄 전체를 흐리게 해서 한눈에 "꺼져 있다"가 보이게 한다(95차, 사용자 요청).
        // 고정 회색을 새로 칠하는 대신 Modifier.alpha로 카드 전체(배경+글자+아이콘)를 낮은 불투명도로
        // 내려서 지금 테마(라이트/다크/커스텀 무엇이든) 배경이 그대로 비쳐 보이게 한다 — 항상 테마에
        // 맞는 "흐려진" 색이 저절로 나온다.
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)
            .alpha(if (group.groupEnabled) 1f else 0.55f),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Column(Modifier.padding(Spacing.md)) {
            // 이름+배지는 고정폭(더 이상 weight로 넓게 안 잡음), 잠깐 풀기 버튼은 그 오른쪽 남는 여백
            // 안에서 왼쪽 붙여 배치, 동기화 칩은 켜짐/꺼짐 Switch 바로 옆(맨 오른쪽)에 배치한다(95차,
            // 사용자 확정 — "동기화는 on/off 옆에, 잠깐 풀기는 여백 왼쪽에").
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    // 이 화면에서 사용자가 가장 먼저 알고 싶은 건 "지금 이 그룹이 실제로 걸려 있는가"인데,
                    // 예전엔 그 상태가 가장 작고(labelSmall) 가장 흐린(onSurfaceVariant) 텍스트라 그룹
                    // 이름에 완전히 묻혔다 — 상태별 색 배지로 올려 시각적 우선순위를 바로잡는다.
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Spacing.xs))
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
                Spacer(Modifier.width(Spacing.sm))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (!pending && group.groupEnabled && group.snoozeEnabled && (restrictingNow || snoozeActive)) {
                        OutlinedButton(onClick = onSnooze, enabled = !snoozeActive && snoozeRemainingToday > 0) {
                            Text(if (snoozeActive) "😴 잠깐 풀기 중" else "😴 잠깐 풀기 ${group.snoozeMinutes}분 ($snoozeRemainingToday/${group.snoozeDailyLimit})")
                        }
                    }
                }
                // 칩 모양은 공부앱 캘린더의 "N회독" 토글(색 배경 알약+굵은 글씨, CalendarScreen.kt
                // 참고)과 같은 스타일이되, 이모지는 "🔁"(N회독)과 헷갈리지 않는 "☁️"(클라우드 동기화)를 쓴다.
                Text(
                    if (group.syncEnabled) "☁️동기화 ON" else "☁️동기화 OFF",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (group.syncEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            (if (group.syncEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.15f),
                            RoundedCornerShape(50)
                        )
                        .clickable { onSyncToggle(!group.syncEnabled) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Switch(checked = group.groupEnabled, onCheckedChange = onGroupToggle)
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
                Spacer(Modifier.height(Spacing.xs))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                group.dailyLimitSeconds?.let {
                    Text("일일 한도 ${formatHms(it)}", style = MaterialTheme.typography.bodySmall)
                }
                if (group.scheduleStartMinute != null && group.scheduleEndMinute != null) {
                    val startH = group.scheduleStartMinute / 60
                    val startM = group.scheduleStartMinute % 60
                    val endH = group.scheduleEndMinute / 60
                    val endM = group.scheduleEndMinute % 60
                    Text(
                        "%02d:%02d~%02d:%02d 차단".format(startH, startM, endH, endM),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // 데스크탑판 GroupRow엔 있었는데 안드로이드에만 빠져 있던 항목 — 어떤 관리 종류가
                // 켜져 있는지 목록에서 바로 알 수 있어야 편집 화면까지 들어가 보지 않는다.
                if (group.confirmEnabled) {
                    Text("실행 전 대기", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// 상태 배지 색 — DECISIONS.md 85차의 상태 3색(성공/경고/실패)을 그대로 재사용한다.
private val STATUS_OK = Color(0xFF34D399)
private val STATUS_WARNING = Color(0xFFFBBF24)
private val STATUS_DANGER = Color(0xFFF87171)

/** 그룹의 현재 상태를 한눈에 알리는 작은 알약 배지(DECISIONS.md 85차 "섹션 헤더 알약" 패턴 재사용). */
@Composable
private fun GroupStatusBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
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
