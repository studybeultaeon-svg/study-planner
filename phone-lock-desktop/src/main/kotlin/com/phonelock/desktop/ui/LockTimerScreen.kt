package com.phonelock.desktop.ui

import com.phonelock.desktop.ui.theme.LocalPhoneLockPalette
import com.phonelock.desktop.ui.components.ProgressLine
import com.phonelock.desktop.ui.components.Overline
import com.phonelock.desktop.ui.components.LiveDot
import com.phonelock.desktop.ui.components.FitText
import androidx.compose.ui.unit.sp
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.isEffectivelyOffline
import com.phonelock.desktop.data.releaseLockTimer
import com.phonelock.desktop.data.startLockTimer
import com.phonelock.desktop.data.syncLockTimer
import com.phonelock.desktop.ui.components.NumberStepperField
import com.phonelock.desktop.ui.components.ResponsiveSplit
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.theme.Spacing
import com.phonelock.shared.PERSUASION_MESSAGES
import com.phonelock.shared.lock.LockTimer
import com.phonelock.shared.lock.LockTimerPreset
import com.phonelock.shared.lock.UnlockLevel
import com.phonelock.shared.lock.formatLockRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 자주 쓰는 시간을 한 번에 넣는 칩 — (표시, 분). */
private val FREE_MINUTE_CHIPS = listOf("바로 잠금" to 0, "5분" to 5, "10분" to 10, "30분" to 30, "1시간" to 60)
private val LOCK_MINUTE_CHIPS = listOf("30분" to 30, "1시간" to 60, "2시간" to 120, "4시간" to 240)

/**
 * 관리 > 타이머 — "이거까지만 할게요!"(142차, 사용자 요청, 안드로이드판과 대칭).
 *
 * "앞으로 N분만 더 쓰고, 그 뒤 M분은 잠근다"를 스스로 약속하는 화면이다. N을 0으로 두면 누르는 즉시 잠기므로
 * "지금부터 M분 잠금"도 여기서 한다. 잠그는 범위는 PC 전체(허용한 프로그램만 사용) 또는 고른 프로그램·사이트만
 * 이고, 시작할 때 고른 난이도([UnlockLevel])의 절차를 거쳐야만 도중에 풀 수 있다 — 자유 시간 중의 취소도 같다.
 *
 * 실제로 막는 일은 감시 루프([com.phonelock.desktop.monitor.EnforcementService])와 브라우저 확장이 하고, 이
 * 화면은 약속을 만들고([Repository.lockTimer]) 남은 시간을 보여주고 해제 절차를 진행할 뿐이다.
 */
