package com.phonelock.desktop.monitor

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.ptr.IntByReference
import java.io.File

object ForegroundWindowWatcher {
    /** 현재 활성(포그라운드) 창을 가진 프로세스의 PID를 반환한다. */
    fun currentProcessId(): Long? {
        val hwnd = User32.INSTANCE.GetForegroundWindow() ?: return null
        val pidRef = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef)
        val pid = pidRef.value.toLong()
        return if (pid > 0) pid else null
    }

    /** 현재 활성(포그라운드) 창의 핸들을 반환한다 (확인 후 원래 창을 복원/포커스하기 위해 사용). */
    fun currentForegroundWindowHandle(): HWND? = User32.INSTANCE.GetForegroundWindow()

    /** 최소화된 창을 원래 크기로 복원하고 포커스를 준다. */
    fun restoreAndFocus(hwnd: HWND) {
        User32.INSTANCE.ShowWindow(hwnd, User32.SW_RESTORE)
        User32.INSTANCE.SetForegroundWindow(hwnd)
    }

    /** 현재 활성(포그라운드) 창을 가진 프로세스의 실행파일 이름(예: chrome.exe)을 반환한다. */
    fun currentProcessName(): String? {
        val pid = currentProcessId() ?: return null
        val handle = ProcessHandle.of(pid).orElse(null) ?: return null
        val command = handle.info().command().orElse(null) ?: return null
        return File(command).name
    }

    /** 지정한 이름의 프로세스가 현재 소유한 최상위 창을 최소화한다 (차단/확인 화면을 가리지 않도록). */
    fun minimizeForegroundWindow() {
        val hwnd = User32.INSTANCE.GetForegroundWindow() ?: return
        User32.INSTANCE.ShowWindow(hwnd, User32.SW_MINIMIZE)
    }

    /** 지정한 PID의 프로세스를 강제 종료한다. */
    fun killProcess(pid: Long) {
        ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
    }
}
