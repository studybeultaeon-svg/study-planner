package com.phonelock.desktop.monitor

import com.phonelock.desktop.data.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.BufferedWriter
import java.io.File
import kotlin.concurrent.thread

/**
 * 차단(잠김/실행확인)·공부 잠금 화면에서 백그라운드로 재생 중인 음악 앱(Spotify 등)을 창을 열지 않고
 * 제어한다(125차, 사용자 요청). Windows 시스템 미디어 제어(GSMTC,
 * `GlobalSystemMediaTransportControlsSessionManager`)로 앱별 미디어 세션을 찾아 그 세션에만 명령을 보낸다
 * — 권한이 필요 없고, 창이 최소화돼 있어도 동작한다.
 *
 * GSMTC는 WinRT API라 JVM에서 직접 부를 수 없어 [TtsPlayer]처럼 고정 PowerShell 스크립트를 쓴다. 다만
 * 매번 프로세스를 띄우면 몇 시간씩 떠 있는 공부 잠금 화면에서 부담이 크므로, 카드가 보이는 동안만
 * 헬퍼 프로세스 하나를 유지한다([acquire]/[release] 참조 카운트).
 *
 * 헬퍼 ↔ 앱 프로토콜(한 줄 단위, UTF-8):
 * - 헬퍼 → 앱: 세션 목록이 바뀔 때마다 JSON 배열 한 줄 `[{"id","title","artist","playing"}, ...]`
 * - 앱 → 헬퍼: `toggle|next|prev` + 탭 + 세션 id. id는 시스템이 준 값을 그대로 돌려줄 뿐이고, 스크립트는
 *   이 값을 세션 비교(-eq)에만 쓰므로 코드로 실행되지 않는다.
 * 앱이 종료돼 표준입력이 닫히면 헬퍼도 스스로 끝난다.
 */
object MediaSessionBridge {
    data class Session(val appId: String, val title: String, val artist: String, val isPlaying: Boolean)

    enum class Action(val verb: String) { PREVIOUS("prev"), PLAY_PAUSE("toggle"), NEXT("next") }

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val lock = Any()
    private var users = 0
    private var process: Process? = null
    private var writer: BufferedWriter? = null

    fun acquire() = synchronized(lock) {
        users++
        if (process?.isAlive != true) start()
    }