@Composable
fun LockTimerScreen(repository: Repository) {
    val scope = rememberCoroutineScope()
    var timer by remember { mutableStateOf(repository.activeLockTimer()) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var startNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            val current = repository.activeLockTimer()
            if (current != timer) timer = current
            delay(1000)
        }
    }

    // 143차: 이 화면이 열려 있는 동안 다른 기기의 약속을 받아오고(없으면 새로 걸 수 있게) 풀린 약속을 반영한다.
    // 위 1초 루프가 저장값을 다시 읽으므로 여기서는 맞추기만 한다.
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { if (!repository.isEffectivelyOffline()) repository.syncLockTimer() }
            delay(10_000)
        }
    }

    val active = timer
    // 144차: 화면 제목은 관리 탭 머리가 보여주므로 여기선 바로 약속(히어로)부터.
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
        if (active == null) {
            startNotice?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(Spacing.sm))
            }
            LockTimerSetup(repository = repository, onStart = { created ->
                scope.launch {
                    // 다른 기기에서 이미 진행 중인 약속이 있으면 그걸 받아오고 새로 걸지 않는다(한 번에 하나).
                    val started = withContext(Dispatchers.IO) { repository.startLockTimer(created) }
                    startNotice = if (started) null else "다른 기기에서 이미 진행 중인 타이머가 있어 새로 걸지 않았습니다."
                    timer = repository.activeLockTimer()
                }
            })
        } else {
            LockTimerRunning(timer = active, nowMillis = nowMillis, onUnlocked = {
                scope.launch {
                    withContext(Dispatchers.IO) { repository.releaseLockTimer() }
                    timer = null
                }
            })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LockTimerSetup(repository: Repository, onStart: (LockTimer) -> Unit) {
    var preset by remember { mutableStateOf(repository.lockTimerPreset) }
    var showConfirm by remember { mutableStateOf(false) }

    fun update(next: LockTimerPreset) {
        preset = next
        repository.lockTimerPreset = next
    }

    ResponsiveSplit(leftWeight = 1f, rightWeight = 1f, left = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(end = Spacing.md)) {
            // 144차 히어로: 지금 고른 약속을 문장 두 줄로 크게 — 아래 칸을 바꾸면 곧바로 바뀐다.
            Overline("이거까지만 할게요!")
            Spacer(Modifier.height(4.dp))
            Text(
                if (preset.freeMinutes == 0) "지금 바로" else "${formatMinutesKo(preset.freeMinutes)} 더 쓰고",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text("${formatMinutesKo(preset.lockMinutes)} 잠급니다", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Spacing.sm))
            // 143차: 약속은 로그인한 다른 기기에도 같이 걸리고(푸는 것도 한 번이면 모든 기기에서 풀린다), 각 기기는
            // 자기가 마지막으로 고른 허용·잠글 목록을 쓴다.
            Text(
                "정한 시간만 더 쓰고 그 뒤는 스스로 잠급니다. 같은 계정으로 로그인한 다른 기기에도 같은 약속이 걸리고, 각 기기는 그 기기에서 마지막으로 고른 프로그램·사이트 목록을 씁니다.",
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
        }
    }, right = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionCard("무엇을 잠글까요", emoji = "🎯") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    FilterChip(
                        selected = preset.wholeDevice,
                        onClick = { update(preset.copy(wholeDevice = true)) },
                        label = { Text("PC 전체") }
                    )
                    FilterChip(
                        selected = !preset.wholeDevice,
                        onClick = { update(preset.copy(wholeDevice = false)) },
                        label = { Text("고른 프로그램·사이트만") }
                    )
                }
                Text(
                    if (preset.wholeDevice) {
                        "PC 전체가 잠기고 아래에서 고른 프로그램·사이트만 쓸 수 있습니다. 바탕화면(탐색기)은 항상 열립니다."
                    } else {
                        "아래에서 고른 프로그램·사이트만 잠급니다."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))

                // 전체 잠금의 허용 목록과 특정 잠금의 잠글 목록은 서로 다른 목록이라 따로 기억한다.
                val apps = (if (preset.wholeDevice) preset.allowedApps else preset.targetApps).sorted()
                val sites = (if (preset.wholeDevice) preset.allowedSites else preset.targetSites).sorted()
                fun setApps(next: Set<String>) =
                    update(if (preset.wholeDevice) preset.copy(allowedApps = next) else preset.copy(targetApps = next))
                fun setSites(next: Set<String>) =
                    update(if (preset.wholeDevice) preset.copy(allowedSites = next) else preset.copy(targetSites = next))

                Text(
                    if (preset.wholeDevice) "허용할 프로그램 (실행파일 이름)" else "잠글 프로그램 (실행파일 이름)",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(Spacing.xs))
                LockListEditor(
                    items = apps,
                    placeholder = "예: chrome.exe",
                    // 프로그램 이름은 대소문자를 가리지 않으므로 소문자로 맞춰 저장한다(LockTimer.blocksApp 참고).
                    onAdd = { name -> setApps(apps.toSet() + name.lowercase()) },
                    onRemove = { idx -> setApps(apps.toSet() - apps[idx]) }
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    if (preset.wholeDevice) "허용할 사이트 (도메인)" else "잠글 사이트 (도메인)",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(Spacing.xs))
                LockListEditor(
                    items = sites,
                    placeholder = "예: youtube.com",
                    onAdd = { name -> setSites(sites.toSet() + name.lowercase()) },
                    onRemove = { idx -> setSites(sites.toSet() - sites[idx]) }
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    if (preset.wholeDevice) {
                        "브라우저를 허용했다면 허용할 사이트도 적어 주세요. 비워 두면 브라우저 안의 모든 사이트가 막힙니다. " +
                            "사이트 판정은 브라우저 확장프로그램이 켜져 있어야 동작합니다."
                    } else {
                        "사이트 잠금은 브라우저 확장프로그램이 켜져 있어야 동작합니다."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(Spacing.md))

            val nothingToLock = !preset.wholeDevice && preset.targetApps.isEmpty() && preset.targetSites.isEmpty()
            Button(onClick = { showConfirm = true }, enabled = !nothingToLock, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (preset.freeMinutes == 0) "지금 잠그기" else "이거까지만 할게요!", style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
            }
            if (nothingToLock) {
                Text(
                    "잠글 프로그램이나 사이트를 하나 이상 넣어 주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    })

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
                            (if (preset.wholeDevice) "PC 전체를 잠급니다(허용: 프로그램 ${apps.size}개 · 사이트 ${sites.size}개)."
                            else "프로그램 ${apps.size}개 · 사이트 ${sites.size}개를 잠급니다.")
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

/** 해제 절차의 진행 단계. */
private enum class UnlockStep { IDLE, QUESTIONS, WAITING, READY }

@Composable
private fun LockTimerRunning(timer: LockTimer, nowMillis: Long, onUnlocked: () -> Unit) {
    val locked = timer.phaseAt(nowMillis) == LockTimer.Phase.LOCKED
    val actionLabel = if (locked) "잠금 풀기" else "약속 취소"

    var step by remember(timer.startedAtMillis) { mutableStateOf(UnlockStep.IDLE) }
    var waitRemaining by remember(timer.startedAtMillis) { mutableIntStateOf(0) }
    var notice by remember(timer.startedAtMillis) { mutableStateOf<String?>(null) }

    // 창을 벗어나면 진행 중이던 절차는 처음으로 돌아간다 — 켜 두고 딴짓하다 돌아와 통과하는 것을 막는다
    // (확인 질문 화면 ExitConfirmScreen은 스스로 같은 처리를 하고, 여기서는 기다리는 단계를 맡는다).
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (!windowInfo.isWindowFocused && (step == UnlockStep.WAITING || step == UnlockStep.READY)) {
            step = UnlockStep.IDLE
            notice = "창을 벗어나서 절차가 취소되었습니다. 처음부터 다시 해 주세요."
        }
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

    if (step == UnlockStep.QUESTIONS) {
        ExitConfirmScreen(
            title = "스스로 한 약속을 깨려는 중입니다",
            finalLabel = "통과",
            onConfirmExit = { afterQuestions() },
            onCancel = { step = UnlockStep.IDLE }
        )
        return
    }

    ResponsiveSplit(leftWeight = 1f, rightWeight = 1f, left = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // 144차 히어로: 남은 시간을 폭에 맞춰 아주 크게, 그 아래 이번 단계가 얼마나 지났는지 막대 하나.
            val palette = LocalPhoneLockPalette.current
            val phaseColor = if (locked) MaterialTheme.colorScheme.primary else palette.warning
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(phaseColor)
                Spacer(Modifier.width(8.dp))
                Text(if (locked) "잠금 중" else "자유 시간", style = MaterialTheme.typography.labelLarge, color = phaseColor)
            }
            FitText(formatLockRemaining(timer.remainingMillis(nowMillis)), style = MaterialTheme.typography.displayLarge, maxSize = 84.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(if (locked) "뒤에 풀립니다" else "뒤에 잠깁니다", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        "${lockMinutes}분 동안 PC 전체 잠금 · 허용 프로그램 ${timer.apps.size}개 · 사이트 ${timer.sites.size}개"
                    } else {
                        "${lockMinutes}분 동안 프로그램 ${timer.apps.size}개 · 사이트 ${timer.sites.size}개 잠금"
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
        }
    }, right = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (!UnlockLevel.canUnlock(timer.level)) {
                SectionCard(actionLabel, emoji = "⛔") {
                    Text(
                        "${UnlockLevel.label(timer.level)}으로 시작한 약속입니다. 시간이 끝날 때까지 풀 수 없습니다.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                return@Column
            }
            SectionCard(actionLabel, emoji = "🔓") {
                when (step) {
                    UnlockStep.IDLE, UnlockStep.QUESTIONS -> {
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
                                if (UnlockLevel.needsQuestions(timer.level)) step = UnlockStep.QUESTIONS else afterQuestions()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("$actionLabel 절차 시작") }
                    }
                    UnlockStep.WAITING -> {
                        Text("이 창을 그대로 두고 기다려 주세요.", style = MaterialTheme.typography.bodyMedium)
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
    })
}

/** "90분" → "1시간 30분", "60분" → "1시간" — 히어로 문장용(144차, 안드로이드판과 같은 규칙). */
private fun formatMinutesKo(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}분"
        m == 0 -> "${h}시간"
        else -> "${h}시간 ${m}분"
    }
}
