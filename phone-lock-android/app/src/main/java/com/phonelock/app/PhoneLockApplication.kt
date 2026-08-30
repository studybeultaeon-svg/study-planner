package com.phonelock.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 실기기에서 원인을 알 수 없는 즉시 종료(크래시)가 보고됐는데 이 세션에는 adb/로그캣 접근이 없어서
 * 추측으로 대응했다 — 다음에도 재현되면 이 파일(`crash_log.txt`, 앱 내부 저장소)을 확인해 실제
 * 스택트레이스로 원인을 특정할 수 있게 남겨둔다. 기본 핸들러는 그대로 두고(시스템 크래시 다이얼로그 등
 * 정상 동작 유지) 그 앞에서 파일에 기록만 추가한다.
 */
class PhoneLockApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                File(filesDir, "crash_log.txt").appendText("\n=== $timestamp (thread=${thread.name}) ===\n$sw")
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
