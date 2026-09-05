package com.phonelock.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.phonelock.desktop.data.DebugLog
import com.phonelock.desktop.data.*
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.routine.DesktopNotifier
import com.phonelock.desktop.routine.RoutineNotifier
import com.phonelock.desktop.routine.SocialGroupNotifier
import com.phonelock.desktop.routine.VoiceMessageNotifier
import com.phonelock.desktop.routine.WeeklySummaryNotifier
import com.sun.jna.Native
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.phonelock.desktop.monitor.EnforcementService
import com.phonelock.desktop.monitor.LockReason
import com.phonelock.desktop.monitor.StudyLockStatus
import com.phonelock.desktop.monitor.UsageOverlayStatus
import com.phonelock.desktop.net.LocalApiServer
import com.phonelock.desktop.ui.AccountGate
import com.phonelock.desktop.ui.BlockScreen
import com.phonelock.desktop.ui.ConfirmScreen
import com.phonelock.desktop.ui.ExitConfirmScreen
import com.phonelock.desktop.ui.MainScreen
import com.phonelock.desktop.ui.SunriseIcon
import com.phonelock.desktop.ui.StudyLockScreen
import com.phonelock.desktop.ui.UsageOverlayContent
import com.phonelock.desktop.ui.theme.PhoneLockTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * "chrome.exe" 같은 맨 이름만으로 실행 파일을 찾는다. `ProcessBuilder(appName)`은 PATH만 뒤지고,
 * `cmd /c start`는 매번 콘솔 창이 잠깐 떴다 사라지는 데다(디스코드처럼 못 찾는 경우엔 창이 안 닫히고
 * 남기까지 했다) 실패해도 cmd 자체는 정상 종료라 실패 감지가 안 됐다 — 2026-08-07, 32차 세션 이후
 * 사용자 재현. Chrome/Edge류는 레지스트리 App Paths에 등록돼 있고, Discord류(Electron 설치형)는
 * 대개 없는 대신 시작 메뉴 바로가기(.lnk)는 있으므로 그것까지 순서대로 찾는다.
 */
private fun resolveAppPath(appName: String): File? {
    val exeName = if (appName.endsWith(".exe", ignoreCase = true)) appName else "$appName.exe"
    val nameNoExt = exeName.removeSuffix(".exe").removeSuffix(".EXE")
    val appPathsKey = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$exeName"
    for (root in listOf(WinReg.HKEY_CURRENT_USER, WinReg.HKEY_LOCAL_MACHINE)) {
        val resolved = runCatching {
            if (Advapi32Util.registryKeyExists(root, appPathsKey)) {
                Advapi32Util.registryGetStringValue(root, appPathsKey, "").takeIf { it.isNotBlank() }
            } else null
        }.getOrNull()
        if (resolved != null && File(resolved).isFile) return File(resolved)
    }
    val startMenuDirs = listOfNotNull(
        System.getenv("ProgramData")?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") },
        System.getenv("APPDATA")?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") }
    )
    for (dir in startMenuDirs) {
        if (!dir.isDirectory) continue
        val shortcut = runCatching {
            dir.walkTopDown()
                .firstOrNull { it.isFile && it.extension.equals("lnk", ignoreCase = true) && it.nameWithoutExtension.equals(nameNoExt, ignoreCase = true) }
        }.getOrNull()
        if (shortcut != null) return shortcut
    }
    return null
}

/**
 * 사용 중 오버레이가 화면 전체를 덮으면서도 마우스 클릭은 아래 프로그램으로 그대로 전달되도록
 * (안드로이드 접근성 오버레이/브라우저 확장의 pointer-events:none과 동등한 효과) Win32
 * WS_EX_TRANSPARENT 확장 스타일을 추가한다. Compose Desktop 창 자체엔 이런 클릭-통과 옵션이
 * 없어(코너 위젯으로 축소해둔 원래 이유) 네이티브 호출이 필요하다.
 */
private fun makeClickThrough(window: java.awt.Window) {
    runCatching {
        val hwnd = HWND(Native.getComponentPointer(window))
        val exStyle = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, exStyle or WinUser.WS_EX_LAYERED or WinUser.WS_EX_TRANSPARENT)
    }.onFailure { e -> DebugLog.log("UsageOverlay", "클릭-통과 설정 실패: ${e.javaClass.simpleName}: ${e.message}") }
}

