package com.phonelock.app.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.isEffectivelyOffline
import com.phonelock.app.data.releaseLockTimer
import com.phonelock.app.data.startLockTimer
import com.phonelock.app.data.syncLockTimer
import com.phonelock.app.service.AccessibilityServiceChecker
import com.phonelock.app.ui.components.NumberStepperField
import com.phonelock.app.ui.components.PersuasionStepper
import com.phonelock.app.ui.components.SectionCard
import androidx.compose.ui.unit.sp
import com.phonelock.app.ui.components.FitText
import com.phonelock.app.ui.components.LiveDot
import com.phonelock.app.ui.components.NoticeStrip
import com.phonelock.app.ui.components.Overline
import com.phonelock.app.ui.components.ProgressLine
import com.phonelock.app.ui.theme.LocalPhoneLockPalette
import com.phonelock.app.ui.theme.Spacing
import com.phonelock.shared.PERSUASION_MESSAGES
import com.phonelock.shared.lock.LockTimer
import com.phonelock.shared.lock.LockTimerPreset
import com.phonelock.shared.lock.UnlockLevel
import com.phonelock.shared.lock.formatLockRemaining
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 자주 쓰는 시간을 한 번에 넣는 칩 — (표시, 분). */
private val FREE_MINUTE_CHIPS = listOf("바로 잠금" to 0, "5분" to 5, "10분" to 10, "30분" to 30, "1시간" to 60)
private val LOCK_MINUTE_CHIPS = listOf("30분" to 30, "1시간" to 60, "2시간" to 120, "4시간" to 240)

/**
 * 관리 > 타이머 — "이거까지만 할게요!"(142차, 사용자 요청).
 *
 * "앞으로 N분만 더 쓰고, 그 뒤 M분은 잠근다"를 스스로 약속하는 화면이다. N을 0으로 두면 누르는 즉시 잠기므로
 * "지금부터 M분 잠금"도 여기서 한다. 잠그는 범위는 기기 전체(허용한 앱만 사용) 또는 고른 앱·사이트만이고,
 * 시작할 때 고른 난이도([UnlockLevel])의 절차를 거쳐야만 도중에 풀 수 있다 — 자유 시간 중의 취소도 같다.
 *
 * 실제로 막는 일은 접근성 서비스([com.phonelock.app.service.AppMonitorAccessibilityService])가 하고, 이 화면은
 * 약속을 만들고([AppPreferences.lockTimer]) 남은 시간을 보여주고 해제 절차를 진행할 뿐이다.
 */
