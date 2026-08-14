package com.phonelock.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.phonelock.desktop.ui.components.WatchAndWaitScreen

@Composable
fun ConfirmScreen(processName: String, waitSeconds: Int, level: Int = 0, onYes: () -> Unit, onNo: () -> Unit) {
    val title = remember(level) { quoteForTier(confirmQuoteTier(level)) }
    WatchAndWaitScreen(
        title = title,
        titleStyle = MaterialTheme.typography.headlineMedium,
        countdownSeconds = waitSeconds,
        primaryLabel = "진행",
        reverseButtonOrder = true,
        primaryFilled = false,
        onPrimary = onYes,
        secondaryLabel = "중단",
        secondaryFilled = true,
        secondaryContainerColor = MaterialTheme.colorScheme.primary,
        onSecondary = onNo
    )
}
