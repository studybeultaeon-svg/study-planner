package com.phonelock.app.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 디버그 로그 인앱 뷰어(82차, 감사보고서 §10③) — 실기기 검증이 워크플로 핵심인 이 프로젝트에서
 * "동기화가 왜 실패했는지"를 adb 없이 설정 화면에서 바로 확인할 수 있게 한다. 순환 버퍼(메모리, 최근
 * [MAX_LINES]줄)와 파일 append를 함께 유지 — 프로세스가 죽어도 파일엔 남는다. 판정 로직과 무관한
 * 순수 관측 도구.
 */
object InAppLogger {
    private const val MAX_LINES = 500
    private val buffer = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)

    private fun logFile(context: Context): File = File(context.filesDir, "debug_log.txt")

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        val line = "[${timeFormat.format(System.currentTimeMillis())}] [$tag] $message"
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        runCatching {
            logFile(context).appendText(line + "\n")
        }
    }

    @Synchronized
    fun recentLines(): List<String> = buffer.toList()

    /** 순환 버퍼가 비어있으면(예: 앱 재시작 직후) 파일에서 최근 줄을 읽어 보여준다. */
    fun loadFromFileIfEmpty(context: Context): List<String> {
        if (buffer.isNotEmpty()) return recentLines()
        return runCatching {
            logFile(context).takeIf { it.exists() }
                ?.readLines()
                ?.takeLast(MAX_LINES)
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun clear(context: Context) {
        buffer.clear()
        runCatching { logFile(context).writeText("") }
    }
}
