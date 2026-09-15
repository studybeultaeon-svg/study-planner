package com.phonelock.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.phonelock.shared.confirmQuoteTier
import com.phonelock.shared.quoteForTier
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.service.ConfirmationGate
import com.phonelock.app.service.IntentExtras
import com.phonelock.app.ui.components.InterstitialScreen
import com.phonelock.app.ui.theme.PhoneLockTheme
import com.phonelock.app.ui.theme.applyThemeWindowBackground

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

        val prefs = AppPreferences(applicationContext)
        applyThemeWindowBackground(prefs)

        if (isSite) {
            setContent {
                PhoneLockTheme(prefs.themeMode, prefs.customThemeBackground, prefs.customThemeAccent, prefs.fontScale) {
                    val title = remember { quoteForTier(confirmQuoteTier(level)) }
                    // 82차(§11 "미래의 나에게") — 이 그룹에 예약 메시지가 있으면 문구와 함께 보여준다.
                    var selfMessage by remember { mutableStateOf("") }
                    LaunchedEffect(groupId) {
                        if (groupId >= 0) selfMessage = repository.getGroup(groupId)?.selfMessageText ?: ""
                    }
                    InterstitialScreen(
                        title = title,
                        message = selfMessage.ifBlank { null },
                        titleStyle = MaterialTheme.typography.headlineMedium,
                        countdownSeconds = waitSeconds,
                        primaryLabel = "진행",
                        reverseButtonOrder = true,
                        primaryFilled = false,
                        onPrimary = {
                            recordConfirm()
                            repository.recordQuoteOutcomeFireAndForget(confirmQuoteTier(level), title, proceeded = true)
                            // 사이트는 이미 브라우저에 열려있는 상태이므로 확인창만 닫으면 그대로 보여진다.
                            finish()
                        },
                        secondaryLabel = "중단",
                        secondaryFilled = true,
                        secondaryContainerColor = MaterialTheme.colorScheme.primary,
                        onSecondary = {
                            repository.recordQuoteOutcomeFireAndForget(confirmQuoteTier(level), title, proceeded = false)
                            goHome()
                        }
                    )
                }
            }
            return
        }

        setContent {
            // 96차 버그 수정: 이 경로(앱 실행 확인)만 themeMode만 넘기고 커스텀 배경/강조색·글자
            // 크기를 안 넘겨서, 커스텀 테마를 쓰는 사용자에게는 이 화면만 기본 프리셋 팔레트로
            // 보였다(사이트 확인 경로인 위쪽 45줄엔 이미 다 넘기고 있었음 — 그쪽과 대칭 맞춤).
            PhoneLockTheme(prefs.themeMode, prefs.customThemeBackground, prefs.customThemeAccent, prefs.fontScale) {
                val title = remember { quoteForTier(confirmQuoteTier(level)) }
                var selfMessage by remember { mutableStateOf("") }
                LaunchedEffect(groupId) {
                    if (groupId >= 0) selfMessage = repository.getGroup(groupId)?.selfMessageText ?: ""
                }
                InterstitialScreen(
                    title = title,
                    message = selfMessage.ifBlank { null },
                    titleStyle = MaterialTheme.typography.headlineMedium,
                    countdownSeconds = waitSeconds,
                    primaryLabel = "진행",
                    reverseButtonOrder = true,
                    primaryFilled = false,
                    onPrimary = {
                        recordConfirm()
                        repository.recordQuoteOutcomeFireAndForget(confirmQuoteTier(level), title, proceeded = true)
                        // 앱을 다시 실행(startActivity)하면 앱이 처음 화면으로 초기화되므로,
                        // 이미 떠 있던 화면이 그대로 드러나도록 확인창만 닫는다.
                        finish()
                    },
                    secondaryLabel = "중단",
                    secondaryFilled = true,
                    secondaryContainerColor = MaterialTheme.colorScheme.primary,
                    onSecondary = {
                        repository.recordQuoteOutcomeFireAndForget(confirmQuoteTier(level), title, proceeded = false)
                        goHome()
                    }
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
