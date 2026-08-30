package com.phonelock.desktop.monitor

import com.phonelock.desktop.data.Group
import com.phonelock.desktop.data.Repository
import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TICK_MS = 2000L
// 다른 기기가 올린 "공부 타이머 실행 중" 신호가 이보다 오래 갱신 안 됐으면 무시한다(StudyTimerScreen의
// 미러 표시와 같은 값) — 이 신호로 실제 잠금을 걸기 때문에, 신호를 올리던 기기가 정지 없이 꺼져서
// timerActive:true가 유령처럼 영원히 남는 경우 이 기기가 영구히 잠기는 걸 막는 안전장치.
private const val REMOTE_STUDY_SIGNAL_STALE_MS = 20 * 60 * 1000L

private sealed class TickDecision {
    data class NeedsConfirm(val group: Group) : TickDecision()
}

data class UsageOverlayStatus(val remainingSeconds: Int, val level: Int, val isPomodoro: Boolean = false, val levelStepsToMax: Int = 5)

/**
 * 공부앱 타이머가 "공부" 페이즈로 진행 중일 때(휴식 중엔 잠그지 않음) 전체화면 잠금을 걸 때의 상태.
 * 공부앱은 공부 페이즈의 종료 시각을 올리지 않으므로, 경과시간은 관리앱이 잠금을 처음 감지한 로컬
 * 시각(studyStartedAtMillis) 기준 근사치로 보여준다.
 */
data class StudyLockStatus(
    val allowedApps: List<String>,
    val studyStartedAtMillis: Long,
    val isPomodoroMode: Boolean,
    /** 이 기기 자신의 타이머가 아니라, 같은 계정의 다른 기기가 공부 페이즈를 실행 중이라 잠긴 것인지.
     *  이 경우 정지/전환 버튼은 로컬에 실행 중인 타이머가 없어 눌러도 효과가 없으므로 화면에서 숨긴다. */
    val isRemote: Boolean = false
)

