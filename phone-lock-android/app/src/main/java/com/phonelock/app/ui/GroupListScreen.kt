package com.phonelock.app.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phonelock.shared.PERSUASION_MESSAGES
import com.phonelock.shared.randomPersuasionStepDelaysMs
import com.phonelock.app.data.AppGroup
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.syncGroupSettingsFromFirebase
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
            Text(
                "🗂️ 차단 규칙",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
            )
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
                        }
                    )
                }
                }
            }
        }
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
    onCancelPending: () -> Unit
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
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(Spacing.xs))
                    // 이 화면에서 사용자가 가장 먼저 알고 싶은 건 "지금 이 그룹이 실제로 걸려 있는가"인데,
                    // 예전엔 그 상태가 가장 작고(labelSmall) 가장 흐린(onSurfaceVariant) 텍스트라 그룹
                    // 이름에 완전히 묻혔다 — 상태별 색 배지로 올려 시각적 우선순위를 바로잡는다.
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
                Switch(checked = group.groupEnabled, onCheckedChange = onGroupToggle)
            }
            if (!pending && group.groupEnabled && group.snoozeEnabled && (restrictingNow || snoozeActive)) {
                Spacer(Modifier.height(Spacing.xs))
                OutlinedButton(onClick = onSnooze, enabled = !snoozeActive && snoozeRemainingToday > 0) {
                    Text(if (snoozeActive) "😴 잠깐 풀기 중" else "😴 잠깐 풀기 ${group.snoozeMinutes}분 ($snoozeRemainingToday/${group.snoozeDailyLimit})")
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
