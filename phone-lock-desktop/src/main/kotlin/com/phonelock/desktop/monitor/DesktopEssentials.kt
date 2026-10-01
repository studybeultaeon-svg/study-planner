package com.phonelock.desktop.monitor

import java.io.File

/**
 * 기기 전체 잠금(전체 잠금 방식 규칙 · 타이머의 전체 잠금, 142차) 중에도 **항상 열리는 프로그램**.
 * 안드로이드의 `EssentialApps`와 같은 역할이다.
 *
 * - 이 앱 자신 — 잠금 화면과 타이머 화면을 띄워야 한다.
 * - 탐색기(explorer.exe) — 바탕화면·작업 표시줄이 곧 탐색기라, 이것까지 막으면 앞에 아무 창도 없을 때마다 잠금
 *   화면이 뜨고 PC를 켜 두기만 해도 일일 한도가 줄어든다. 안드로이드에서 홈 화면(런처)을 허용하는 것과 같다.
 * - 윈도우 잠금/로그인 화면 — PC를 잠가 둔 시간이 사용 시간으로 세이면 안 된다.
 *
 * 공부 잠금은 이 목록을 쓰지 않는다(허용 프로그램 외에는 전부 막는 기존 동작 그대로).
 */
object DesktopEssentials {

    private val ALWAYS_ALLOWED = setOf("explorer.exe", "lockapp.exe", "logonui.exe")

    /** 전체 잠금 방식 규칙이 "브라우저를 허용했는지" 볼 때 쓰는 실행 파일 이름. */
    private val BROWSERS = setOf(
        "chrome.exe", "msedge.exe", "whale.exe", "brave.exe", "firefox.exe", "opera.exe", "vivaldi.exe"
    )

    val selfProcessName: String? by lazy {
        runCatching { File(ProcessHandle.current().info().command().orElse(null) ?: return@lazy null).name }.getOrNull()
    }

    fun isSelf(processName: String): Boolean = processName.equals(selfProcessName, ignoreCase = true)

    fun isAlwaysAllowed(processName: String): Boolean =
        isSelf(processName) || processName.lowercase() in ALWAYS_ALLOWED

    fun isBrowser(processName: String): Boolean = processName.lowercase() in BROWSERS
}
