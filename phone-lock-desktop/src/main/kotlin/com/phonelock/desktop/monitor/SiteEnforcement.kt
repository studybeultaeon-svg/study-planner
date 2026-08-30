package com.phonelock.desktop.monitor

import com.phonelock.desktop.data.Group
import com.phonelock.desktop.data.Repository

// EnforcementService.REMOTE_STUDY_SIGNAL_STALE_MS와 같은 값 — 다른 기기의 "공부 타이머 실행 중" 신호가
// 이보다 오래 갱신 안 됐으면 무시한다(신호를 올리던 기기가 정지 없이 꺼져 유령처럼 남는 경우 대비).
private const val REMOTE_STUDY_SIGNAL_STALE_MS = 20 * 60 * 1000L

sealed class CheckResult {
    object Allow : CheckResult()
    data class Confirm(val groupId: Long, val waitSeconds: Int, val level: Int = 0) : CheckResult()
    data class Block(val reason: LockReason, val attempts: Int = 0) : CheckResult()
}

/**
 * 브라우저 확장프로그램이 로컬 API를 통해 도메인 방문을 물어볼 때 쓰는 판정 로직.
 * EnforcementService(프로그램용)와 그룹 단위 ConfirmationGate를 공유해서, 그룹 내 한 사이트에서
 * 확인을 마치면 같은 그룹의 다른 사이트/프로그램도 쿨다운 동안 다시 묻지 않는다.
 */
class SiteEnforcement(private val repository: Repository) {
    private val evaluator = LockEvaluator(repository)

    /**
     * 공부앱 타이머가 "공부" 페이즈로 진행 중이면(휴식 중엔 아님) 공부앱 타이머 탭에 등록된 허용 사이트
     * 목록(안드로이드와 공유하는 같은 Firebase 값) 외 모든 사이트를 차단한다. 그룹 판정보다 먼저
     * 확인해서, 그룹에 등록 안 된 사이트라도 공부 잠금 중이면 걸리게 한다.
     */
    private fun isBlockedByStudyLock(hostname: String): Boolean {
        // 이 기기의 로컬 타이머뿐 아니라, 같은 계정의 다른 기기가 공부 페이즈를 실행 중이라는 신호가
        // 와도 똑같이 차단한다(EnforcementService.checkStudyLock의 프로그램 차단과 동일 원칙 — 사용자
        // 요청: 한쪽에서 타이머를 실행하면 다른쪽에서도 공부 잠금이 실행되게).
        val active = repository.isStudyLockActive() || isRemoteStudyTimerActive()
        if (!active) return false
        val allowedSites = repository.studyLockAllowedSites
        val host = hostname.lowercase()
        return allowedSites.none { site ->
            val d = site.lowercase()
            host == d || host.endsWith(".$d")
        }
    }

    private fun isRemoteStudyTimerActive(): Boolean {
        val url = repository.fbDatabaseUrl
        val key = repository.fbApiKey
        if (!PomodoroSyncClient.isStudyTimerActive(url, key)) return false
        val updatedAt = PomodoroSyncClient.remoteUpdatedAtMillis(url, key)
        return updatedAt > 0 && System.currentTimeMillis() - updatedAt < REMOTE_STUDY_SIGNAL_STALE_MS
    }

