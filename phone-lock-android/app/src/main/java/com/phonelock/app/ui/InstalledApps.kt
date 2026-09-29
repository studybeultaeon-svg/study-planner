package com.phonelock.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(val label: String, val packageName: String)

fun getLaunchableApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = pm.queryIntentActivities(intent, 0)
    return resolved
        .filter { it.activityInfo.packageName != context.packageName }
        .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/**
 * [getLaunchableApps]를 컴포지션이 아니라 IO에서 읽어온다(136차).
 *
 * `queryIntentActivities` + 앱마다 `loadLabel`(각각 그 앱의 리소스를 여는 작업)은 설치된 앱이 많으면
 * 수백 ms가 걸린다. 이걸 `remember { getLaunchableApps(context) }`로 부르면 그 시간 동안 컴포지션이
 * 통째로 멈춰서, 화면(차단 규칙 편집 / 공부 잠금 허용 앱)이 눌린 뒤 한참 있다가 열리는 것처럼 보인다.
 * 런처 홈([com.phonelock.app.ui.launcher.LauncherRoot])은 이미 같은 이유로 IO에서 읽고 있었고, 이
 * 함수는 그 방식을 나머지 화면도 같이 쓰게 하려고 뽑아둔 것이다.
 *
 * 목록을 받기 전 첫 프레임에는 빈 목록이 나오므로, 호출부는 비어 있는 동안 "불러오는 중" 표시를 둔다.
 */
@Composable
fun rememberLaunchableApps(): List<AppInfo> {
    val context = LocalContext.current
    return produceState(initialValue = emptyList<AppInfo>(), context) {
        value = withContext(Dispatchers.IO) {
            runCatching { getLaunchableApps(context) }.getOrElse { emptyList() }
        }
    }.value
}
