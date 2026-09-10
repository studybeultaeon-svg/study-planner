package com.phonelock.desktop.monitor

import java.net.InetSocketAddress
import java.net.Socket

/**
 * 온라인/오프라인 모드(98차, 사용자 요청) — 안드로이드판은 ConnectivityManager로 실시간 감시하지만
 * 데스크탑엔 대응하는 OS API가 없어, 호출 시점에 짧은 타임아웃으로 실제 연결을 확인하는 방식으로
 * 대신한다(각 화면 진입 시 1회씩만 부르므로 부담 없음). DNS 서버(8.8.8.8:53)에 TCP 연결만 시도 —
 * 실제 페이로드 없이 가장 빠르고 어디서나 열려있는 포트.
 */
object NetworkMonitor {
    fun isOnline(timeoutMs: Int = 1000): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("8.8.8.8", 53), timeoutMs) }
        true
    }.getOrDefault(false)
}
