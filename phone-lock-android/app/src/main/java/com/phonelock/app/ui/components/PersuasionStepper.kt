package com.phonelock.app.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import com.phonelock.shared.randomPersuasionStepDelaysMs
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * 회유 절차(persuasion stepper)의 "메시지 하나 보여주기 + 타이머 + 버튼" 부분을 공통화한 컴포저블.
 * GroupEditScreen의 그룹 수정 저장 시 / 그룹 삭제 시 회유 절차 두 곳에서 쓰인다(레이아웃과 텍스트
 * 스타일이 완전히 동일한 두 곳만 통합했다 — GroupListScreen의 일정 끄기 회유 절차는 버튼 배치(Row,
 * 취소가 먼저)와 메시지 텍스트 스타일(bodySmall)이 달라서 그대로 남겨두었다).
 *
 * [stepKey]가 바뀌면 이번 회유 절차에서 쓸 무작위 딜레이들을 새로 뽑는다. [messageIndex]가 바뀔
 * 때마다 "시작 전" 상태로 리셋된다. 버튼을 누르면([onConfirmStep] 트리거) 그 단계에 배정된 시간만큼
 * 기다린 뒤 [onConfirmStep]을 호출한다 — 마지막 단계인지 여부와 그때 할 일(다음 단계로 진행할지,
 * 실제 행동을 실행할지)은 호출하는 쪽이 판단한다.
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
    Text(
        headerText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(Spacing.xs))
    Text(message, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(Spacing.sm))
    Button(
        onClick = { stepStarted = true },
        enabled = !stepStarted,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (stepStarted && stepRemainingSeconds > 0) "$confirmLabel (${stepRemainingSeconds}초)" else confirmLabel)
    }
    Spacer(Modifier.height(Spacing.sm))
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(cancelLabel)
    }
}