class EnforcementService(
    private val repository: Repository,
    private val onBlock: (processName: String, reason: LockReason, blockAttempts: Int) -> Unit,
    private val onConfirm: suspend (processName: String, groupId: Long, waitSeconds: Int) -> Boolean,
    private val onOverlayUpdate: (UsageOverlayStatus?) -> Unit = {},
    private val onStudyLockUpdate: (StudyLockStatus?) -> Unit = {}
) {
    private val evaluator = LockEvaluator(repository)
    private val tickMutex = Mutex()

    // 이 앱 자신은 공부 잠금 허용 목록에 없어도 항상 예외(그렇지 않으면 잠금 화면 자체를 못 띄움).
    private val selfProcessName: String? by lazy {
        runCatching { File(ProcessHandle.current().info().command().orElse(null) ?: return@lazy null).name }.getOrNull()
    }

    // 확인창이 이미 떠 있는 그룹은 다시 확인을 요청하지 않게 막는다. 확인 대기는 tickMutex 밖에서
    // 이뤄지므로(아래 tick() 참고), 이 표시가 없으면 사용자가 확인창에 응답하지 않고 방치하는 동안
    // 매 tick마다 같은 그룹에 대해 또 다른 확인창을 띄우려 시도하게 된다.
    private val pendingConfirmGroupIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    // 공부 잠금이 언제부터 활성 상태였는지(경과시간 근사치 표시용) — 비활성화되면 초기화.
    private var studyLockStartedAt: Long? = null

    /**
     * 포그라운드 창이 바뀌는 즉시(ForegroundWindowChangeWatcher) 재평가하고, 혹시 그 알림을
     * 놓치는 경우에 대비해 기존 주기적 폴링도 안전망으로 계속 돌린다.
     */
    suspend fun run() = coroutineScope {
        ForegroundWindowChangeWatcher.start()
        launch {
            for (unit in ForegroundWindowChangeWatcher.changes) {
                runCatching { tick() }
            }
        }
        launch {
            while (true) {
                delay(TICK_MS)
                runCatching { tick() }
            }
        }
    }

    /**
     * 그룹 조회/잠금 판정/사용시간 적립처럼 빠른 판정만 tickMutex로 직렬화한다. 확인창 응답 대기
     * (onConfirm)는 사용자가 언제 누를지 알 수 없어 오래(때로는 무한히) 걸릴 수 있는데, 예전에는 이
     * 대기까지 같은 뮤텍스 안에서 했기 때문에 사용자가 확인창을 닫지 않고 방치하면(다른 가상 데스크톱
     * 등으로 전환) 뮤텍스가 영구히 풀리지 않아 다른 모든 그룹에 대한 감시까지 전부 멈춰버리는 문제가
     * 있었다. 이제 확인 대기는 뮤텍스 밖에서 수행해 다른 프로세스에 대한 tick은 계속 돌게 한다.
     */
    private suspend fun tick() {
        repository.applyDailyGroupResetIfNeeded()
        repository.checkForUpdateIfNeeded()
        val pid = ForegroundWindowWatcher.currentProcessId() ?: return
        val processName = ForegroundWindowWatcher.currentProcessName() ?: return

        if (checkStudyLock(processName)) return

        val decision = tickMutex.withLock { decide(processName) }
        if (decision == null) {
            onOverlayUpdate(overlayStatusFor(processName))
            return
        }
        onOverlayUpdate(null)
        val group = decision.group

        try {
            val hwnd = ForegroundWindowWatcher.currentForegroundWindowHandle()
            val waitSeconds = repository.getConfirmWaitSeconds(group)
            ForegroundWindowWatcher.minimizeForegroundWindow()
            val confirmed = onConfirm(processName, group.id, waitSeconds)
            if (confirmed) {
                ConfirmationGate.markConfirmed(group.id)
                repository.recordConfirm(group)
                hwnd?.let { ForegroundWindowWatcher.restoreAndFocus(it) }
            } else {
                ForegroundWindowWatcher.killProcess(pid)
            }
        } finally {
            pendingConfirmGroupIds.remove(group.id)
        }
    }

    /**
     * 공부앱 타이머가 "공부" 페이즈로 진행 중이면(휴식 중엔 잠그지 않음) 이 앱 자신과 허용 목록 외
     * 모든 프로그램을 전체화면으로 잠근다. 잠금을 걸었으면(또는 유지 중이면) true를 반환해 이후
     * 그룹 기반 판정을 건너뛴다.
     *
     * 이 기기의 로컬 타이머뿐 아니라, 같은 계정의 다른 기기가 공부 페이즈를 실행 중이라는 신호
     * (PomodoroSyncClient.isStudyTimerActive, StudyTimerScreen의 미러 표시와 같은 값)가 와도 똑같이
     * 잠근다 — 사용자 요청: "한쪽에서 타이머를 실행하면 다른쪽에서도 공부 잠금이 실행되게". 이 기기
     * 자신의 타이머 판정 로직(Repository.isStudyLockActive)은 그대로 두고, 여기서 OR로 넓히기만 한다.
     * 원격 신호는 20분 넘게 갱신이 없으면 유령 상태로 보고 무시한다(REMOTE_STUDY_SIGNAL_STALE_MS) —
     * 그렇지 않으면 신호를 올리던 기기가 정지 없이 꺼졌을 때 이 기기가 영구히 잠길 수 있다.
     */
    private fun checkStudyLock(processName: String): Boolean {
        val localActive = repository.isStudyLockActive()
        val remoteActive = !localActive && isRemoteStudyTimerActive()
        if (!localActive && !remoteActive) {
            studyLockStartedAt = null
            onStudyLockUpdate(null)
            return false
        }
        // 잠금 화면 자체가 전체화면으로 뜨면서 포그라운드 창이 되면, 다음 tick엔 "현재 포그라운드
        // 프로세스"가 이 앱 자신이 된다. 예전엔 그걸 "허용됨"으로 보고 즉시 잠금을 내렸는데, 그러면
        // 잠금 화면 밑에 있던(허용 안 된) 창이 다시 포그라운드가 되어 곧바로 재차단 → 잠금 화면이
        // 다시 포그라운드가 되어 또 내려가는 걸 반복해서 화면이 깜빡이고, 그 사이 클릭한 버튼도
        // 창이 다시 만들어지며 씹혔다. 이 앱 자신이 포그라운드일 땐 기존 상태를 그대로 두고 아무
        // 것도 하지 않는다.
        if (processName.equals(selfProcessName, ignoreCase = true)) return true

        val startedAt = studyLockStartedAt ?: run {
            val remoteStartedAt = if (remoteActive) {
                PomodoroSyncClient.remotePhaseStartedAt(repository.fbDatabaseUrl, repository.fbApiKey)
            } else {
                0L
            }
            (if (remoteStartedAt > 0) remoteStartedAt else System.currentTimeMillis()).also { studyLockStartedAt = it }
        }
        val allowedApps = repository.studyLockAllowedApps
        if (allowedApps.any { it.equals(processName, ignoreCase = true) }) {
            onStudyLockUpdate(null)
            return false
        }
        val isPomodoroMode = if (localActive) {
            repository.isTimerPomodoroMode()
        } else {
            PomodoroSyncClient.isPomodoroMode(repository.fbDatabaseUrl, repository.fbApiKey)
        }
        onStudyLockUpdate(StudyLockStatus(allowedApps, startedAt, isPomodoroMode, isRemote = remoteActive))
        ForegroundWindowWatcher.minimizeForegroundWindow()
        return true
    }

    private fun isRemoteStudyTimerActive(): Boolean {
        val url = repository.fbDatabaseUrl
        val key = repository.fbApiKey
        if (!PomodoroSyncClient.isStudyTimerActive(url, key)) return false
        val updatedAt = PomodoroSyncClient.remoteUpdatedAtMillis(url, key)
        return updatedAt > 0 && System.currentTimeMillis() - updatedAt < REMOTE_STUDY_SIGNAL_STALE_MS
    }

    /**
     * ConfirmationGate(로컬 인메모리 쿨다운)와, 같은 이름의 그룹을 가진 다른 기기가 동기화로 올린
     * 마지막 확인 시각(Repository.syncedLastConfirmedAtEpochMillis) 중 더 나중(더 최근)인 쪽을 기준으로
     * 남은 유예시간을 계산한다. ConfirmationGate 자체의 판정 로직/데이터 구조는 전혀 건드리지 않는다.
     */
    private fun effectiveRemainingCooldownSeconds(group: Group): Int {
        val local = ConfirmationGate.remainingCooldownSeconds(group.id, group.confirmCooldownSeconds)
        val syncedAt = repository.syncedLastConfirmedAtEpochMillis(group)
        val syncedRemaining = if (syncedAt <= 0) {
            0
        } else {
            val elapsedSeconds = (System.currentTimeMillis() - syncedAt) / 1000L
            (group.confirmCooldownSeconds - elapsedSeconds).coerceAtLeast(0L).toInt()
        }
        return maxOf(local, syncedRemaining)
    }

    private fun isRecentlyConfirmedAnyDevice(group: Group): Boolean = effectiveRemainingCooldownSeconds(group) > 0

    /** tickMutex 안에서만 호출된다. 확인이 필요하면 그 결정만 돌려주고, 나머지 판정/부수효과는 여기서 끝낸다. */
    private fun decide(processName: String): TickDecision.NeedsConfirm? {
        val groups = repository.findGroupsForProcess(processName)
        if (groups.isEmpty()) return null

        // 스케줄 => 일일 한도 => 실행 확인 우선순위: 겹치는 그룹 중 하나라도 지금 스케줄/한도로 잠금
        // 조건이면(evaluate()가 스케줄을 한도보다 먼저 검사) 확인창을 띄우지 않고 곧바로 잠근다.
        val lockedEntry = groups.firstNotNullOfOrNull { group -> evaluator.evaluate(group).takeIf { it.locked }?.let { group to it } }
        if (lockedEntry != null) {
            val (group, result) = lockedEntry
            onBlock(processName, result.reason!!, repository.recordBlockAttempt(group.id))
            ForegroundWindowWatcher.minimizeForegroundWindow()
            return null
        }

        // 겹치는 그룹 중 아직 확인을 안 받았고 이미 확인창이 떠 있지 않은 그룹이 있으면 하나씩 확인받는다.
        // 같은 이름의 그룹을 가진 다른 기기가 방금 확인했다면(isRecentlyConfirmedAnyDevice) 이 기기도
        // 재확인 없이 이어서 쓸 수 있다 — 사용자 요청: "한쪽에서 전자 버튼을 누르면 같은 이름의 그룹을
        // 가진 다른 기기도 같은 시간대를 가지고 활동을 진행".
        val needsConfirm = groups.firstOrNull {
            evaluator.isConfirmActiveNow(it) &&
                it.id !in pendingConfirmGroupIds &&
                !isRecentlyConfirmedAnyDevice(it)
        }
        if (needsConfirm != null) {
            pendingConfirmGroupIds.add(needsConfirm.id)
            return TickDecision.NeedsConfirm(needsConfirm)
        }

        groups.forEach { group ->
            if (group.dailyLimitSeconds != null) {
                repository.addUsageSeconds(group.id, (TICK_MS / 1000L).toInt())
            }
        }
        val freshGroups = groups.mapNotNull { repository.getGroup(it.id) }
        val freshLockedEntry = freshGroups.firstNotNullOfOrNull { group -> evaluator.evaluate(group).takeIf { it.locked }?.let { group to it } }
        if (freshLockedEntry != null) {
            val (group, result) = freshLockedEntry
            onBlock(processName, result.reason!!, repository.recordBlockAttempt(group.id))
            ForegroundWindowWatcher.minimizeForegroundWindow()
        }
        return null
    }

    /**
     * 실행확인을 통과하고 유예시간 안에 있는 동안 남은 시간을 오버레이로 보여주기 위한 상태.
     * SiteEnforcement.overlayStatus(브라우저 확장용)와 같은 로직을 프로세스 기준으로 계산한다.
     */
    private fun overlayStatusFor(processName: String): UsageOverlayStatus? {
        val groups = repository.findGroupsForProcess(processName)
        val candidate = groups.firstOrNull { evaluator.isConfirmActiveNow(it) && it.usageOverlayEnabled }
        if (candidate != null) {
            val remaining = effectiveRemainingCooldownSeconds(candidate)
            if (remaining <= 0) {
                OverlayLevelRatchet.reset(candidate.id)
                return null
            }
            val rawLevel = repository.getCurrentLevel(candidate)
            return UsageOverlayStatus(
                remaining,
                OverlayLevelRatchet.displayLevel(candidate.id, rawLevel, remaining),
                levelStepsToMax = candidate.overlayLevelStepsToMax
            )
        }

        // 실행확인 대상이 아니어도, 공부앱 뽀모도로 휴식으로 임시 해제된 그룹이면 "원래는 잠겨야 하는데
        // 휴식이라 임시로 풀려있다"는 걸 알 수 있게 같은 위젯을 기본보다 진한 불투명도로 띄운다.
        val hasPomodoroUnlock = groups.any { it.pomodoroUnlockEnabled && it.usageOverlayEnabled && evaluator.isPomodoroUnlockActive(it) }
        if (!hasPomodoroUnlock) return null
        val phaseEndAt = PomodoroSyncClient.currentPhaseEndAt(repository.fbDatabaseUrl, repository.fbApiKey)
        val remainingBreakSeconds = ((phaseEndAt - System.currentTimeMillis()) / 1000L).toInt()
        if (remainingBreakSeconds <= 0) return null
        return UsageOverlayStatus(remainingBreakSeconds, level = 0, isPomodoro = true)
    }
}