@Composable
fun LockTimerScreen(repository: PhoneLockRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context) }
    var timer by remember { mutableStateOf(prefs.lockTimer) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var startNotice by remember { mutableStateOf<String?>(null) }

    // 143차: 이 화면이 열려 있는 동안 다른 기기의 약속을 받아오고(없으면 새로 걸 수 있게) 풀린 약속을 반영한다.
    // 위 1초 루프가 저장값을 다시 읽으므로 여기서는 맞추기만 한다.
    LaunchedEffect(Unit) {
        while (true) {
            if (!repository.isEffectivelyOffline()) repository.syncLockTimer()
            delay(10_000)
        }
    }

    // 남은 시간은 매초 다시 그리고, 끝난 약속은 여기서도 지운다(접근성 서비스가 꺼져 있어도 화면이 맞게 보이도록).
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            val current = prefs.lockTimer
            if (current != null && current.phaseAt(nowMillis) == LockTimer.Phase.DONE) {
                prefs.lockTimer = null
                timer = null
            } else if (current != timer) {
                timer = current
            }
            delay(1000)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .widthIn(max = 760.dp)
            .padding(horizontal = Spacing.gutter).padding(top = Spacing.lg, bottom = Spacing.xl)
    ) {
        // 144차: 화면 제목은 관리 탭 머리가 보여주므로 여기선 바로 약속 내용(히어로)부터. 안내 문구는 히어로 아래 한 단락으로.
        if (!AccessibilityServiceChecker.isEnabled(context)) {
            NoticeStrip(
                "접근성 서비스가 꺼져 있어 타이머를 걸어도 실제로 잠기지 않습니다",
                actionLabel = "켜기",
                onAction = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            )
            Spacer(Modifier.height(Spacing.lg))
        }

        val active = timer
        if (active == null) {
            startNotice?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(Spacing.sm))
            }
            LockTimerSetup(prefs = prefs, onStart = { created ->
                scope.launch {
                    // 다른 기기에서 이미 진행 중인 약속이 있으면 그걸 받아오고 새로 걸지 않는다(한 번에 하나).
                    if (repository.startLockTimer(created)) {
                        startNotice = null
                    } else {
                        startNotice = "다른 기기에서 이미 진행 중인 타이머가 있어 새로 걸지 않았습니다."
                    }
                    timer = prefs.lockTimer
                }
            })
        } else {
            LockTimerRunning(
                timer = active,
                nowMillis = nowMillis,
                onUnlocked = {
                    scope.launch {
                        repository.releaseLockTimer()
                        timer = null
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LockTimerSetup(prefs: AppPreferences, onStart: (LockTimer) -> Unit) {
    var preset by remember { mutableStateOf(prefs.lockTimerPreset) }
    var allowedSitesText by remember { mutableStateOf(preset.allowedSites.joinToString("\n")) }
    var targetSitesText by remember { mutableStateOf(preset.targetSites.joinToString("\n")) }
    var showConfirm by remember { mutableStateOf(false) }

    fun update(next: LockTimerPreset) {
        preset = next
        prefs.lockTimerPreset = next
    }

    // 144차 히어로: 지금 고른 약속을 문장 두 줄로 크게 — 아래 칸을 바꾸면 곧바로 이 문장이 바뀐다.
    Overline("이거까지만 할게요!")
    Spacer(Modifier.height(4.dp))
    Text(
        if (preset.freeMinutes == 0) "지금 바로" else "${formatMinutesKo(preset.freeMinutes)} 더 쓰고",
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        "${formatMinutesKo(preset.lockMinutes)} 잠급니다",
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(Spacing.sm))
    // 143차: 약속은 로그인한 다른 기기에도 같이 걸리고(푸는 것도 한 번이면 모든 기기에서 풀린다), 각 기기는 자기가
    // 마지막으로 고른 허용·잠글 목록을 쓴다.
    Text(
        "정한 시간만 더 쓰고 그 뒤는 스스로 잠급니다. 같은 계정으로 로그인한 다른 기기에도 같은 약속이 걸리고, 각 기기는 그 기기에서 마지막으로 고른 앱·사이트 목록을 씁니다.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(Spacing.xl))

    SectionCard("얼마나 더 쓸까요", emoji = "🙋") {
        NumberStepperField(
            label = "자유 시간(분)",
            value = preset.freeMinutes.toString(),
            onValueChange = { text ->
                update(preset.copy(freeMinutes = (text.toIntOrNull() ?: preset.freeMinutes).coerceIn(0, LockTimer.MAX_FREE_MINUTES)))
            },
            min = 0, max = LockTimer.MAX_FREE_MINUTES,
            modifier = Modifier.width(180.dp)
        )
        Spacer(Modifier.height(Spacing.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FREE_MINUTE_CHIPS.forEach { (label, minutes) ->
                FilterChip(
                    selected = preset.freeMinutes == minutes,
                    onClick = { update(preset.copy(freeMinutes = minutes)) },
                    label = { Text(label) }
                )
            }
        }
        Text(
            "이 시간 동안은 평소처럼 쓰고, 끝나면 바로 잠깁니다. 0으로 두면 시작하자마자 잠깁니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(Spacing.md))

    SectionCard("얼마 동안 잠글까요", emoji = "🔒") {
        NumberStepperField(
            label = "잠금 시간(분)",
            value = preset.lockMinutes.toString(),
            onValueChange = { text ->
                update(
                    preset.copy(
                        lockMinutes = (text.toIntOrNull() ?: preset.lockMinutes)
                            .coerceIn(LockTimer.MIN_LOCK_MINUTES, LockTimer.MAX_LOCK_MINUTES)
                    )
                )
            },
            min = LockTimer.MIN_LOCK_MINUTES, max = LockTimer.MAX_LOCK_MINUTES,
            modifier = Modifier.width(180.dp)
        )
        Spacer(Modifier.height(Spacing.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            LOCK_MINUTE_CHIPS.forEach { (label, minutes) ->
                FilterChip(
                    selected = preset.lockMinutes == minutes,
                    onClick = { update(preset.copy(lockMinutes = minutes)) },
                    label = { Text(label) }
                )
            }
        }
        Text(
            "한 번에 최대 ${LockTimer.MAX_LOCK_MINUTES / 60}시간까지 잠글 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(Spacing.md))

    SectionCard("무엇을 잠글까요", emoji = "🎯") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FilterChip(
                selected = preset.wholeDevice,
                onClick = { update(preset.copy(wholeDevice = true)) },
                label = { Text("기기 전체") }
            )
            FilterChip(
                selected = !preset.wholeDevice,
                onClick = { update(preset.copy(wholeDevice = false)) },
                label = { Text("고른 앱·사이트만") }
            )
        }
        Text(
            if (preset.wholeDevice) {
                "기기 전체가 잠기고 아래에서 고른 앱·사이트만 쓸 수 있습니다. 홈 화면과 전화·시계·키보드는 항상 열립니다."
            } else {
                "아래에서 고른 앱·사이트만 잠급니다."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            if (preset.wholeDevice) "허용할 앱 (${preset.allowedApps.size}개)" else "잠글 앱 (${preset.targetApps.size}개)",
            style = MaterialTheme.typography.labelLarge
        )
        // 전체 잠금의 허용 목록과 특정 잠금의 잠글 목록은 서로 다른 목록이라, 방식을 바꾸면 목록 화면도 새로 만든다.
        key(preset.wholeDevice) {
            AppMultiSelectPicker(
                initialSelection = if (preset.wholeDevice) preset.allowedApps else preset.targetApps,
                onChange = { selection ->
                    update(if (preset.wholeDevice) preset.copy(allowedApps = selection) else preset.copy(targetApps = selection))
                }
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = if (preset.wholeDevice) allowedSitesText else targetSitesText,
            onValueChange = { text ->
                val sites = text.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                if (preset.wholeDevice) {
                    allowedSitesText = text
                    update(preset.copy(allowedSites = sites))
                } else {
                    targetSitesText = text
                    update(preset.copy(targetSites = sites))
                }
            },
            label = { Text(if (preset.wholeDevice) "허용할 사이트 (한 줄에 하나씩)" else "잠글 사이트 (한 줄에 하나씩)") },
            placeholder = { Text("예: youtube.com") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            if (preset.wholeDevice) {
                "브라우저를 허용했다면 허용할 사이트도 적어 주세요. 비워 두면 브라우저 안의 모든 사이트가 막힙니다."
            } else {
                "사이트는 Chrome · 삼성 인터넷 · Google 앱에서 열 때 잠깁니다."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(Spacing.md))

    SectionCard("해제 난이도", emoji = "🧗") {
        (UnlockLevel.MIN..UnlockLevel.MAX).forEach { level ->
            Row(
                Modifier.fillMaxWidth().clickable { update(preset.copy(level = level)) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = preset.level == level, onClick = { update(preset.copy(level = level)) })
                Column(Modifier.weight(1f)) {
                    Text(UnlockLevel.label(level), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        UnlockLevel.description(level, PERSUASION_MESSAGES.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Text(
            "시작한 뒤에는 자유 시간 중에 취소할 때도 같은 절차를 거칩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(Spacing.md))

    // 143차: 집중 타이머의 뽀모도로 휴식 동안 이 약속의 잠금을 잠시 푼다(시작할 때만 정할 수 있다).
    SectionCard("뽀모도로 휴식", emoji = "🍅") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("휴식 중엔 잠금 잠시 풀기", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "집중 타이머가 뽀모도로 휴식 단계인 동안은 잠금이 풀리고, 휴식이 끝나면 남은 잠금 시간이 이어집니다. 시작한 뒤에는 바꿀 수 없습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = preset.pomodoroBreakUnlock,
                onCheckedChange = { update(preset.copy(pomodoroBreakUnlock = it)) }
            )
        }
    }
    Spacer(Modifier.height(Spacing.md))

    val nothingToLock = !preset.wholeDevice && preset.targetApps.isEmpty() && preset.targetSites.isEmpty()
    Button(onClick = { showConfirm = true }, enabled = !nothingToLock, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(if (preset.freeMinutes == 0) "지금 잠그기" else "이거까지만 할게요!", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
    }
    if (nothingToLock) {
        Text(
            "잠글 앱이나 사이트를 하나 이상 골라 주세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    if (showConfirm) {
        val apps = if (preset.wholeDevice) preset.allowedApps else preset.targetApps
        val sites = if (preset.wholeDevice) preset.allowedSites else preset.targetSites
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("이대로 시작할까요?") },
            text = {
                Column {
                    Text(
                        (if (preset.freeMinutes == 0) "지금부터 " else "${preset.freeMinutes}분 뒤부터 ") +
                            "${preset.lockMinutes}분 동안 " +
                            (if (preset.wholeDevice) "기기 전체를 잠급니다(허용: 앱 ${apps.size}개 · 사이트 ${sites.size}개)."
                            else "앱 ${apps.size}개 · 사이트 ${sites.size}개를 잠급니다.")
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "${UnlockLevel.label(preset.level)} — ${UnlockLevel.description(preset.level, PERSUASION_MESSAGES.size)}",
                        fontWeight = FontWeight.SemiBold
                    )
                    if (preset.pomodoroBreakUnlock) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text("🍅 뽀모도로 휴식 중엔 잠금이 잠시 풀립니다.")
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "시작하면 잠금이 끝날 때까지 이 절차 없이는 취소할 수 없습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onStart(
                        LockTimer.create(
                            nowMillis = System.currentTimeMillis(),
                            freeMinutes = preset.freeMinutes,
                            lockMinutes = preset.lockMinutes,
                            wholeDevice = preset.wholeDevice,
                            apps = apps,
                            sites = sites,
                            level = preset.level,
                            pomodoroBreakUnlock = preset.pomodoroBreakUnlock
                        )
                    )
                }) { Text("시작") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("취소") } }
        )
    }
}

@Composable
private fun LockTimerRunning(timer: LockTimer, nowMillis: Long, onUnlocked: () -> Unit) {
    val phase = timer.phaseAt(nowMillis)
    val locked = phase == LockTimer.Phase.LOCKED

    // 144차 히어로: 남은 시간을 화면 폭에 맞춰 아주 크게, 그 아래 이번 단계가 얼마나 지났는지 막대 하나.
    val palette = LocalPhoneLockPalette.current
    val phaseColor = if (locked) MaterialTheme.colorScheme.primary else palette.warning
    Row(verticalAlignment = Alignment.CenterVertically) {
        LiveDot(phaseColor)
        Spacer(Modifier.width(8.dp))
        Text(if (locked) "잠금 중" else "자유 시간", style = MaterialTheme.typography.labelLarge, color = phaseColor)
    }
    FitText(
        formatLockRemaining(timer.remainingMillis(nowMillis)),
        style = MaterialTheme.typography.displayLarge,
        maxSize = 80.sp,
        color = MaterialTheme.colorScheme.onBackground
    )
    Text(
        if (locked) "뒤에 풀립니다" else "뒤에 잠깁니다",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(Spacing.md))
    val phaseStart = if (locked) timer.lockStartAtMillis else timer.startedAtMillis
    val phaseEnd = if (locked) timer.lockEndAtMillis else timer.lockStartAtMillis
    ProgressLine(
        if (phaseEnd > phaseStart) ((nowMillis - phaseStart).toFloat() / (phaseEnd - phaseStart)).coerceIn(0f, 1f) else 1f,
        color = phaseColor
    )
    Spacer(Modifier.height(Spacing.xl))

    SectionCard("이번 약속", emoji = "📌") {
        val lockMinutes = (timer.lockEndAtMillis - timer.lockStartAtMillis) / 60_000L
        Text(
            if (timer.wholeDevice) {
                "${lockMinutes}분 동안 기기 전체 잠금 · 허용 앱 ${timer.apps.size}개 · 사이트 ${timer.sites.size}개"
            } else {
                "${lockMinutes}분 동안 앱 ${timer.apps.size}개 · 사이트 ${timer.sites.size}개 잠금"
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "${UnlockLevel.label(timer.level)} — ${UnlockLevel.description(timer.level, PERSUASION_MESSAGES.size)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (timer.pomodoroBreakUnlock) {
            Text(
                "🍅 뽀모도로 휴식 중엔 잠금이 잠시 풀립니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(Modifier.height(Spacing.md))

    LockTimerUnlockGate(timer = timer, locked = locked, onUnlocked = onUnlocked)
}

/** 해제 절차의 진행 단계. */
private enum class UnlockStep { IDLE, QUESTIONS, WAITING, READY }

/**
 * 난이도별 해제 절차. 화면을 벗어나면(다른 앱으로 전환·화면 꺼짐) 진행 중이던 절차는 처음으로 돌아간다 —
 * 차단 규칙 편집의 확인 질문과 같은 규칙으로, 켜 두고 딴짓하다 돌아와 통과하는 것을 막는다.
 */
@Composable
private fun LockTimerUnlockGate(timer: LockTimer, locked: Boolean, onUnlocked: () -> Unit) {
    val actionLabel = if (locked) "잠금 풀기" else "약속 취소"
    if (!UnlockLevel.canUnlock(timer.level)) {
        SectionCard(actionLabel, emoji = "⛔") {
            Text(
                "${UnlockLevel.label(timer.level)}으로 시작한 약속입니다. 시간이 끝날 때까지 풀 수 없습니다.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    var step by remember(timer.startedAtMillis) { mutableStateOf(UnlockStep.IDLE) }
    var messageIndex by remember(timer.startedAtMillis) { mutableIntStateOf(0) }
    var waitRemaining by remember(timer.startedAtMillis) { mutableIntStateOf(0) }
    var notice by remember(timer.startedAtMillis) { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && step != UnlockStep.IDLE) {
                step = UnlockStep.IDLE
                messageIndex = 0
                notice = "화면을 벗어나서 절차가 취소되었습니다. 처음부터 다시 해 주세요."
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(step) {
        if (step != UnlockStep.WAITING) return@LaunchedEffect
        waitRemaining = UnlockLevel.waitSeconds(timer.level)
        while (waitRemaining > 0) {
            delay(1000)
            waitRemaining -= 1
        }
        step = UnlockStep.READY
    }

    fun afterQuestions() {
        step = if (UnlockLevel.waitSeconds(timer.level) > 0) UnlockStep.WAITING else UnlockStep.READY
    }

    SectionCard(actionLabel, emoji = "🔓") {
        when (step) {
            UnlockStep.IDLE -> {
                notice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(Spacing.xs))
                }
                Text(
                    UnlockLevel.description(timer.level, PERSUASION_MESSAGES.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(
                    onClick = {
                        notice = null
                        messageIndex = 0
                        if (UnlockLevel.needsQuestions(timer.level)) step = UnlockStep.QUESTIONS else afterQuestions()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("$actionLabel 절차 시작") }
            }
            UnlockStep.QUESTIONS -> {
                val isLast = messageIndex == PERSUASION_MESSAGES.lastIndex
                PersuasionStepper(
                    stepKey = timer.startedAtMillis,
                    messageIndex = messageIndex,
                    headerText = "스스로 한 약속을 깨려는 중입니다 (%d/%d)".format(messageIndex + 1, PERSUASION_MESSAGES.size),
                    message = PERSUASION_MESSAGES[messageIndex],
                    confirmLabel = "예",
                    onCancel = {
                        step = UnlockStep.IDLE
                        messageIndex = 0
                    },
                    onConfirmStep = { if (isLast) afterQuestions() else messageIndex++ }
                )
            }
            UnlockStep.WAITING -> {
                Text(
                    "이 화면을 그대로 두고 기다려 주세요.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    formatLockRemaining(waitRemaining * 1000L),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(onClick = { step = UnlockStep.IDLE }, modifier = Modifier.fillMaxWidth()) { Text("그만두기") }
            }
            UnlockStep.READY -> {
                Text("절차를 모두 마쳤습니다.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Spacing.sm))
                Button(onClick = onUnlocked, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) }
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(onClick = { step = UnlockStep.IDLE }, modifier = Modifier.fillMaxWidth()) {
                    Text("그대로 두기")
                }
            }
        }
    }
}

/** "90분" → "1시간 30분", "60분" → "1시간" — 히어로 문장용(144차). */
private fun formatMinutesKo(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}분"
        m == 0 -> "${h}시간"
        else -> "${h}시간 ${m}분"
    }
}
