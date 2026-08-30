package com.phonelock.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 전체화면 확인/차단 인터스티셜 공용 셸. 차단(BlockScreen), 실행 확인(ConfirmScreen),
 * 종료 확인(ExitConfirmScreen)이 거의 동일한 레이아웃과 "창을 벗어나면 대기시간이 처음부터 다시
 * 시작되는" 카운트다운 로직을 각자 중복 구현하고 있어 이걸로 통합한다.
 *
 * countdownSeconds가 null이면 대기 없이 즉시 활성화된 버튼 하나만 있는 화면(차단 안내용)이 되고,
 * secondaryLabel/onSecondary가 null이면 버튼이 하나뿐인 화면이 된다.
 */
@Composable
fun WatchAndWaitScreen(
    title: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    message: String? = null,
    quote: String? = null,
    countdownSeconds: Int? = null,
    primaryEnabled: Boolean = true,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    titleStyle: TextStyle? = null,
    reverseButtonOrder: Boolean = false,
    primaryFilled: Boolean = true,
    primaryOutlineColor: Color? = null,
    secondaryFilled: Boolean = false,
    secondaryContainerColor: Color? = null
) {
    // "잠겨있다가 풀리면 누르는" 방식이 아니라 "누르면 그때부터 대기시간이 시작되고, 다 지나면
    // 자동으로 진행되는" 방식이다. 버튼은 처음부터 눌러야 시작되고, 누른 뒤에는 다시 잠긴다.
    // 게다가 대기 도중 무작위 지점(들)에서 한 번씩 멈춰서, 다 됐나 확인 안 하고 자리를 비워도
    // 자동으로 끝까지 흘러가지 않고 사용자가 다시 눌러야 이어지게 한다(불시 재확인).
    var started by remember(countdownSeconds) { mutableStateOf(false) }
    var paused by remember(countdownSeconds) { mutableStateOf(false) }
    var remainingSeconds by remember(countdownSeconds) { mutableIntStateOf(countdownSeconds ?: 0) }
    // 중간 체크포인트와는 별개로, 0(대기시간이 다 지난 시점)은 항상 추가로 붙는 체크포인트다 — 타이머가
    // 끝나도 자동으로 진행되지 않고 마지막으로 한 번 더 눌러야 실제로 onPrimary가 호출된다.
    val originalCheckpoints = remember(countdownSeconds) {
        if (countdownSeconds == null) {
            emptyList()
        } else {
            val midCount = if (countdownSeconds >= 4) {
                // 대기시간이 길어질수록(실행확인 레벨이 오를수록 대기시간도 늘어나므로) 중간에 다시
                // 눌러야 하는 횟수도 계단식으로 늘어난다 — 30초마다 1번씩 추가.
                (1 + countdownSeconds / 30 + Random.nextInt(0, 2)).coerceIn(1, countdownSeconds - 1)
            } else {
                0
            }
            val mids = if (midCount > 0) (1 until countdownSeconds).shuffled().take(midCount) else emptyList()
            mids + 0
        }
    }
    val pendingCheckpoints = remember(countdownSeconds) { mutableStateListOf<Int>().apply { addAll(originalCheckpoints) } }
    val windowInfo = LocalWindowInfo.current

    if (countdownSeconds != null) {
        LaunchedEffect(started, paused, windowInfo.isWindowFocused) {
            if (!started || paused) return@LaunchedEffect
            if (!windowInfo.isWindowFocused) {
                // 창을 벗어나면 누른 것 자체가 취소되어 처음부터 다시 눌러야 한다.
                started = false
                remainingSeconds = countdownSeconds
                pendingCheckpoints.clear()
                pendingCheckpoints.addAll(originalCheckpoints)
                return@LaunchedEffect
            }
            // 매 틱마다 그냥 1씩 빼면(delay(1000) 후 -1) 디스패처가 잠깐 밀렸을 때(다른 백그라운드
            // 감시 루프 등) 보정이 없어 숫자가 예상보다 오래 멈춰있다가 넘어가는 버벅거림이 생긴다.
            // 그 대신 단조 시계(System.nanoTime)로 실제 흐른 시간을 100ms마다 재확인해서 남은 초를
            // 다시 계산하면, 디스패처가 밀려도 다음 확인에서 바로 따라잡아 버벅거림이 없다.
            val tickStartNanos = System.nanoTime()
            val initialRemaining = remainingSeconds
            while (remainingSeconds > 0) {
                delay(100)
                val elapsedSeconds = ((System.nanoTime() - tickStartNanos) / 1_000_000_000L).toInt()
                val newRemaining = (initialRemaining - elapsedSeconds).coerceIn(0, initialRemaining)
                while (remainingSeconds > newRemaining) {
                    remainingSeconds -= 1
                    if (pendingCheckpoints.remove(remainingSeconds)) {
                        paused = true
                        return@LaunchedEffect
                    }
                }
            }
            // 타이머가 다 끝났어도 0은 항상 체크포인트로 남아있으므로 여기서 걸린다 — 자동으로
            // 진행되지 않고 마지막으로 한 번 더 눌러야 한다.
            if (pendingCheckpoints.remove(0)) {
                paused = true
                return@LaunchedEffect
            }
            onPrimary()
            started = false
            paused = false
            remainingSeconds = countdownSeconds
            pendingCheckpoints.clear()
            pendingCheckpoints.addAll(originalCheckpoints)
        }
    }

    Surface {
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, style = titleStyle ?: MaterialTheme.typography.titleLarge)
            if (message != null) {
                Spacer(Modifier.height(Spacing.md))
                Text(message, style = MaterialTheme.typography.bodyLarge)
            }
            if (quote != null) {
                Spacer(Modifier.height(Spacing.md))
                Text(quote, style = MaterialTheme.typography.titleMedium)
            }
            if (countdownSeconds != null && started && paused) {
                Spacer(Modifier.height(Spacing.sm))
                Text("이게 의무입니까?", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            } else if (countdownSeconds != null && started && !windowInfo.isWindowFocused) {
                Spacer(Modifier.height(Spacing.sm))
                Text("이 창을 벗어나서 다시 눌러야 합니다.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(Spacing.xl))
            // primary(진행) 버튼은 누르는 순간 대기시간이 시작되고, 다 지나면 자동으로 onPrimary가
            // 호출된다 — 시각적 좌우 순서(reverseButtonOrder)나 채워짐/테두리 스타일(primaryFilled/
            // secondaryFilled)을 바꿔도 "진행" 액션에 걸린 대기시간 게이트는 항상 primary 쪽에 그대로 유지된다.
            val secondaryButton: (@Composable () -> Unit)? = if (secondaryLabel != null && onSecondary != null) {
                {
                    if (secondaryFilled) {
                        Button(
                            onClick = onSecondary,
                            colors = if (secondaryContainerColor != null) {
                                ButtonDefaults.buttonColors(containerColor = secondaryContainerColor)
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) { Text(secondaryLabel) }
                    } else {
                        OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
                    }
                }
            } else null
            val primaryOnClick = {
                if (countdownSeconds == null) {
                    onPrimary()
                } else if (!started) {
                    started = true
                } else if (paused) {
                    paused = false
                }
            }
            val primaryLocked = countdownSeconds != null && started && !paused
            val effectivePrimaryEnabled = if (countdownSeconds == null) primaryEnabled else !primaryLocked
            val primaryText = if (countdownSeconds != null && started && !paused && remainingSeconds > 0) {
                "$primaryLabel (${remainingSeconds}초)"
            } else {
                primaryLabel
            }
            val primaryButton: @Composable () -> Unit = {
                if (primaryFilled) {
                    Button(onClick = primaryOnClick, enabled = effectivePrimaryEnabled) {
                        Text(primaryText)
                    }
                } else {
                    OutlinedButton(
                        onClick = primaryOnClick,
                        enabled = effectivePrimaryEnabled,
                        colors = if (primaryOutlineColor != null) {
                            ButtonDefaults.outlinedButtonColors(contentColor = primaryOutlineColor)
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Text(primaryText)
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (reverseButtonOrder) {
                    primaryButton()
                    secondaryButton?.invoke()
                } else {
                    secondaryButton?.invoke()
                    primaryButton()
                }
            }
        }
    }
}
