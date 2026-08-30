package com.phonelock.desktop.monitor

import java.io.File

/**
 * 무전기 텍스트 메시지(TTS) 재생 — Windows 내장 SAPI(System.Speech)를 PowerShell로 호출한다. 별도
 * 자바 TTS 라이브러리 의존성을 추가하지 않기 위한 선택.
 *
 * 보안 주의: [text]는 다른 계정(모임 멤버)이 보낸 신뢰할 수 없는 입력이다. PowerShell 커맨드 문자열에
 * 직접 이어붙이면(`-Command "...${text}..."`) 커맨드 인젝션이 가능해지므로, 절대 그렇게 하지 않는다.
 * 대신 고정된(사용자 입력이 전혀 섞이지 않는) 스크립트 파일을 한 번만 만들어두고, 텍스트는 `-File` 뒤에
 * 별도 프로세스 인자(`-Text <값>`)로 넘긴다 — PowerShell의 param() 바인딩은 이 값을 코드가 아니라
 * 순수 데이터(문자열)로만 받아들이므로, 값 안에 따옴표/세미콜론/`$()` 등이 있어도 스크립트로 실행되지
 * 않는다. [ProcessBuilder]도 인자를 리스트로 받아 각 요소를 그대로 하나의 프로세스 인자로 전달할 뿐
 * 셸을 거치지 않는다.
 */
object TtsPlayer {
    private val scriptFile: File by lazy {
        val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PhoneLockDesktop")
        dir.mkdirs()
        File(dir, "speak.ps1").apply {
            if (!exists()) {
                writeText(
                    """
                    param([string]${'$'}Text, [int]${'$'}Volume)
                    Add-Type -AssemblyName System.Speech
                    ${'$'}synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
                    ${'$'}synth.Volume = [Math]::Max(0, [Math]::Min(100, ${'$'}Volume))
                    ${'$'}synth.Speak(${'$'}Text)
                    """.trimIndent()
                )
            }
        }
    }

    fun speak(text: String, volumePercent: Int) {
        if (text.isBlank()) return
        runCatching {
            ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", scriptFile.absolutePath, "-Text", text, "-Volume", volumePercent.coerceIn(0, 100).toString()
            ).start()
        }
    }
}