private data class BlockRequest(val processName: String, val reason: LockReason, val blockAttempts: Int)

private data class ConfirmRequest(
    val processName: String,
    val groupId: Long,
    val waitSeconds: Int,
    val result: CompletableDeferred<Boolean>
)

// 잠금 파일/채널을 프로세스 종료 시까지 참조로 붙잡아둬야 잠금이 풀리지 않는다.
private var lockFileChannel: FileChannel? = null

/** 이미 다른 인스턴스가 실행 중이면 false를 반환한다 (중복 실행 방지). */
private fun acquireSingleInstanceLock(): Boolean {
    val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PhoneLockDesktop")
    dir.mkdirs()
    val lockFile = File(dir, "app.lock")
    val channel = RandomAccessFile(lockFile, "rw").channel
    channel.tryLock() ?: return false
    lockFileChannel = channel
    return true
}

fun main(args: Array<String>) {
    if (isWatchdogArg(args)) {
        runWatchdog()
        return
    }
    // 작업 스케줄러가 1분마다 자동으로 실행을 시도하는 것일 뿐인데, 방금 정식 종료 절차(10분 대기)를
    // 거쳐 나간 상태라면 존중하고 켜지 않는다. 반면 사용자가 직접(수동으로) 실행한 경우에는 그 자체가
    // "다시 켜겠다"는 의사표시이므로 표식이 있어도 정상적으로 켠다.
    if (isAutoStartArg(args) && intentionalExitFlagFile().exists()) {
        return
    }
    intentionalExitFlagFile().delete()
    if (!acquireSingleInstanceLock()) {
        return
    }
    registerAutoStartScheduledTask()
    startWatchdogSupervisor()
    startApp()
}

