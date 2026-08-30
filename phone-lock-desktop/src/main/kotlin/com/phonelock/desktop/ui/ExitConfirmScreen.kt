package com.phonelock.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import com.phonelock.desktop.ui.components.WatchAndWaitScreen
import kotlinx.coroutines.delay

/**
 * 앱을 완전히 종료하는 것은 다른 모든 제한을 한 번에 무력화시키는 가장 강력한 꼼수이므로,
 * 회유 멘트 20개를 하나씩 "예"를 눌러 끝까지 확인해야 실제로 종료된다. 각 단계는 눌렀을 때부터
 * 무작위 시간만큼 기다린 뒤 자동으로 다음 멘트로 넘어간다(20단계 합쳐도 10분 안쪽).
 * WatchAndWaitScreen의 countdownSeconds는 실행확인("진행" 선택)의 "중간에 불시 정지" 기능과
 * 묶여있어 여기서는 쓰지 않고, primaryEnabled로 버튼 잠금만 직접 제어한다.
 */
@Composable
fun ExitConfirmScreen(onConfirmExit: () -> Unit, onCancel: () -> Unit) {
    var messageIndex by remember { mutableIntStateOf(0) }
    val isLast = messageIndex == PERSUASION_MESSAGES.lastIndex
    val stepDelaysMs = remember { randomPersuasionStepDelaysMs() }
    var stepStarted by remember(messageIndex) { mutableStateOf(false) }
    var stepRemainingSeconds by remember(messageIndex) { mutableIntStateOf(0) }

    // 창을 벗어나면 진행 중이던 시도를 취소하고 처음부터 다시 하게 한다.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (!windowInfo.isWindowFocused && (messageIndex > 0 || stepStarted)) {
            messageIndex = 0
            stepStarted = false
        }
    }

    LaunchedEffect(messageIndex, stepStarted) {
        if (!stepStarted) return@LaunchedEffect
        stepRemainingSeconds = ((stepDelaysMs[messageIndex] + 999) / 1000).toInt()
        while (stepRemainingSeconds > 0) {
            delay(1000)
            stepRemainingSeconds -= 1
        }
        if (isLast) onConfirmExit() else messageIndex++
    }

    val baseLabel = if (isLast) "종료" else "예"
    val primaryLabel = if (stepStarted && stepRemainingSeconds > 0) "$baseLabel (${stepRemainingSeconds}초)" else baseLabel

    WatchAndWaitScreen(
        title = "정말 갓생살기종합세트를 종료하시겠습니까?",
        message = "회유 멘트 (%d/%d)".format(messageIndex + 1, PERSUASION_MESSAGES.size),
        quote = PERSUASION_MESSAGES[messageIndex],
        countdownSeconds = null,
        primaryEnabled = !stepStarted,
        primaryLabel = primaryLabel,
        onPrimary = { stepStarted = true },
        secondaryLabel = "취소",
        onSecondary = onCancel
    )
}
