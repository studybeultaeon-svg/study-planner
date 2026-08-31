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
            // 항상 덮어쓴다(78차부터) — 스크립트 내용이 앱 업데이트로 바뀔 수 있는데, 예전엔 !exists()라
            // 이미 설치된 이전 버전 스크립트가 그대로 남아 새 파라미터(VoiceGender)를 못 받는 문제가 있었다.
            // 사용자 입력이 전혀 섞이지 않는 고정 스크립트라 매번 다시 써도 안전하다.
            writeText(
                """
                    param([string]${'$'}Text, [int]${'$'}Volume, [string]${'$'}VoiceGender)
                    Add-Type -AssemblyName System.Speech
                    ${'$'}synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
                    ${'$'}synth.Volume = [Math]::Max(0, [Math]::Min(100, ${'$'}Volume))
                    # 예전엔 기본(선택 안 함) 음성을 그대로 썼는데, 이 기본값이 한국어일지 영어일지는
                    # Windows 설정마다 달라서 어떤 PC에선 영어 음성으로 한글을 읽는 버그가 있었다(78차,
                    # 사용자 보고로 발견). 성별과 무관하게 항상 한국어(ko) 음성을 먼저 찾고, 그중에서
                    # 요청한 성별이 있으면 그걸, 없으면 한국어 음성 아무거나(대개 여성 Heami)를 쓴다.
                    # 한국어 음성이 하나도 설치 안 돼있는 드문 경우에만 시스템 기본값 그대로 둔다.
                    ${'$'}koVoices = ${'$'}synth.GetInstalledVoices() | Where-Object { ${'$'}_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq 'ko' }
                    ${'$'}target = ${'$'}null
                    if (${'$'}VoiceGender -eq 'MALE') {
                        ${'$'}target = ${'$'}koVoices | Where-Object { ${'$'}_.VoiceInfo.Gender -eq 'Male' } | Select-Object -First 1
                    }
                    if (-not ${'$'}target) { ${'$'}target = ${'$'}koVoices | Select-Object -First 1 }
                    if (${'$'}target) { ${'$'}synth.SelectVoice(${'$'}target.VoiceInfo.Name) }
                    ${'$'}synth.Speak(${'$'}Text)
                    """.trimIndent()
            )
        }
    }

    fun speak(text: String, volumePercent: Int, voiceGender: String = "FEMALE") {
        if (text.isBlank()) return
        runCatching {
            ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", scriptFile.absolutePath, "-Text", text, "-Volume", volumePercent.coerceIn(0, 100).toString(),
                "-VoiceGender", voiceGender
            ).start()
        }
    }
}
