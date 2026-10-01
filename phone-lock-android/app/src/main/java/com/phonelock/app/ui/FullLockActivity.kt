package com.phonelock.app.ui

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.enforcedLockTimer
import com.phonelock.app.service.IntentExtras
import com.phonelock.app.service.LockEvaluator
import com.phonelock.app.ui.theme.PhoneLockTheme
import com.phonelock.app.ui.theme.applyThemeWindowBackground
import com.phonelock.shared.lock.formatLockRemaining
import kotlinx.coroutines.delay

/**
 * 전체 잠금 화면(142차) — 기기 전체를 잠그고 허용한 앱만 쓰게 하는 두 기능이 함께 쓴다.
 * - 차단 규칙의 "전체 잠금 방식"이 시간대/일일 한도에 걸렸을 때
 * - 관리 > 타이머("이거까지만 할게요!")의 전체 잠금이 시작됐을 때
 *
 * 허용 안 된 앱을 열면 접근성 서비스가 이 화면으로 되돌린다. 미니멀 런처를 쓰는 사람은 홈의 앱 목록에 애초에
 * 허용한 앱만 보이므로 이 화면을 볼 일이 드물고, 다른 런처를 쓰는 사람에게는 여기가 "허용한 앱을 여는 곳"이다.
 * 홈 화면(런처)과 전화·시계·키보드는 항상 열린다([com.phonelock.app.service.EssentialApps]).
 *
 * 이 화면에는 해제 버튼을 두지 않는다 — 타이머는 관리 > 타이머에서 정한 난이도의 절차를 거쳐야 하고, 규칙은
 * 차단 규칙 화면에서(방지 시간대면 확인 질문을 거쳐) 끄거나 잠깐 풀기를 써야 한다.
 */
class FullLockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(IntentExtras.EXTRA_FULL_LOCK_TITLE).orEmpty()
        val message = intent.getStringExtra(IntentExtras.EXTRA_FULL_LOCK_MESSAGE).orEmpty()
        val allowedPackages = intent.getStringArrayExtra(IntentExtras.EXTRA_FULL_LOCK_ALLOWED_PACKAGES)?.toList() ?: emptyList()
        val endsAtMillis = intent.getLongExtra(IntentExtras.EXTRA_FULL_LOCK_ENDS_AT, 0L)
        val groupId = intent.getLongExtra(IntentExtras.EXTRA_GROUP_ID, -1L)
        val fromTimer = groupId < 0
        val repository = PhoneLockRepository(applicationContext)
        val evaluator = LockEvaluator(repository)

        val prefs = AppPreferences(applicationContext)
        applyThemeWindowBackground(prefs)
        setContent {
            PhoneLockTheme(prefs.themeMode, prefs.customThemeBackground, prefs.customThemeAccent, prefs.fontScale, performanceMode = prefs.minimalMode) {
                FullLockScreen(
                    title = title,
                    message = message,
                    allowedPackages = allowedPackages,
                    endsAtMillis = endsAtMillis,
                    fromTimer = fromTimer,
                    onLaunchApp = { packageName ->
                        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                            finish()
                        }
                    },
                    onHome = { goHome() },
                    // 143차: 타이머뿐 아니라 규칙 잠금에서도 이 앱으로 돌아갈 수 있게 한다(규칙 잠금 → 관리 > 차단 규칙).
                    onOpenManage = {
                        startActivity(
                            MainActivity.openIntent(
                                this, MainActivity.ROUTE_MANAGE,
                                if (fromTimer) MainActivity.MANAGE_SUB_TAB_TIMER else MainActivity.MANAGE_SUB_TAB_RULES
                            )
                        )
                        finish()
                    },
                    // 잠금이 끝났으면(타이머 종료·해제, 규칙 시간대 종료·잠깐 풀기 등) 이 화면도 같이 닫힌다.
                    isStillActive = {
                        if (fromTimer) {
                            // 143차: 뽀모도로 휴식 중엔 잠금이 풀린 약속이므로 이 화면도 같이 닫힌다.
                            repository.enforcedLockTimer(prefs.lockTimer) != null
                        } else {
                            repository.getGroup(groupId)?.let { evaluator.evaluate(it).locked } == true
                        }
                    },
                    onInactive = { finish() }
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

    // singleInstance라 화면이 남아 있으면 다음 요청이 onNewIntent로만 온다 — 다른 잠금(다른 규칙·타이머)이면 새로 그린다.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val same = intent.getStringExtra(IntentExtras.EXTRA_FULL_LOCK_TITLE) == this.intent.getStringExtra(IntentExtras.EXTRA_FULL_LOCK_TITLE) &&
            intent.getLongExtra(IntentExtras.EXTRA_GROUP_ID, -1L) == this.intent.getLongExtra(IntentExtras.EXTRA_GROUP_ID, -1L) &&
            intent.getLongExtra(IntentExtras.EXTRA_FULL_LOCK_ENDS_AT, 0L) == this.intent.getLongExtra(IntentExtras.EXTRA_FULL_LOCK_ENDS_AT, 0L)
        if (!same) {
            setIntent(intent)
            recreate()
        }
    }

    // 뒤로가기로 벗어나 허용 안 된 앱으로 돌아가면 다음 tick에서 다시 잠기므로 그냥 홈으로 보낸다(BlockActivity와 같은 처리).
    override fun onBackPressed() {
        goHome()
    }
}

