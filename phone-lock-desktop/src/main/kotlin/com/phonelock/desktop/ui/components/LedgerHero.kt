package com.phonelock.desktop.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.desktop.ui.theme.LocalAppMotion
import com.phonelock.desktop.ui.theme.LocalPhoneLockPalette
import com.phonelock.desktop.ui.theme.Spacing

/*
 * "Ledger" 히어로·상태 부품(144차 리디자인, 안드로이드 ui/components/LedgerHero.kt와 같은 구성).
 */

/**
 * 한 줄 히어로 텍스트 — [maxSize]로 그리되 폭에 안 들어가면 그만큼 비율로 줄인다(최소 [minSize]). 타이머처럼 값이
 * 길어질 수 있는 큰 숫자가 작은 폰에서 잘리거나 두 줄로 깨지지 않게 한다(넘침을 숨기지 않고 크기로 맞춘다).
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    maxSize: TextUnit = style.fontSize,
    minSize: TextUnit = 28.sp,
    color: Color = MaterialTheme.colorScheme.onBackground,
    textAlign: TextAlign = TextAlign.Start
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val maxWidthPx = constraints.maxWidth
        val fitted = remember(text, maxWidthPx, maxSize, style, density) {
            val base = style.copy(fontSize = maxSize, lineHeight = maxSize * 1.08f)
            val width = measurer.measure(text, base, maxLines = 1, softWrap = false, constraints = Constraints()).size.width
            if (maxWidthPx == Constraints.Infinity || width <= maxWidthPx || width == 0) base
            else {
                val scaled = (maxSize.value * maxWidthPx / width.toFloat() * 0.98f).coerceAtLeast(minSize.value)
                style.copy(fontSize = scaled.sp, lineHeight = (scaled * 1.08f).sp, letterSpacing = style.letterSpacing * (scaled / maxSize.value))
            }
        }
        Text(text, style = fitted, color = color, maxLines = 1, softWrap = false, textAlign = textAlign, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * 시간 히어로 — "2시간 14분"을 큰 숫자 + 작은 단위로(숫자와 단위는 같은 기준선, 줄바꿈 없음). 1시간 미만이면 "45분".
 */
@Composable
fun DurationHero(
    seconds: Long,
    modifier: Modifier = Modifier,
    numberStyle: TextStyle = MaterialTheme.typography.displayLarge,
    color: Color = MaterialTheme.colorScheme.onBackground
) {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val unitStyle = MaterialTheme.typography.titleLarge
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (h > 0) BigNumber(h.toString(), unit = "시간", style = numberStyle, unitStyle = unitStyle, color = color, modifier = Modifier.alignByBaseline())
        BigNumber(m.toString(), unit = "분", style = numberStyle, unitStyle = unitStyle, color = color, modifier = Modifier.alignByBaseline())
    }
}

/** "지금 진행 중"을 알리는 점 — 천천히 숨 쉬듯 깜빡인다. 성능 모드에선 멈춘 점. */
@Composable
fun LiveDot(color: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 8.dp) {
    val motion = LocalAppMotion.current
    val alpha = if (motion.reduced) 1f else {
        val transition = rememberInfiniteTransition(label = "liveDot")
        val a by transition.animateFloat(
            1f, 0.35f,
            infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
            label = "liveDotAlpha"
        )
        a
    }
    Box(modifier.size(size).graphicsLayer { this.alpha = alpha }.background(color, CircleShape))
}

/** 얇은 진행 막대(4dp) — 값이 바뀌면 부드럽게 따라간다(성능 모드에선 바로). 트랙은 가는 선 색. */
@Composable
fun ProgressLine(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    thickness: androidx.compose.ui.unit.Dp = 4.dp
) {
    val motion = LocalAppMotion.current
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), motion.standard(), label = "progressLine")
    val track = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier
            .fillMaxWidth()
            .height(thickness)
            .drawBehind {
                val r = size.height / 2
                drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
                if (animated > 0f) {
                    drawRoundRect(
                        color,
                        size = size.copy(width = (size.width * animated).coerceAtLeast(size.height)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                    )
                }
            }
    )
}

enum class NoticeTone { Error, Warning, Info }

/**
 * 한 줄 알림 띠 — 큰 빨간 카드 대신 왼쪽 굵은 색 막대 + 옅은 바탕 + 오른쪽 동작 하나. 문장이 길면 두 줄까지 감싸고
 * 동작 버튼은 항상 한 줄로 오른쪽에 둔다(좁은 폰에서 버튼 글자가 세로로 깨지지 않게).
 */
@Composable
fun NoticeStrip(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.Error,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    val palette = LocalPhoneLockPalette.current
    val (bar, bg) = when (tone) {
        NoticeTone.Error -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer
        NoticeTone.Warning -> palette.warning to palette.warningContainer
        NoticeTone.Info -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
    }
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).background(bg, RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(bar, RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 3,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction, modifier = Modifier.padding(end = 4.dp)) {
                Text(actionLabel, maxLines = 1, softWrap = false, color = bar)
            }
        }
    }
}