private fun startApp() = application {
    val repository = remember { Repository() }
    var themeMode by remember { mutableStateOf(repository.themeMode) }
    // 79차: 커스텀 테마는 themeMode 문자열("CUSTOM")이 안 바뀌어도 색상만 바뀔 수 있어서, 그 경우도
    // 반드시 재계산되도록 별도 카운터를 함께 key로 쓴다(SettingsScreen이 색을 바꿀 때마다 증가).
    var themeRefreshTick by remember { mutableStateOf(0) }
    val palette = remember(themeMode, themeRefreshTick) { repository.currentPalette() }
    var mainWindowVisible by remember { mutableStateOf(true) }
    var blockRequest by remember { mutableStateOf<BlockRequest?>(null) }
    var confirmRequest by remember { mutableStateOf<ConfirmRequest?>(null) }
    var exitConfirmVisible by remember { mutableStateOf(false) }
    var overlayStatus by remember { mutableStateOf<UsageOverlayStatus?>(null) }
    var studyLockStatus by remember { mutableStateOf<StudyLockStatus?>(null) }
    var studyLockToast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val apiServer = LocalApiServer(repository)
        apiServer.start()

        val service = EnforcementService(
            repository = repository,
            onBlock = { processName, reason, blockAttempts -> blockRequest = BlockRequest(processName, reason, blockAttempts) },
            onConfirm = { processName, groupId, waitSeconds ->
                val deferred = CompletableDeferred<Boolean>()
                confirmRequest = ConfirmRequest(processName, groupId, waitSeconds, deferred)
                deferred.await()
            },
            onOverlayUpdate = { status -> overlayStatus = status },
            onStudyLockUpdate = { status -> studyLockStatus = status }
        )
        service.run()
    }

    val trayState = rememberTrayState()
    // 82차(감사 후속): 예전엔 30초/7초 주기 루프 2개가 각자 따로 돌았다 — 하나의 7초 티커로 합치고,
    // 30초 주기 작업은 누적 경과시간으로 4번에 1번(≈30초)만 실행해 기존 주기를 그대로 유지한다.
    // "무전기"(VoiceMessageNotifier)는 켜짐/모드/일정이 모임마다 달라 전역 스위치가 없으므로 매번 돌리고,
    // 어느 모임에서도 안 켜져 있으면 실질적으로 아무 일도 하지 않는다.
    LaunchedEffect(trayState) {
        DesktopNotifier.trayState = trayState
        var msSinceSlowTick = 0L
        while (true) {
            VoiceMessageNotifier.tick(repository)
            msSinceSlowTick += 7_000L
            // 안드로이드는 AlarmManager로 정확히 예약하지만 데스크탑엔 그런 API가 없어 직접 경과시간을 비교한다(52차).
            if (msSinceSlowTick >= 30_000L) {
                msSinceSlowTick = 0L
                RoutineNotifier.tick(repository)
                SocialGroupNotifier.tick(repository)
                runCatching { WeeklySummaryNotifier.tick(repository) } // 82차: 매주 일요일 20시, 내부적으로 날짜 가드됨
                runCatching { repository.runDailyMaintenanceIfNeeded() } // 82차: 12개월 정리+클라우드 백업 자동화, 내부적으로 날짜 가드됨
            }
            delay(7_000L)
        }
    }

    // 79차(사용자 요청): "정말 종료?" 20문항 확인 절차는 관리(차단) 기능의 꼼수 방지 장치이지 앱
    // 전체 필수 기능은 아니라는 판단 — 설정 > 관리 탭에서 껐다면 이 확인 없이 바로 종료.
    fun doExit() {
        runCatching { intentionalExitFlagFile().createNewFile() }
        repository.flushPendingUsage()
        exitApplication()
    }
    Tray(
        icon = SunriseIcon,
        state = trayState,
        tooltip = "갓생살기종합세트",
        onAction = { mainWindowVisible = true },
        menu = {
            Item("열기", onClick = { mainWindowVisible = true })
            Item("종료", onClick = { if (repository.exitConfirmEnabled) exitConfirmVisible = true else doExit() })
        }
    )

    if (mainWindowVisible) {
        Window(
            onCloseRequest = { mainWindowVisible = false },
            title = "갓생살기종합세트",
            icon = SunriseIcon
        ) {
            PhoneLockTheme(palette) {
                // MaterialTheme은 색상 팔레트만 정의할 뿐 실제로 캔버스를 칠하진 않는다 — 이 Surface가
                // 없으면 MainScreen이 덮지 않는 여백(패딩 등)이 Window 기본 배경(흰색)으로 비쳐 보인다.
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    AccountGate(repository) {
                        MainScreen(repository, onThemeChange = { themeMode = it; themeRefreshTick++ })
                    }
                }
            }
        }
    }

    blockRequest?.let { req ->
        Window(
            onCloseRequest = { blockRequest = null },
            title = "차단됨",
            undecorated = true,
            alwaysOnTop = true,
            state = rememberWindowState(placement = WindowPlacement.Maximized)
        ) {
            PhoneLockTheme(palette) {
                BlockScreen(req.reason, req.blockAttempts) { blockRequest = null }
            }
        }
    }

    if (studyLockStatus != null && blockRequest == null && confirmRequest == null && !exitConfirmVisible) {
        Window(
            onCloseRequest = {},
            title = "공부 잠금",
            undecorated = true,
            alwaysOnTop = true,
            state = rememberWindowState(placement = WindowPlacement.Maximized)
        ) {
            // checkStudyLock()이 잠금을 걸기 직전에 대상 창을 minimizeForegroundWindow()로
            // 최소화하는데, 그 직후에 뜨는 이 창이 자동으로 포커스를 못 받는 경우가 있다(ConfirmScreen과
            // 같은 문제 — Main.kt의 ConfirmScreen 쪽 주석 참고). 포커스를 못 받으면 버튼을 눌러도
            // 클릭이 이 창으로 전달되지 않아 전부 먹통이 된다. 창이 뜨자마자 명시적으로 포커스를 가져온다.
            LaunchedEffect(Unit) {
                window.toFront()
                window.requestFocus()
            }
            PhoneLockTheme(palette) {
                studyLockStatus?.let { status ->
                    StudyLockScreen(
                        status = status,
                        toastMessage = studyLockToast,
                        onLaunchApp = { appName ->
                            val resolved = resolveAppPath(appName)
                            runCatching {
                                when {
                                    // Desktop.open()은 .lnk를 열 때 JDK에서 "Unsupported URI content"로
                                    // 실패한다(실기기 재현, 2026-08-07) — explorer.exe에 바로가기 경로를
                                    // 넘기면 창 깜빡임 없이 탐색기가 대신 풀어서 실행해준다.
                                    resolved != null && resolved.extension.equals("lnk", ignoreCase = true) ->
                                        ProcessBuilder("explorer.exe", resolved.absolutePath).start()
                                    resolved != null -> ProcessBuilder(resolved.absolutePath).start()
                                    else -> ProcessBuilder(appName).start()
                                }
                            }
                                .onSuccess { DebugLog.log("LaunchApp", "appName=$appName resolved=${resolved?.absolutePath} 실행 성공") }
                                .onFailure { e ->
                                    DebugLog.log("LaunchApp", "appName=$appName resolved=${resolved?.absolutePath} 실행 실패: ${e.javaClass.simpleName}: ${e.message}")
                                    studyLockToast = "\"$appName\" 실행 실패: 설치 경로를 찾지 못했습니다. 시작 메뉴에 표시되는 이름으로 다시 등록해보세요."
                                }
                        },
                        onStopTimer = { repository.timerStop() },
                        onSwitchToBreak = { repository.timerSwitchPhase() },
                        onToastShown = { studyLockToast = null }
                    )
                }
            }
        }
    }

    if (exitConfirmVisible) {
        Window(
            onCloseRequest = { exitConfirmVisible = false },
            title = "종료 확인",
            undecorated = true,
            alwaysOnTop = true,
            state = rememberWindowState(placement = WindowPlacement.Maximized)
        ) {
            PhoneLockTheme(palette) {
                ExitConfirmScreen(
                    onConfirmExit = {
                        exitConfirmVisible = false
                        doExit()
                    },
                    onCancel = { exitConfirmVisible = false }
                )
            }
        }
    }

    if (overlayStatus != null && blockRequest == null && confirmRequest == null && !exitConfirmVisible) {
        Window(
            onCloseRequest = {},
            title = "",
            undecorated = true,
            alwaysOnTop = true,
            focusable = false,
            resizable = false,
            transparent = true,
            state = rememberWindowState(placement = WindowPlacement.Maximized)
        ) {
            // 안드로이드/브라우저 확장은 pointer-events:none으로 전체화면 위에 덮지만, Compose Desktop
            // 창은 클릭까지 가로챈다 — 창 자체를 진짜 투명(transparent=true)하게 만들고 Win32
            // WS_EX_TRANSPARENT로 클릭 통과까지 줘야 아래 프로그램을 그대로 쓸 수 있다.
            LaunchedEffect(Unit) { makeClickThrough(window) }
            overlayStatus?.let { status -> PhoneLockTheme(palette) { UsageOverlayContent(status) } }
        }
    }

    confirmRequest?.let { req ->
        Window(
            onCloseRequest = {
                req.result.complete(false)
                confirmRequest = null
            },
            title = "실행 확인",
            undecorated = true,
            alwaysOnTop = true,
            state = rememberWindowState(placement = WindowPlacement.Maximized)
        ) {
            // 직전에 대상 앱 창을 네이티브로 최소화하는 것과 겹치면 이 창이 자동으로
            // 포커스를 못 받는 경우가 있어(그러면 대기시간 타이머가 영원히 멈춰있음),
            // 창이 뜨자마자 명시적으로 포커스를 가져온다.
            LaunchedEffect(Unit) {
                window.toFront()
                window.requestFocus()
            }
            PhoneLockTheme(palette) {
                ConfirmScreen(
                    processName = req.processName,
                    waitSeconds = req.waitSeconds,
                    level = remember(req.groupId) { repository.getGroup(req.groupId)?.let { repository.getCurrentLevel(it) } ?: 0 },
                    repository = repository,
                    groupId = req.groupId,
                    onYes = {
                        req.result.complete(true)
                        confirmRequest = null
                    },
                    onNo = {
                        req.result.complete(false)
                        confirmRequest = null
                    }
                )
            }
        }
    }
}
