package com.phonelock.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.service.ConfirmationGate
import com.phonelock.app.service.IntentExtras
import com.phonelock.app.ui.components.InterstitialScreen
import com.phonelock.app.ui.theme.PhoneLockTheme

private const val DEFAULT_WAIT_SECONDS = 5

class ConfirmOpenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = PhoneLockRepository(applicationContext)
        val groupId = intent.getLongExtra(IntentExtras.EXTRA_GROUP_ID, -1L)
        val waitSeconds = intent.getIntExtra(IntentExtras.EXTRA_WAIT_SECONDS, DEFAULT_WAIT_SECONDS)
        val level = intent.getIntExtra(IntentExtras.EXTRA_LEVEL, 0)
        val isSite = intent.getBooleanExtra(IntentExtras.EXTRA_IS_SITE, false)

        fun recordConfirm() {
            if (groupId >= 0) {
                ConfirmationGate.markConfirmed(groupId)
                // lifecycleScope는 finish() 직후 취소되어 기록이 유실될 수 있어 repository 자체 수명에 묶는다.
                repository.recordConfirmFireAndForget(groupId)
            }
        }

        if (isSite) {
            setContent {
                val prefs = AppPreferences(applicationContext)
                PhoneLockTheme(prefs.themeMode, prefs.customThemeBackground, prefs.customThemeAccent) {
                    val title = remember { quoteForTier(confirmQuoteTier(level)) }
                    InterstitialScreen(
                        title = title,
                        titleStyle = MaterialTheme.typography.headlineMedium,
                        countdownSeconds = waitSeconds,
                        primaryLabel = "진행",
                        reverseButtonOrder = true,
                        primaryFilled = false,
                        onPrimary = {
                            recordConfirm()
                            // 사이트는 이미 브라우저에 열려있는 상태이므로 확인창만 닫으면 그대로 보여진다.
                            finish()
                        },
                        secondaryLabel = "중단",
                        secondaryFilled = true,
                        secondaryContainerColor = MaterialTheme.colorScheme.primary,
                        onSecondary = { goHome() }
                    )
                }
            }
            return
        }

        setContent {
            PhoneLockTheme(AppPreferences(applicationContext).themeMode) {
                val title = remember { quoteForTier(confirmQuoteTier(level)) }
                InterstitialScreen(
                    title = title,
                    titleStyle = MaterialTheme.typography.headlineMedium,
                    countdownSeconds = waitSeconds,
                    primaryLabel = "진행",
                    reverseButtonOrder = true,
                    primaryFilled = false,
                    onPrimary = {
                        recordConfirm()
                        // 앱을 다시 실행(startActivity)하면 앱이 처음 화면으로 초기화되므로,
                        // 이미 떠 있던 화면이 그대로 드러나도록 확인창만 닫는다.
                        finish()
                    },
                    secondaryLabel = "중단",
                    secondaryFilled = true,
                    secondaryContainerColor = MaterialTheme.colorScheme.primary,
                    onSecondary = { goHome() }
                )
            }
        }
    }

    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(homeIntent)
        finish()
    }

    override fun onBackPressed() {
        goHome()
    }
}
