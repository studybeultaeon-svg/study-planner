package com.phonelock.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

private const val WATCHDOG_ARG = "--watchdog"
private const val AUTOSTART_ARG = "--autostart"
private const val WATCHDOG_POLL_MS = 2000L
private const val SUPERVISOR_POLL_MS = 5000L

private fun appDataDir(): File {
    val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PhoneLockDesktop")
    dir.mkdirs()
    return dir
}

private fun mainLockFile() = File(appDataDir(), "app.lock")
private fun watchdogLockFile() = File(appDataDir(), "watchdog.lock")

// GC로 잠금 채널이 수거되면(닫히면서) 잠금이 풀려버리므로 프로세스 수명 내내 참조를 붙잡아둔다.
private var watchdogLockChannel: FileChannel? = null

/**
 * 사용자가 트레이의 "종료"를 정상적으로(대기시간을 다 채우고) 눌러서 나갈 때만 만들어두는 표식.
 * 감시 프로세스는 이 파일이 있으면 "의도된 종료"로 보고 다시 실행시키지 않는다.
 */
fun intentionalExitFlagFile(): File = File(appDataDir(), "intentional_exit.flag")

/** 현재 프로세스를 실행시킨 네이티브 런처(예: PhoneLockDesktop.exe)의 경로. 감시/재실행에 사용한다. */
private fun exeLauncherPath(): String? =
    ProcessHandle.current().info().command().orElse(null)

/**
 * 지정한 잠금 파일이 지금 이 순간 아무도 안 잡고 있는지(즉 그 프로세스가 죽어있는지) 확인한다.
 * Windows의 파일 잠금은 프로세스가 죽으면(강제종료 포함) OS가 자동으로 해제하므로,
 * 별도의 heartbeat 없이 이 잠금 상태만으로 생사를 판단할 수 있다.
 */
private fun isLockFree(file: File): Boolean {
    return runCatching {
        RandomAccessFile(file, "rw").channel.use { channel ->
            val lock = channel.tryLock()
            if (lock != null) {
                lock.release()
                true
            } else {
                false
            }
        }
    }.getOrDefault(false)
}

/** 감시 프로세스가 안 떠 있으면 하나 띄운다. 메인 앱이 주기적으로 호출해서 감시 프로세스가 죽어도 되살린다. */
private fun spawnWatchdogIfNeeded() {
    if (!isLockFree(watchdogLockFile())) return
    val exe = exeLauncherPath() ?: return
    runCatching { ProcessBuilder(exe, WATCHDOG_ARG).start() }
}

/** 메인 앱 시작 시 호출: 감시 프로세스를 즉시 하나 띄우고, 이후 계속 살아있는지 주기적으로 확인/재기동한다. */
fun startWatchdogSupervisor() {
    spawnWatchdogIfNeeded()
    Thread({
        while (true) {
            Thread.sleep(SUPERVISOR_POLL_MS)
            runCatching { spawnWatchdogIfNeeded() }
        }
    }, "WatchdogSupervisor").apply { isDaemon = true; start() }
}

/**
 * `--watchdog` 인자로 실행됐을 때의 진입점. 창 없이 백그라운드에서 메인 앱의 생사만 지켜보다가,
 * 강제종료 등으로 죽은 게 확인되면(그리고 의도된 종료 표식이 없다면) 즉시 다시 실행시킨다.
 * 표식이 있으면(정식 종료 절차를 거쳐 나간 경우) 되살리지 않고 계속 표식만 지켜본다 — 표식은
 * 사용자가 앱을 직접(수동으로) 다시 켤 때만 지워지므로, 그 전까지는 스케줄러가 몇 번을 재시도해도
 * 계속 꺼진 채로 남는다.
 */
fun runWatchdog() {
    val myChannel = RandomAccessFile(watchdogLockFile(), "rw").channel
    myChannel.tryLock() ?: return // 이미 다른 감시 프로세스가 실행 중
    watchdogLockChannel = myChannel
    val exe = exeLauncherPath()

    while (true) {
        Thread.sleep(WATCHDOG_POLL_MS)
        if (isLockFree(mainLockFile()) && !intentionalExitFlagFile().exists() && exe != null) {
            runCatching { ProcessBuilder(exe).start() }
        }
    }
}

fun isWatchdogArg(args: Array<String>): Boolean = args.contains(WATCHDOG_ARG)

/** 작업 스케줄러가 자동으로 실행시켰는지 여부. 사용자가 직접 실행한 경우와 구분하는 데 쓴다. */
fun isAutoStartArg(args: Array<String>): Boolean = args.contains(AUTOSTART_ARG)

/**
 * 프로세스 2개(메인+감시)를 한꺼번에("작업 관리자에서 관련된 항목 모두 종료" 등) 죽이면
 * 서로가 서로를 살릴 수 없는 근본적인 한계가 있다. 이를 보완하기 위해 우리 프로세스와 완전히
 * 무관한 OS 스케줄러(작업 스케줄러)에 "1분마다 앱을 실행"하는 작업을 등록해둔다. 이미 실행 중이면
 * 기존 단일 인스턴스 잠금에 걸려 조용히 아무 일도 안 하고 끝나므로 중복 실행 걱정은 없다.
 * `--autostart` 인자를 붙여서, 사용자가 직접 실행한 것과 구분할 수 있게 한다.
 */
fun registerAutoStartScheduledTask() {
    val exe = exeLauncherPath() ?: return
    runCatching {
        ProcessBuilder(
            "schtasks", "/create", "/tn", "PhoneLockDesktopWatchdog",
            "/tr", "\"$exe\" $AUTOSTART_ARG", "/sc", "minute", "/mo", "1", "/f"
        ).start()
    }
}
