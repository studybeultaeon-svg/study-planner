package com.phonelock.desktop.ui.components

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    secondaryContainerColor: Color? = null,
    /** 147차: 확인 질문 단계(1부터) — 주면 제목 위에 "확인 질문 n / 전체" 큰 숫자와 진행 선을 그린다. */
    step: Int? = null,
    stepTotal: Int? = null
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

    // 144차 리디자인: 가운데 정렬 안내문 → 편집형 "포스터". 위는 작은 브랜드 줄, 가운데는 왼쪽 정렬 큰 제목과 강조 막대가
    // 붙은 인용문(이 앱의 목소리), 대기 중엔 남은 초를 아주 크게, 버튼은 엄지가 닿는 아래쪽. 카운트다운·체크포인트 동작은 그대로.
    Surface(color = MaterialTheme.colorScheme.background) {
        // 넓은 창에서도 글줄이 너무 길어지지 않게 가운데 720dp 기둥 안에 포스터를 세운다.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().padding(horizontal = Spacing.xl).padding(top = Spacing.xl, bottom = Spacing.xl)
        ) {
            Overline("갓생키트")
            Spacer(Modifier.weight(1f))
            // 문구는 무작위라 길이가 들쭉날쭉하다 — 폰에선 한 줄에 안 들어가면(didOverflowWidth) 글자를 조금씩 줄여
            // 한 줄로 맞추고(애매한 지점에서 줄이 갈라지지 않게), 태블릿은 폭이 넉넉해 그대로 줄바꿈한다.
            // 데스크탑은 창 폭이 넉넉해 폰처럼 한 줄로 줄여 넣지 않고 자연스럽게 줄바꿈한다(안드로이드 태블릿 분기와 같다).
            if (step != null && stepTotal != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Overline("확인 질문", Modifier.weight(1f))
                    BigNumber("$step", unit = "/ $stepTotal", style = MaterialTheme.typography.displaySmall, unitStyle = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(Spacing.xs))
                ProgressLine(step / stepTotal.toFloat(), thickness = 3.dp)
                Spacer(Modifier.height(Spacing.lg))
            }
            Text(title, style = titleStyle ?: MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth())
            if (message != null) {
                Spacer(Modifier.height(Spacing.sm))
                Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            }
            if (quote != null) {
                Spacer(Modifier.height(Spacing.lg))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(Spacing.md))
                    Text(quote, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                }
            }
            if (countdownSeconds != null && started) {
                // 대기 중: 남은 초를 크게 + 지나간 만큼 막대. 체크포인트에서 멈추면 숫자는 그대로 두고 아래에 이유를 쓴다.
                Spacer(Modifier.height(Spacing.xl))
                BigNumber(
                    "$remainingSeconds",
                    unit = "초",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp, lineHeight = 90.sp, letterSpacing = (-3).sp),
                    unitStyle = MaterialTheme.typography.headlineSmall,
                    color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(Spacing.sm))
                ProgressLine(
                    if (countdownSeconds > 0) 1f - remainingSeconds / countdownSeconds.toFloat() else 1f,
                    color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                )
            }
            if (countdownSeconds != null && started && paused) {
                Spacer(Modifier.height(Spacing.md))
                Text("이게 의무입니까?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                // 체크포인트에서 카운트다운이 조용히 멈추기만 해서, 문구만 보고는 "왜 숫자가 안 줄지" 알 수 없었다 —
                // 다시 눌러야 이어진다는 사실을 명시한다(문구 자체는 의도된 표현이라 유지).
                Text(
                    "아래 \"$primaryLabel\"을 다시 눌러야 이어집니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (countdownSeconds != null && started && !windowInfo.isWindowFocused) {
                Spacer(Modifier.height(Spacing.md))
                Text("이 창을 벗어나서 다시 눌러야 합니다.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.weight(1f))
            // primary(진행) 버튼은 누르는 순간 대기시간이 시작되고, 다 지나면 자동으로 onPrimary가 호출된다 — 좌우 순서
            // (reverseButtonOrder)나 채워짐/테두리 스타일을 바꿔도 "진행" 액션에 걸린 대기시간 게이트는 항상 primary 쪽에 있다.
            // 앱에서 가장 자주, 대개 급한 마음으로 누르는 버튼이라 56dp 높이 + 균등 폭으로 조준하기 쉽게 한다.
            val buttonModifier = Modifier.defaultMinSize(minHeight = 56.dp)
            val secondaryButton: (@Composable RowScope.() -> Unit)? = if (secondaryLabel != null && onSecondary != null) {
                {
                    if (secondaryFilled) {
                        Button(
                            onClick = onSecondary,
                            modifier = buttonModifier.weight(1f),
                            colors = if (secondaryContainerColor != null) {
                                ButtonDefaults.buttonColors(containerColor = secondaryContainerColor)
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) { Text(secondaryLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false) }
                    } else {
                        OutlinedButton(onClick = onSecondary, modifier = buttonModifier.weight(1f)) {
                            Text(secondaryLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
                        }
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
            // 남은 초는 위의 큰 숫자가 보여주므로 버튼 글자는 짧게 그대로 둔다.
            val primaryText = primaryLabel
            val primaryButton: @Composable RowScope.() -> Unit = {
                if (primaryFilled) {
                    Button(onClick = primaryOnClick, enabled = effectivePrimaryEnabled, modifier = buttonModifier.weight(1f)) {
                        Text(primaryText, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
                    }
                } else {
                    OutlinedButton(
                        onClick = primaryOnClick,
                        enabled = effectivePrimaryEnabled,
                        modifier = buttonModifier.weight(1f),
                        colors = if (primaryOutlineColor != null) {
                            ButtonDefaults.outlinedButtonColors(contentColor = primaryOutlineColor)
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Text(primaryText, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (reverseButtonOrder) {
                    primaryButton()
                    secondaryButton?.invoke(this)
                } else {
                    secondaryButton?.invoke(this)
                    primaryButton()
                }
            }
        }
    }
    }
}
