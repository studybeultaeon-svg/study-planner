package com.phonelock.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonelock.shared.PERSUASION_MESSAGES
import com.phonelock.shared.randomPersuasionStepDelaysMs
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * 회유 절차(persuasion stepper)의 "메시지 하나 보여주기 + 타이머 + 버튼" 부분을 공통화한 컴포저블.
 * 데스크탑은 규칙 편집(저장·삭제)이 쓴다(전체화면 확인은 [com.phonelock.desktop.ui.ExitConfirmScreen]).
 *
 * [stepKey]가 바뀌면 이번 회유 절차에서 쓸 무작위 딜레이들을 새로 뽑는다. [messageIndex]가 바뀔
 * 때마다 "시작 전" 상태로 리셋된다. 버튼을 누르면([onConfirmStep] 트리거) 그 단계에 배정된 시간만큼
 * 기다린 뒤 [onConfirmStep]을 호출한다 — 마지막 단계인지 여부와 그때 할 일(다음 단계로 진행할지,
 * 실제 행동을 실행할지)은 호출하는 쪽이 판단한다. [headerText]는 "무엇을 하려는지"만 쓴다(몇 번째인지는 여기서 붙인다).
 */
@Composable
fun PersuasionStepper(
    stepKey: Any,
    messageIndex: Int,
    headerText: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String = "취소",
    onCancel: () -> Unit,
    onConfirmStep: suspend () -> Unit
) {
    val stepDelaysMs = remember(stepKey) { randomPersuasionStepDelaysMs() }
    var stepStarted by remember(messageIndex) { mutableStateOf(false) }
    var stepRemainingSeconds by remember(messageIndex) { mutableIntStateOf(0) }
    LaunchedEffect(messageIndex, stepStarted) {
        if (!stepStarted) return@LaunchedEffect
        stepRemainingSeconds = ((stepDelaysMs[messageIndex] + 999) / 1000).toInt()
        while (stepRemainingSeconds > 0) {
            delay(1000)
            stepRemainingSeconds -= 1
        }
        onConfirmStep()
    }
    PersuasionStepView(
        context = headerText,
        step = messageIndex + 1,
        total = PERSUASION_MESSAGES.size,
        message = message,
        confirmLabel = if (stepStarted && stepRemainingSeconds > 0) "$confirmLabel (${stepRemainingSeconds}초)" else confirmLabel,
        confirmEnabled = !stepStarted,
        cancelLabel = cancelLabel,
        onConfirm = { stepStarted = true },
        onCancel = onCancel
    )
}

/**
 * 확인 질문 한 단계의 모양(147차 — 사용자 지적 "확인 질문에 디자인이 안 들어갔다", 안드로이드 같은 파일과 같은 구성).
 * 작은 라벨(무엇을 하려는지) + 오른쪽 "3 / 20" 큰 숫자, 그 아래 진행 선, 강조 막대가 붙은 질문(이 앱의 목소리),
 * 버튼 줄(취소 / 예). 타이머·단계 상태는 호출하는 쪽이 갖는다.
 */
@Composable
fun PersuasionStepView(
    context: String,
    step: Int,
    total: Int,
    message: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancelLabel: String = "취소"
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Overline("확인 질문")
                Text(context, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Spacer(Modifier.width(Spacing.sm))
            BigNumber("$step", unit = "/ $total", style = MaterialTheme.typography.headlineMedium, unitStyle = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(Spacing.xs))
        ProgressLine(step / total.toFloat(), thickness = 3.dp)
        Spacer(Modifier.height(Spacing.md))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(Spacing.sm))
            Text(message, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(cancelLabel, maxLines = 1, softWrap = false) }
            Button(onClick = onConfirm, enabled = confirmEnabled, modifier = Modifier.weight(1f)) {
                Text(confirmLabel, maxLines = 1, softWrap = false)
            }
        }
    }
}
