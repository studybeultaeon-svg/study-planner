package com.phonelock.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.phonelock.shared.blockQuoteTier
import com.phonelock.shared.quoteForTier
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.service.IntentExtras
import com.phonelock.app.service.LockReason
import com.phonelock.app.ui.components.InterstitialScreen
import com.phonelock.app.ui.components.MediaControlCard
import com.phonelock.app.ui.theme.PhoneLockTheme
import com.phonelock.app.ui.theme.Spacing
import com.phonelock.app.ui.theme.applyThemeWindowBackground

class BlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reasonName = intent.getStringExtra(IntentExtras.EXTRA_REASON)
        val reason = reasonName?.let { runCatching { LockReason.valueOf(it) }.getOrNull() }
        val blockAttempts = intent.getIntExtra(IntentExtras.EXTRA_BLOCK_ATTEMPTS, 0)
        // 125차(사용자 요청): 차단 규칙(스케줄/일일한도)에 걸린 앱이 Spotify 같은 음악 앱이면, 앱을 열 수
        // 없어도 백그라운드 재생을 제어할 수 있게 카드를 붙인다. 릴스/쇼츠·공부 중 사이트 차단은 "그 앱을
        // 규칙에 지정한" 상황이 아니므로 제외.
        val mediaTargetPackage = intent.getStringExtra(IntentExtras.EXTRA_PACKAGE_NAME)
            ?.takeIf { reason == null || reason == LockReason.SCHEDULE || reason == LockReason.LIMIT }
        val message = when (reason) {
            LockReason.SCHEDULE -> "지정된 시간대에는 이 차단 규칙의 앱을 사용할 수 없습니다."
            LockReason.LIMIT -> "오늘 이 차단 규칙의 사용 시간 한도를 모두 사용했습니다."
            LockReason.REELS -> "릴스 화면이 감지되어 차단되었습니다."
            LockReason.SHORTS -> "쇼츠 화면이 감지되어 차단되었습니다."
            LockReason.STUDY_LOCK -> "공부 중에는 허용된 사이트만 이용할 수 있습니다."
            null -> "이 차단 규칙은 현재 잠겨 있습니다."
        }

        // 실행확인 대기화면(ConfirmOpenActivity)과 같은 톤으로 통일. 여기선 "확인"이라는 탈출구를
        // 주지 않기 위해 "진행"(primaryLabel) 버튼은 눌러도 아무 동작을 하지 않는 장식용 버튼이고,
        // 실제로 화면을 벗어나는 동작은 "중단"(secondaryLabel, goHome)에만 걸려있다.
        val prefs = AppPreferences(applicationContext)
        applyThemeWindowBackground(prefs)
        setContent {
            PhoneLockTheme(prefs.themeMode, prefs.customThemeBackground, prefs.customThemeAccent, prefs.fontScale) {
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
                    onSecondary = { goHome() },
                    extraContent = mediaTargetPackage?.let { pkg ->
                        { MediaControlCard(targetPackage = pkg, modifier = Modifier.padding(top = Spacing.lg)) }
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

    // 125차(기존 버그): singleInstance라 이 화면이 홈 제스처 등으로 남아 있으면 다음 차단 요청이 onNewIntent로만
    // 와서, 다른 앱이 막혀도 이전 앱/사유의 화면(문구·음악 카드)이 그대로 보였다 — 다른 요청이면 새로 그린다.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!IntentExtras.isSameLockRequest(intent, this.intent)) {
            setIntent(intent)
            recreate()
        }
    }

    override fun onBackPressed() {
        goHome()
    }
}
