package com.phonelock.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.phonelock.shared.blockQuoteTier
import com.phonelock.shared.quoteForTier
import com.phonelock.desktop.monitor.LockReason
import com.phonelock.desktop.ui.components.WatchAndWaitScreen

/**
 * 실행확인 대기화면(ConfirmScreen)과 같은 톤으로 통일. 여기선 "확인"이라는 탈출구를 주지 않기 위해
 * "진행"(primaryLabel) 버튼은 눌러도 아무 동작을 하지 않는 장식용 버튼이고, 실제로 창을 닫는 동작은
 * "중단"(secondaryLabel, onConfirm)에만 걸려있다.
 */
@Composable
fun BlockScreen(reason: LockReason, blockAttempts: Int = 0, onConfirm: () -> Unit) {
    val message = when (reason) {
        LockReason.SCHEDULE -> "지정된 시간대에는 이 그룹의 프로그램을 사용할 수 없습니다."
        LockReason.LIMIT -> "오늘 이 그룹의 사용 시간 한도를 모두 사용했습니다."
        LockReason.STUDY_LOCK -> "공부 중에는 허용된 사이트만 이용할 수 있습니다."
    }
    val title = remember(blockAttempts) { quoteForTier(blockQuoteTier(blockAttempts)) }
    WatchAndWaitScreen(
        title = title,
        titleStyle = MaterialTheme.typography.headlineMedium,
        message = message,
        primaryLabel = "진행",
        reverseButtonOrder = true,
        primaryFilled = false,
        onPrimary = {},
        secondaryLabel = "중단",
        secondaryFilled = true,
        secondaryContainerColor = MaterialTheme.colorScheme.primary,
        onSecondary = onConfirm
    )
}
