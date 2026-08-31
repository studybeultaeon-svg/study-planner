package com.phonelock.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.service.IntentExtras
import com.phonelock.app.service.LockReason
import com.phonelock.app.ui.components.InterstitialScreen
import com.phonelock.app.ui.theme.PhoneLockTheme

class BlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reasonName = intent.getStringExtra(IntentExtras.EXTRA_REASON)
        val reason = reasonName?.let { runCatching { LockReason.valueOf(it) }.getOrNull() }
        val blockAttempts = intent.getIntExtra(IntentExtras.EXTRA_BLOCK_ATTEMPTS, 0)
        val message = when (reason) {
            LockReason.SCHEDULE -> "지정된 시간대에는 이 그룹의 앱을 사용할 수 없습니다."
            LockReason.LIMIT -> "오늘 이 그룹의 사용 시간 한도를 모두 사용했습니다."
            LockReason.REELS -> "릴스 화면이 감지되어 차단되었습니다."
            LockReason.SHORTS -> "쇼츠 화면이 감지되어 차단되었습니다."
            LockReason.STUDY_LOCK -> "공부 중에는 허용된 사이트만 이용할 수 있습니다."
            null -> "이 그룹은 현재 잠겨 있습니다."
        }

        // 실행확인 대기화면(ConfirmOpenActivity)과 같은 톤으로 통일. 여기선 "확인"이라는 탈출구를
        // 주지 않기 위해 "진행"(primaryLabel) 버튼은 눌러도 아무 동작을 하지 않는 장식용 버튼이고,
        // 실제로 화면을 벗어나는 동작은 "중단"(secondaryLabel, goHome)에만 걸려있다.
        setContent {
            val prefs = AppPreferences(applicationContext)
            PhoneLockTheme(prefs.themeMode, prefs.customThemeBackground, prefs.customThemeAccent) {
                val title = remember { quoteForTier(blockQuoteTier(blockAttempts)) }
                InterstitialScreen(
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
