package com.phonelock.app.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/**
 * 기기 전체 잠금(허용한 앱만 쓰는 차단 규칙 · 타이머의 전체 잠금, 142차) 중에도 **항상 열리는 앱**.
 *
 * 전체 잠금은 "허용 목록에 없는 앱은 전부 막는다"라서, 아무것도 예외로 두지 않으면 걸려 온 전화를 받을 수도,
 * 울리는 알람을 끌 수도, 허용한 앱에서 글자를 칠 수도(키보드도 하나의 앱이다) 없게 된다. 그래서 기기를
 * 기기로 쓰는 데 꼭 필요한 것만 여기서 허용한다 — 사용자가 고르는 허용 목록과는 별개다.
 *
 * - 이 앱 자신(잠금 화면·타이머 화면)과 시스템 UI(알림창, 잠금화면)
 * - 기본 홈 앱(런처) — 홈 화면까지 막으면 잠금 화면에서 나갈 곳이 없다. 미니멀 런처를 쓰면 그 홈이 허용한
 *   앱만 보여주고, 다른 런처를 쓰면 허용 안 된 앱을 여는 순간 전체 잠금 화면이 뜬다.
 * - 전화(기본 전화 앱과 통화 화면) — 긴급 전화 포함
 * - 시계/알람 앱 — 잠금 중에 울린 알람을 끌 수 있어야 한다
 * - 켜져 있는 키보드(입력기)
 *
 * 공부 잠금은 이 목록을 쓰지 않는다(홈 화면까지 막는 기존 동작 그대로).
 */
object EssentialApps {

    /** 기본 전화 앱으로 잡히지 않는 제조사별 통화 화면·전화 서비스. */
    private val KNOWN_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.emergency"
    )

    private const val CACHE_TTL_MS = 60_000L

    @Volatile private var cached: Set<String> = emptySet()
    @Volatile private var cachedAtMillis = 0L

    /** 2초마다 도는 감시 루프에서 부르므로 잠깐 기억해 둔다 — 기본 런처/전화 앱이 바뀌어도 1분 안에 반영된다. */
    fun packages(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        if (cached.isNotEmpty() && now - cachedAtMillis < CACHE_TTL_MS) return cached
        return resolve(context.applicationContext).also {
            cached = it
            cachedAtMillis = now
        }
    }

    private fun resolve(context: Context): Set<String> = buildSet {
        addAll(KNOWN_PACKAGES)
        add(context.packageName)
        val pm = context.packageManager
        runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()?.let { add(it) }
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }
            .getOrNull()?.let { add(it) }
        runCatching {
            pm.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()?.let { add(it) }
        runCatching {
            context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.map { it.packageName }
        }.getOrNull()?.let { addAll(it) }
    }
}