    fun release() = synchronized(lock) {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    fun send(appId: String, action: Action) {
        if (appId.any { it == '\t' || it == '\r' || it == '\n' }) return
        synchronized(lock) {
            runCatching { writer?.apply { write("${action.verb}\t$appId\n"); flush() } }
        }
    }

    /**
     * GSMTC 앱 id가 [processName](예: "Spotify.exe") 프로그램의 것인지. Win32 앱은 id가 실행 파일 이름
     * ("Spotify.exe", "Chrome", "MSEdge")이고 스토어 앱은 패키지 이름("SpotifyAB.SpotifyMusic_…!Spotify")이라
     * 확장자를 뗀 이름이 id에 들어있는지로 판단한다(너무 짧은 이름은 오탐이 많아 제외).
     */
    fun matchesProcess(appId: String, processName: String): Boolean {
        val base = processName.trim().removeSuffix(".exe").removeSuffix(".EXE")
        return base.length >= 3 && appId.contains(base, ignoreCase = true)
    }

    /** 화면에 보여줄 앱 이름 — id의 마지막 조각에서 경로/확장자를 뗀다. */
    fun displayName(appId: String): String =
        appId.substringAfterLast('!').substringAfterLast('\\').removeSuffix(".exe").ifBlank { appId }

    private fun start() {
        val proc = runCatching {
            ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", scriptFile.absolutePath
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrElse { e ->
            DebugLog.log("MediaSessionBridge", "helper start failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        process = proc
        writer = proc.outputStream.bufferedWriter(Charsets.UTF_8)
        thread(isDaemon = true, name = "MediaSessionBridge") {
            runCatching {
                proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    val parsed = parse(line) ?: return@forEachLine
                    synchronized(lock) { if (process === proc) _sessions.value = parsed }
                }
            }
            synchronized(lock) {
                if (process === proc) {
                    DebugLog.log("MediaSessionBridge", "helper exited: ${runCatching { proc.exitValue() }.getOrNull()}")
                    process = null
                    writer = null
                    _sessions.value = emptyList()
                }
            }
        }
    }

    private fun stop() {
        process?.destroy()
        process = null
        writer = null
        _sessions.value = emptyList()
    }

    private fun parse(line: String): List<Session>? = runCatching {
        val array = JSONArray(line)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Session(
                appId = o.optString("id"),
                title = o.optString("title"),
                artist = o.optString("artist"),
                isPlaying = o.optBoolean("playing")
            )
        }.filter { it.appId.isNotBlank() }
    }.getOrNull()

    private val scriptFile: File by lazy {
        val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PhoneLockDesktop")
        dir.mkdirs()
        File(dir, "media_sessions.ps1").apply {
            // TtsPlayer와 같이 항상 덮어쓴다(앱 업데이트로 스크립트가 바뀔 수 있음). 이 스크립트에는 한글을
            // 넣지 않는다 — BOM 없는 UTF-8을 PowerShell 5.1이 시스템 코드페이지로 읽는 문제(BUGS.md 61차).
            writeText(
                """
                ${'$'}ErrorActionPreference = 'Stop'
                # Raw UTF-8 streams: [Console]::OutputEncoding does not apply to redirected output in PS 5.1.
                ${'$'}utf8 = New-Object System.Text.UTF8Encoding ${'$'}false
                ${'$'}stdin = New-Object System.IO.StreamReader([Console]::OpenStandardInput(), ${'$'}utf8)
                ${'$'}stdout = New-Object System.IO.StreamWriter([Console]::OpenStandardOutput(), ${'$'}utf8)
                ${'$'}stdout.AutoFlush = ${'$'}true
                Add-Type -AssemblyName System.Runtime.WindowsRuntime
                ${'$'}asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
                    ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and
                    ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
                })[0]
                function Await(${'$'}op, [Type]${'$'}type) {
                    ${'$'}task = ${'$'}asTask.MakeGenericMethod(${'$'}type).Invoke(${'$'}null, @(${'$'}op))
                    if (-not ${'$'}task.Wait(3000)) { return ${'$'}null }
                    return ${'$'}task.Result
                }
                ${'$'}managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
                ${'$'}propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]
                ${'$'}manager = Await (${'$'}managerType::RequestAsync()) ${'$'}managerType
                if (${'$'}null -eq ${'$'}manager) { exit 1 }

                # A command line is "<verb><TAB><session id>". The id is only compared, never executed.
                ${'$'}pending = ${'$'}stdin.ReadLineAsync()
                ${'$'}last = ${'$'}null
                ${'$'}wait = 1000
                while (${'$'}true) {
                    while (${'$'}pending.IsCompleted) {
                        ${'$'}line = ${'$'}pending.Result
                        if (${'$'}null -eq ${'$'}line) { exit 0 }
                        ${'$'}parts = ${'$'}line.Split("`t")
                        if (${'$'}parts.Length -eq 2) {
                            ${'$'}target = ${'$'}null
                            foreach (${'$'}s in ${'$'}manager.GetSessions()) {
                                if (${'$'}s.SourceAppUserModelId -eq ${'$'}parts[1]) { ${'$'}target = ${'$'}s; break }
                            }
                            if (${'$'}null -ne ${'$'}target) {
                                try {
                                    switch (${'$'}parts[0]) {
                                        'toggle' { ${'$'}null = Await (${'$'}target.TryTogglePlayPauseAsync()) ([bool]) }
                                        'next' { ${'$'}null = Await (${'$'}target.TrySkipNextAsync()) ([bool]) }
                                        'prev' { ${'$'}null = Await (${'$'}target.TrySkipPreviousAsync()) ([bool]) }
                                    }
                                } catch { }
                            }
                        }
                        ${'$'}pending = ${'$'}stdin.ReadLineAsync()
                        # The app needs a moment to apply a command; look again soon instead of after a full second.
                        ${'$'}wait = 250
                    }

                    ${'$'}list = New-Object System.Collections.ArrayList
                    foreach (${'$'}s in ${'$'}manager.GetSessions()) {
                        try {
                            ${'$'}props = Await (${'$'}s.TryGetMediaPropertiesAsync()) ${'$'}propsType
                            ${'$'}status = ${'$'}s.GetPlaybackInfo().PlaybackStatus
                            [void]${'$'}list.Add([ordered]@{
                                id = [string]${'$'}s.SourceAppUserModelId
                                title = [string]${'$'}props.Title
                                artist = [string]${'$'}props.Artist
                                playing = (${'$'}status -eq 'Playing')
                            })
                        } catch { }
                    }
                    ${'$'}json = ConvertTo-Json -InputObject @(${'$'}list) -Compress -Depth 3
                    if (${'$'}json -ne ${'$'}last) {
                        ${'$'}stdout.WriteLine(${'$'}json)
                        ${'$'}last = ${'$'}json
                    }
                    # Wakes up early when a command arrives.
                    [void]${'$'}pending.Wait(${'$'}wait)
                    ${'$'}wait = 1000
                }
                """.trimIndent()
            )
        }
    }
}