@Composable
private fun FullLockScreen(
    title: String,
    message: String,
    allowedPackages: List<String>,
    endsAtMillis: Long,
    fromTimer: Boolean,
    onLaunchApp: (String) -> Unit,
    onHome: () -> Unit,
    onOpenManage: () -> Unit,
    isStillActive: suspend () -> Boolean,
    onInactive: () -> Unit
) {
    val context = LocalContext.current
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            delay(1000)
            nowMillis = System.currentTimeMillis()
            // 규칙 판정은 다른 기기의 잠깐 풀기까지 확인하느라 가볍지 않으므로 2초에 한 번만 묻는다.
            if (++ticks % 2 == 0 && !isStillActive()) {
                onInactive()
                return@LaunchedEffect
            }
        }
    }
    val allowedApps = remember(allowedPackages) {
        val pm = context.packageManager
        allowedPackages.mapNotNull { pkg ->
            // 지워진 앱은 눌러도 열리지 않으므로 목록에서 뺀다.
            runCatching { AppInfo(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(), pkg) }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    // 144차 리디자인: 인터스티셜과 같은 편집형 포스터 — 왼쪽 정렬, 남은 시간을 아주 크게, 허용한 앱은 그 아래 묶음,
    // 버튼은 아래쪽. 표준 모드는 강조색이 아주 옅게 번지는 바탕, 성능 모드는 단색 바탕.
    val performance = com.phonelock.app.ui.theme.LocalPerformanceMode.current
    val bgModifier = if (performance) Modifier.background(MaterialTheme.colorScheme.background)
    else Modifier.background(
        Brush.radialGradient(
            colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), MaterialTheme.colorScheme.background),
            radius = 1400f
        )
    )
    Box(Modifier.fillMaxSize().then(bgModifier)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.phonelock.app.ui.components.LiveDot(MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                com.phonelock.app.ui.components.Overline("전체 잠금", color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (endsAtMillis > 0L) {
                Spacer(Modifier.height(16.dp))
                com.phonelock.app.ui.components.FitText(
                    formatLockRemaining(endsAtMillis - nowMillis),
                    style = MaterialTheme.typography.displayLarge,
                    maxSize = 80.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text("뒤에 풀립니다", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))

            com.phonelock.app.ui.components.LedgerSection("허용한 앱") {
                if (allowedApps.isEmpty()) {
                    Text(
                        "허용한 앱이 없습니다. 전화·시계처럼 꼭 필요한 앱만 열립니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    AllowedAppsFlow(apps = allowedApps, onLaunchApp = onLaunchApp)
                }
            }
            Spacer(Modifier.height(32.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onHome, modifier = Modifier.weight(1f).height(56.dp)) { Text("홈으로", maxLines = 1, softWrap = false) }
                Button(onClick = onOpenManage, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text(if (fromTimer) "타이머 열기" else "차단 규칙 열기", maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (fromTimer) {
                    "도중에 풀려면 관리 > 타이머에서 정해 둔 난이도의 절차를 거쳐야 합니다."
                } else {
                    "이 규칙을 끄거나 잠깐 풀려면 관리 > 차단 규칙에서 합니다. 이 앱·전화·시계·홈 화면은 항상 열립니다."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