    /**
     * ConfirmationGate(로컬)와, 같은 이름의 그룹을 가진 다른 기기가 동기화로 올린 마지막 확인 시각
     * 중 더 나중인 쪽 기준으로 남은 유예시간을 계산한다. EnforcementService.effectiveRemainingCooldownSeconds와
     * 같은 로직(플랫폼 내 다른 판정 경로라 대칭 복제) — ConfirmationGate 자체는 건드리지 않는다.
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

    fun check(hostname: String): CheckResult {
        if (isBlockedByStudyLock(hostname)) return CheckResult.Block(LockReason.STUDY_LOCK)

        val groups = repository.findGroupsForDomain(hostname)
        if (groups.isEmpty()) return CheckResult.Allow

        // 스케줄 => 일일 한도 => 실행 확인 우선순위: evaluate()가 스케줄을 한도보다 먼저 검사하므로
        // 겹치는 그룹 중 하나라도 지금 스케줄/한도로 잠금 조건이면 확인창 없이 곧바로 잠근다.
        val lockedEntry = groups.firstNotNullOfOrNull { group -> evaluator.evaluate(group).takeIf { it.locked }?.let { group to it } }
        if (lockedEntry != null) {
            val (group, result) = lockedEntry
            return CheckResult.Block(result.reason!!, repository.recordBlockAttempt(group.id))
        }

        val needsConfirm = groups.firstOrNull {
            evaluator.isConfirmActiveNow(it) && !isRecentlyConfirmedAnyDevice(it)
        }
        return if (needsConfirm != null) {
            CheckResult.Confirm(needsConfirm.id, repository.getConfirmWaitSeconds(needsConfirm), repository.getCurrentLevel(needsConfirm))
        } else CheckResult.Allow
    }

    fun confirmYes(hostname: String, groupId: Long) {
        ConfirmationGate.markConfirmed(groupId)
        repository.getGroup(groupId)?.let { repository.recordConfirm(it) }
    }

    data class OverlayStatus(val remainingSeconds: Int, val level: Int, val isPomodoro: Boolean = false, val levelStepsToMax: Int = 5)

    /**
     * 실행확인에서 "진행"을 고르고 통과한 뒤 이 사이트를 쓰고 있는 동안, 다음 확인까지 남은 시간(초)과
     * 지금 실행확인 레벨(재확인할수록 오르는 값 — 오버레이 불투명도를 여기에 맞춰 점점 진하게 만드는 데
     * 쓰인다). 확인이 필요한 그룹이 있으면(아직 유예시간 안이면) 반환하고, 없으면 뽀모도로 휴식으로
     * 임시 해제된 그룹이 있는지 확인해서 그 경우엔 고정된(기본보다 진한) 불투명도로 반환한다.
     */
    fun overlayStatus(hostname: String): OverlayStatus? {
        val groups = repository.findGroupsForDomain(hostname)
        val candidate = groups.firstOrNull { evaluator.isConfirmActiveNow(it) }
        if (candidate != null) {
            val remaining = effectiveRemainingCooldownSeconds(candidate)
            if (remaining <= 0) {
                OverlayLevelRatchet.reset(candidate.id)
                return null
            }
            val rawLevel = repository.getCurrentLevel(candidate)
            return OverlayStatus(
                remaining,
                OverlayLevelRatchet.displayLevel(candidate.id, rawLevel, remaining),
                levelStepsToMax = candidate.overlayLevelStepsToMax
            )
        }

        val hasPomodoroUnlock = groups.any { it.pomodoroUnlockEnabled && evaluator.isPomodoroUnlockActive(it) }
        if (!hasPomodoroUnlock) return null
        val phaseEndAt = PomodoroSyncClient.currentPhaseEndAt(repository.fbDatabaseUrl, repository.fbApiKey)
        val remainingBreakSeconds = ((phaseEndAt - System.currentTimeMillis()) / 1000L).toInt()
        if (remainingBreakSeconds <= 0) return null
        return OverlayStatus(remainingBreakSeconds, level = 0, isPomodoro = true)
    }

    /**
     * 주기적으로(1분마다) 호출된다. 처음 접속 시 확인을 놓쳤거나(탭이 이미 열려있던 경우 등)
     * 사용 중에 시간대가 바뀌어 잠겨야 하는 경우를 여기서도 다시 판단한다.
     * dailyLimitMinutes가 있으면 elapsedSeconds만큼 사용시간도 누적한다.
     */
    fun tick(hostname: String, elapsedSeconds: Int): CheckResult {
        if (isBlockedByStudyLock(hostname)) return CheckResult.Block(LockReason.STUDY_LOCK)

        val groups = repository.findGroupsForDomain(hostname)
        if (groups.isEmpty()) return CheckResult.Allow

        // 스케줄 => 일일 한도 => 실행 확인 우선순위: 누적 전 상태에서 이미 스케줄/한도로 잠금 조건이면
        // 확인창 없이 곧바로 잠근다(evaluate()가 스케줄을 한도보다 먼저 검사).
        val lockedEntry = groups.firstNotNullOfOrNull { group -> evaluator.evaluate(group).takeIf { it.locked }?.let { group to it } }
        if (lockedEntry != null) {
            val (group, result) = lockedEntry
            return CheckResult.Block(result.reason!!, repository.recordBlockAttempt(group.id))
        }

        val needsConfirm = groups.firstOrNull {
            evaluator.isConfirmActiveNow(it) && !isRecentlyConfirmedAnyDevice(it)
        }
        if (needsConfirm != null) {
            return CheckResult.Confirm(needsConfirm.id, repository.getConfirmWaitSeconds(needsConfirm), repository.getCurrentLevel(needsConfirm))
        }

        groups.forEach { group ->
            if (group.dailyLimitSeconds != null) {
                repository.addUsageSeconds(group.id, elapsedSeconds)
            }
        }

        val freshGroups = groups.mapNotNull { repository.getGroup(it.id) }
        val freshLockedEntry = freshGroups.firstNotNullOfOrNull { group -> evaluator.evaluate(group).takeIf { it.locked }?.let { group to it } }
        return if (freshLockedEntry != null) {
            val (group, result) = freshLockedEntry
            CheckResult.Block(result.reason!!, repository.recordBlockAttempt(group.id))
        } else CheckResult.Allow
    }
}
