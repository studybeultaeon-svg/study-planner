package com.phonelock.shared.lock

/**
 * 타이머("이거까지만 할게요!")를 기기 사이에 맞추는 규칙(143차, 사용자 요청) — 순수 판정만 담고, Firebase 읽기·쓰기와
 * 로컬 저장은 양 플랫폼이 한다. 약속 하나는 [LockTimerSignal.startedAtMillis]로 구별한다.
 *
 * 한 사용자에게 약속은 한 번에 하나뿐이라 문서도 하나(`users/{uid}/lockTimerSync`)다. 기기마다 목록이 다른 앱·사이트는
 * 실리지 않고, 받는 기기가 자기 마지막 설정의 목록을 붙인다([LockTimerSignal.toLockTimer]).
 *
 * **지켜야 할 성질**
 * - 해제 절차를 통과해 푼 약속은 다른 기기에서도 풀린다(어느 기기에서 풀었든 절차는 한 번이다).
 * - 이미 진행 중인 로컬 약속을 다른 약속으로 **갈아끼우지 않는다** — 짧고 쉬운 약속을 다른 기기에서 새로 걸어 어려운
 *   약속을 덮어쓰는 길을 막는다. 두 기기에 서로 다른 약속이 있으면 각자 자기 것을 지킨다.
 * - 이 기기에서 풀었는데 그 사실을 올리지 못했다면([closedStartedAtMillis]) 원격에 남은 같은 약속을 다시 받아오지 않고
 *   해제를 다시 올린다.
 */
data class LockTimerSignal(
    val startedAtMillis: Long,
    val lockStartAtMillis: Long,
    val lockEndAtMillis: Long,
    val wholeDevice: Boolean,
    val level: Int,
    val pomodoroBreakUnlock: Boolean,
    /** 어느 기기에서든 해제 절차를 거쳐 약속을 풀었다. */
    val cancelled: Boolean,
    val updatedAtMillis: Long
) {
    fun isOver(nowMillis: Long): Boolean = lockEndAtMillis <= nowMillis

    fun asCancelled(nowMillis: Long): LockTimerSignal = copy(cancelled = true, updatedAtMillis = nowMillis)

    /** 받는 기기가 자기 마지막 설정([preset])의 목록을 붙여 이 기기의 약속으로 만든다. */
    fun toLockTimer(preset: LockTimerPreset): LockTimer = LockTimer(
        startedAtMillis = startedAtMillis,
        lockStartAtMillis = lockStartAtMillis,
        lockEndAtMillis = lockEndAtMillis,
        wholeDevice = wholeDevice,
        apps = if (wholeDevice) preset.allowedApps else preset.targetApps,
        sites = if (wholeDevice) preset.allowedSites else preset.targetSites,
        level = UnlockLevel.clamp(level),
        pomodoroBreakUnlock = pomodoroBreakUnlock
    )

    companion object {
        fun of(timer: LockTimer, cancelled: Boolean, nowMillis: Long): LockTimerSignal = LockTimerSignal(
            startedAtMillis = timer.startedAtMillis,
            lockStartAtMillis = timer.lockStartAtMillis,
            lockEndAtMillis = timer.lockEndAtMillis,
            wholeDevice = timer.wholeDevice,
            level = timer.level,
            pomodoroBreakUnlock = timer.pomodoroBreakUnlock,
            cancelled = cancelled,
            updatedAtMillis = nowMillis
        )
    }
}

object LockTimerSync {

    /** 호출부가 해야 할 일. */
    sealed class Action {
        /** 할 일 없음. */
        object None : Action()

        /** 원격 약속을 이 기기에도 건다. */
        data class Adopt(val signal: LockTimerSignal) : Action()

        /** 다른 기기에서 푼 약속이므로 이 기기에서도 지운다. */
        object ClearLocal : Action()

        /** 이 기기의 약속을 원격에 올린다. */
        object PushLocal : Action()

        /** 이 기기에서 이미 푼 약속인데 원격엔 아직 진행 중으로 남아 있다 — 해제를 올린다. */
        object PushCancel : Action()
    }

    /**
     * @param local 이 기기의 약속(없으면 null — 끝난 약속은 없는 것으로 본다)
     * @param remote 원격 문서(없으면 null)
     * @param closedStartedAtMillis 이 기기에서 해제 절차로 풀었던 마지막 약속의 시작 시각(없으면 0)
     */
    fun reconcile(local: LockTimer?, remote: LockTimerSignal?, closedStartedAtMillis: Long, nowMillis: Long): Action {
        val live = local?.takeIf { it.phaseAt(nowMillis) != LockTimer.Phase.DONE }

        if (remote == null) return if (live != null) Action.PushLocal else Action.None

        // 원격이 이미 끝났거나 풀린 약속이면 문서 자리가 비어 있는 셈이다.
        if (remote.cancelled || remote.isOver(nowMillis)) {
            if (live == null) return Action.None
            if (live.startedAtMillis == remote.startedAtMillis) {
                return if (remote.cancelled) Action.ClearLocal else Action.None
            }
            return Action.PushLocal
        }

        // 원격은 진행 중인 약속이다.
        if (live == null) {
            return if (remote.startedAtMillis == closedStartedAtMillis) Action.PushCancel else Action.Adopt(remote)
        }
        // 같은 약속이면 맞춰 둔 것이고, 다른 약속이면 각자 자기 것을 지킨다(갈아끼우지 않는다).
        return Action.None
    }
}
