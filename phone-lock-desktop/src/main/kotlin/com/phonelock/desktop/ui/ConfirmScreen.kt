package com.phonelock.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.phonelock.shared.confirmQuoteTier
import com.phonelock.shared.quoteForTier
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.ui.components.WatchAndWaitScreen

@Composable
fun ConfirmScreen(processName: String, waitSeconds: Int, level: Int = 0, repository: Repository? = null, groupId: Long = -1L, onYes: () -> Unit, onNo: () -> Unit) {
    val title = remember(level) { quoteForTier(confirmQuoteTier(level)) }
    // 82차(§11 "미래의 나에게") — 이 그룹에 예약 메시지가 있으면 문구와 함께 보여준다.
    val selfMessage = remember(groupId) { if (groupId >= 0) repository?.getGroup(groupId)?.selfMessageText?.ifBlank { null } else null }
    WatchAndWaitScreen(
        title = title,
        message = selfMessage,
        titleStyle = MaterialTheme.typography.headlineMedium,
        countdownSeconds = waitSeconds,
        primaryLabel = "진행",
        reverseButtonOrder = true,
        primaryFilled = false,
        onPrimary = {
            // 82차(§9/§11 "회유 멘트 성공률 통계") — 판정 로직과 무관한 순수 로깅.
            repository?.recordQuoteOutcome(confirmQuoteTier(level), title, proceeded = true)
            onYes()
        },
        secondaryLabel = "중단",
        secondaryFilled = true,
        secondaryContainerColor = MaterialTheme.colorScheme.primary,
        onSecondary = {
            repository?.recordQuoteOutcome(confirmQuoteTier(level), title, proceeded = false)
            onNo()
        }
    )
}
