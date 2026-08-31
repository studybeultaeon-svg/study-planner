package com.phonelock.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.desktop.monitor.UsageOverlayStatus
import kotlinx.coroutines.delay

// 브라우저 확장의 overlay.js와 같은 값(레벨 0 기본 불투명도, 최대치). 레벨마다 오르는 증가폭은
// 고정값이 아니라 그룹의 overlayLevelStepsToMax(몇 번째 재확인 만에 최고 밝기에 도달할지)로부터
// 매번 계산한다 — GroupEditScreen "실행 확인"에서 그룹별로 설정.
private const val OVERLAY_BASE_ALPHA = 0.55f
private const val OVERLAY_MAX_ALPHA = 0.92f
// 뽀모도로 휴식 임시 해제 위젯용 — 기본 불투명도(OVERLAY_BASE_ALPHA)보다 눈에 띄게 진하게, 고정값.
private const val OVERLAY_POMODORO_ALPHA = 0.72f

private fun formatRemaining(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

/**
 * 실행확인 통과 후 유예시간 동안 프로그램을 쓰는 중에 남은 시간을 보여주는 전체화면 위젯 — 안드로이드/
 * 브라우저 확장과 동일하게 화면 전체를 덮되, Win32 클릭-통과(Main.kt의 makeClickThrough)로 아래
 * 프로그램은 그대로 쓸 수 있다.
 */
@Composable
fun UsageOverlayContent(status: UsageOverlayStatus) {
    // EnforcementService가 2초(TICK_MS)마다 서버 쪽 계산값으로 status를 새로 보내는데, remember(status)로
    // 그 값을 받는 즉시 로컬 카운트다운을 그대로 덮어씌우면(과거 구현) 이미 1초마다 따로 흐르고 있던
    // 값과 타이밍이 딱 맞아떨어지지 않을 때(스케줄링 지연 등 1초 이내 오차) 2초마다 숫자가 한 칸
    // 스킵되거나 잠깐 멈췄다 흐르는 것처럼 버벅거려 보인다. 로컬 값과 1초 이내로만 차이나면 무시하고
    // 그대로 흐르게 두고, 크게 어긋났을 때(오버레이가 방금 뜬 경우, 새 재확인 등 실제 상태 변화)만
    // 실제로 다시 맞춘다.
    var remaining by remember { mutableStateOf(status.remainingSeconds) }

    LaunchedEffect(status) {
        if (kotlin.math.abs(remaining - status.remainingSeconds) > 1) {
            remaining = status.remainingSeconds
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (remaining > 0) remaining -= 1
        }
    }

    val alpha = if (status.isPomodoro) {
        OVERLAY_POMODORO_ALPHA
    } else {
        val alphaPerLevel = (OVERLAY_MAX_ALPHA - OVERLAY_BASE_ALPHA) / status.levelStepsToMax.coerceAtLeast(1)
        (OVERLAY_BASE_ALPHA + status.level * alphaPerLevel).coerceAtMost(OVERLAY_MAX_ALPHA)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = alpha)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
            Text(
                if (status.isPomodoro) "휴식 중 임시 해제" else "남은 유예시간",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                formatRemaining(remaining),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 64.sp
            )
        }
    }
}
