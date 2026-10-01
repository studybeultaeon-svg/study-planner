package com.phonelock.desktop.ui

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.monitor.FullLockStatus
import com.phonelock.desktop.ui.theme.Spacing
import com.phonelock.shared.lock.formatLockRemaining
import kotlinx.coroutines.delay

/**
 * 전체 잠금 화면(142차) — PC 전체를 잠그고 허용한 프로그램만 쓰게 하는 두 기능이 함께 쓴다.
 * - 차단 규칙의 "전체 잠금 방식"이 시간대/일일 한도에 걸렸을 때
 * - 관리 > 타이머("이거까지만 할게요!")의 전체 잠금이 시작됐을 때
 *
 * 허용 안 된 프로그램이 앞에 오면 감시 루프가 그 창을 내리고 이 화면을 띄운다. 데스크탑에는 런처가 없으므로
 * 여기가 "허용한 프로그램을 여는 곳"이다(공부 잠금 화면과 같은 방식). 허용한 프로그램이나 바탕화면이 앞에 오면
 * 화면은 저절로 내려간다.
 *
 * 이 화면에는 해제 버튼을 두지 않는다 — 타이머는 관리 > 타이머에서 정한 난이도의 절차를 거쳐야 하고, 규칙은
 * 차단 규칙 화면에서(방지 시간대면 확인 질문을 거쳐) 끄거나 잠깐 풀기를 써야 한다.
 */
@Composable
fun FullLockScreen(
    status: FullLockStatus,
    onLaunchApp: (String) -> Unit,
    onOpenManage: () -> Unit,
    toastMessage: String? = null,
    onToastShown: () -> Unit = {}
) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
        }
    }
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(4000)
            onToastShown()
        }
    }

    // 144차 리디자인(안드로이드판과 같은 언어): 왼쪽 정렬 편집형 포스터 — 남은 시간을 아주 크게, 허용한 프로그램은 그 아래 묶음,
    // 버튼은 맨 아래. 표준 모드는 강조색이 옅게 번지는 바탕, 성능 모드는 단색 바탕.
    val performance = com.phonelock.desktop.ui.theme.LocalPerformanceMode.current
    val bgModifier = if (performance) Modifier.background(MaterialTheme.colorScheme.background)
    else Modifier.background(
        Brush.radialGradient(
            colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), MaterialTheme.colorScheme.background),
            radius = 1400f
        )
    )
    Box(Modifier.fillMaxSize().then(bgModifier), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl, vertical = Spacing.xl)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.phonelock.desktop.ui.components.LiveDot(MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                com.phonelock.desktop.ui.components.Overline("전체 잠금", color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            Text(status.title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            if (status.endsAtMillis > 0L) {
                Spacer(Modifier.height(Spacing.md))
                com.phonelock.desktop.ui.components.FitText(
                    formatLockRemaining(status.endsAtMillis - nowMillis),
                    style = MaterialTheme.typography.displayLarge,
                    maxSize = 96.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text("뒤에 풀립니다", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(Spacing.md))
            Text(status.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.lg))
            com.phonelock.desktop.ui.components.LedgerSection("허용한 프로그램") {
                if (status.allowedApps.isEmpty()) {
                    Text(
                        "허용한 프로그램이 없습니다. 바탕화면(탐색기)만 열립니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    AllowedAppsFlow(names = status.allowedApps, onLaunchApp = onLaunchApp)
                }
                toastMessage?.let {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(Spacing.xl))
            Button(onClick = onOpenManage, modifier = Modifier.height(52.dp)) {
                Text(if (status.fromTimer) "타이머 열기" else "차단 규칙 열기", maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                if (status.fromTimer) {
                    "도중에 풀려면 관리 > 타이머에서 정해 둔 난이도의 절차를 거쳐야 합니다."
                } else {
                    "이 규칙을 끄거나 잠깐 풀려면 관리 > 차단 규칙에서 합니다."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
