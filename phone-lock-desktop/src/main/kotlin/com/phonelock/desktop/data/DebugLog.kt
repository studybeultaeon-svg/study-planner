package com.phonelock.desktop.data

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 공부 잠금 버그(BUGS.md "Open")를 실기기에서 눈으로 확인하기 위한 임시 계측용 파일 로거.
 * `%APPDATA%\PhoneLockDesktop\debug.log`에 이어쓰기(append)만 하며 크기 제한/로테이션은 없다 —
 * 디버깅이 끝나면 이 파일과 호출부를 정리할 것.
 */
object DebugLog {
    private val dataDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PhoneLockDesktop")
    private val logFile = File(dataDir, "debug.log")
    private val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    @Synchronized
    fun log(tag: String, message: String) {
        runCatching {
            dataDir.mkdirs()
            logFile.appendText("${LocalDateTime.now().format(formatter)} [$tag] $message\n")
        }
    }
}
