package com.phonelock.desktop.monitor

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking

private const val EVENT_SYSTEM_FOREGROUND = 0x0003
private const val WINEVENT_OUTOFCONTEXT = 0x0000
private const val WINEVENT_SKIPOWNPROCESS = 0x0002

/**
 * 2초 주기 폴링만으로는 앱 전환 시 확인/차단이 최대 2초 늦게 뜨는 문제가 있어(모바일의
 * AccessibilityService 이벤트에 해당하는 것이 데스크탑에는 없었음), Win32 SetWinEventHook으로
 * 포그라운드 창이 바뀌는 순간 즉시 알림을 받는다. 이 훅은 별도 네이티브 메시지 루프가 있는 스레드에서만
 * 동작하므로 전용 데몬 스레드를 띄운다. 이벤트를 놓치는 경우(훅 실패 등)에 대비해
 * EnforcementService의 기존 주기적 폴링은 안전망으로 계속 둔다.
 */
object ForegroundWindowChangeWatcher {
    val changes = Channel<Unit>(Channel.CONFLATED)

    // GC로 콜백이 수거되면 네이티브 쪽 호출이 크래시하므로 참조를 계속 들고 있는다.
    private val callback = WinUser.WinEventProc { _, _, _, _, _, _, _ ->
        changes.trySendBlocking(Unit)
    }

    fun start() {
        Thread({
            val hook = User32.INSTANCE.SetWinEventHook(
                EVENT_SYSTEM_FOREGROUND,
                EVENT_SYSTEM_FOREGROUND,
                null,
                callback,
                0,
                0,
                WINEVENT_OUTOFCONTEXT or WINEVENT_SKIPOWNPROCESS
            )
            val msg = WinUser.MSG()
            while (User32.INSTANCE.GetMessage(msg, null as HWND?, 0, 0) > 0) {
                User32.INSTANCE.TranslateMessage(msg)
                User32.INSTANCE.DispatchMessage(msg)
            }
            hook?.let { User32.INSTANCE.UnhookWinEvent(it) }
        }, "ForegroundWindowChangeWatcher").apply {
            isDaemon = true
            start()
        }
    }
}
