package com.phonelock.app.ui.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.ui.theme.PhoneLockTheme
import com.phonelock.app.ui.theme.ThemeMode
import com.phonelock.app.ui.theme.applyThemeWindowBackground

/**
 * 미니멀 런처의 홈 화면(130차). 기기의 홈 버튼이 이 액티비티를 띄운다.
 *
 * **[com.phonelock.app.ui.MainActivity]와 일부러 분리했다** — MainActivity는 onCreate에서 Room 열기,
 * 마이그레이션 전 백업, WorkManager 3종 예약, 로그인 게이트(AccountGate)를 전부 실행한다. 그게 홈
 * 버튼에 매달리면 ① 로그아웃 상태에서 홈 화면이 로그인 화면이 되어 폰을 못 쓰고 ② 홈 복귀가 느려지고
 * ③ **크래시 한 번이 곧 "홈 버튼이 안 먹는 폰"**이 된다. 그래서 이 액티비티는 창을 띄우는 데
 * SharedPreferences 한 번 읽는 것 말고는 아무것도 하지 않고, 앱 목록은 PackageManager만으로,
 * 오늘 요약 같은 부가 정보는 전부 실패해도 화면이 그대로 뜨도록 비동기 + runCatching으로 얹는다.
 *
 * `taskAffinity=""`(manifest)로 자기만의 태스크에 머물러서, 여기서 띄운 다른 앱/MainActivity가 홈
 * 태스크 위에 쌓이지 않는다.
 */
class LauncherActivity : ComponentActivity() {

    /** 앱 서랍이 열려 있는지 — 홈 버튼을 다시 누르면(onNewIntent) 홈으로 되돌리기 위해 액티비티가 들고 있다. */
    private val showDrawer = mutableStateOf(false)

    /** 화면에 돌아올 때마다 올려서 앱 목록/오늘 요약을 다시 읽게 하는 카운터(앱 설치·삭제·루틴 체크 반영). */
    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = runCatching { AppPreferences(applicationContext) }.getOrNull()
        runCatching { preferences?.let { applyThemeWindowBackground(it) } }

        setContent {
            // 133차(사용자 요청 "런처 홈에도 테마가 적용되게"): 홈 액티비티는 singleTask라 한 번 뜨면
            // 며칠씩 그대로 살아 있어서, onCreate에서 한 번 읽은 테마를 쥐고 있으면 앱에서 테마·미니멀
            // 모드를 바꿔도 홈 화면만 예전 색으로 남았다. 화면에 돌아올 때마다(onResume → refreshTick)
            // 설정을 다시 읽고, 값이 바뀌었으면 창 배경(첫 프레임/전환 중에 비치는 색)도 같이 맞춘다.
            val tick = refreshTick.intValue
            val theme = remember(tick) { readThemeSettings(preferences) }
            LaunchedEffect(theme) { runCatching { preferences?.let { applyThemeWindowBackground(it) } } }
            PhoneLockTheme(
                themeMode = theme.mode,
                customBackground = theme.background,
                customAccent = theme.accent,
                fontScale = theme.fontScale
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LauncherRoot(showDrawer = showDrawer, refreshTick = tick)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
    }

    /** 앱 서랍을 열어둔 채로 홈 버튼을 누르면 홈으로 돌아와야 한다(런처의 기본 동작). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showDrawer.value = false
    }
}

/** 런처 화면이 쓰는 표시 설정 묶음 — 하나라도 바뀌면 테마를 다시 적용한다(data class 동등성 비교). */
private data class LauncherThemeSettings(
    val mode: String,
    val background: String,
    val accent: String,
    val fontScale: Float
)

/** 설정을 못 읽어도 홈은 떠야 하므로 기본값(라이트 그린)으로 물러난다 — 130차부터 쓰던 기본값 그대로. */
private fun readThemeSettings(preferences: AppPreferences?): LauncherThemeSettings = runCatching {
    LauncherThemeSettings(
        mode = preferences?.effectiveThemeMode ?: ThemeMode.LIGHT_GREEN,
        background = preferences?.customThemeBackground ?: "#FAFBF6",
        accent = preferences?.customThemeAccent ?: "#8BC34A",
        fontScale = preferences?.fontScale ?: 1.0f
    )
}.getOrDefault(LauncherThemeSettings(ThemeMode.LIGHT_GREEN, "#FAFBF6", "#8BC34A", 1.0f))

/**
 * "기본 런처로 지정" 화면을 여는 인텐트. API 29+는 시스템이 주는 역할(ROLE_HOME) 선택 다이얼로그를,
 * 그 이하(minSdk 26~28)는 설정 앱의 홈 화면 설정을 연다.
 *
 * 반환한 인텐트는 `rememberLauncherForActivityResult(StartActivityForResult())`로 실행한다 —
 * `createRequestRoleIntent`는 결과를 받는 방식으로 띄우는 게 정상 사용법이다.
 */
fun buildSetDefaultLauncherIntent(context: Context): Intent {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
            return roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
        }
    }
    return Intent(Settings.ACTION_HOME_SETTINGS)
}

/** 지금 이 앱이 기본 런처인지. */
fun isDefaultLauncher(context: Context): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    resolved?.activityInfo?.packageName == context.packageName
}.getOrDefault(false)

/** 지금 기본 런처로 지정된 앱 이름(없거나 "선택 안 함" 상태면 null). */
fun currentLauncherLabel(context: Context): String? = runCatching {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ?: return@runCatching null
    // 기본 런처가 정해져 있지 않으면 시스템이 "선택기"(resolver)를 돌려준다 — 그건 앱 이름으로 보여줄 게 못 된다.
    if (resolved.activityInfo.packageName == "android") null
    else resolved.loadLabel(context.packageManager).toString()
}.getOrNull()
