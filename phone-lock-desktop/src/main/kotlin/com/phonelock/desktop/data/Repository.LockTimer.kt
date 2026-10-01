package com.phonelock.desktop.data

import com.phonelock.desktop.monitor.PomodoroSyncClient
import com.phonelock.shared.lock.LockTimer
import com.phonelock.shared.lock.LockTimerSignal
import com.phonelock.shared.lock.LockTimerSync

/**
 * 관리 > 타이머("이거까지만 할게요!")의 기기 간 동기화(143차, 사용자 요청) — 안드로이드 PhoneLockRepository.LockTimer.kt와
 * 대칭. 어떤 약속을 받고 풀고 올릴지는 [LockTimerSync.reconcile]이 정하고, 여기는 저장([Repository.lockTimer])과
 * Firebase 읽기·쓰기만 한다. 프로그램·사이트 목록은 기기마다 달라서 받는 기기가 자기 마지막 설정의 목록을 쓴다.
 *
 * 네트워크를 기다리는 함수들이라 UI 스레드에서 직접 부르지 말 것(화면은 `Dispatchers.IO`로 부른다).
 */

/** [syncLockTimer]가 이 기기의 약속을 바꿨는지 — 감시 루프가 알림을 띄우는 데 쓴다. */
enum class LockTimerSyncResult { NONE, ADOPTED, CLEARED }

// 감시 루프의 주기 동기화와 타이머 화면이 동시에 돌 수 있다. 저장소 락(lock)과는 별개의 락이다 — 저장소 락 안에서
// 네트워크를 기다리면 안 되므로, 네트워크는 이 락 안에서만 기다리고 저장소 접근은 각 접근자가 알아서 잠근다.
private val lockTimerSyncLock = Any()

/** 원격과 맞춘다. 물어보지 못했으면(오프라인 등) 아무것도 바꾸지 않는다. */
fun Repository.syncLockTimer(): LockTimerSyncResult = synchronized(lockTimerSyncLock) {
    if (isEffectivelyOffline()) return@synchronized LockTimerSyncResult.NONE
    // 원격을 먼저 읽고 로컬은 그 뒤에 읽는다 — 네트워크를 기다리는 사이 사용자가 약속을 풀었거나 걸었을 때
    // 낡은 로컬 값으로 판단하지 않도록.
    val remote = PomodoroSyncClient.readLockTimerSignal(fbDatabaseUrl, fbApiKey) ?: return@synchronized LockTimerSyncResult.NONE
    val now = System.currentTimeMillis()
    val local = lockTimer
    when (val action = LockTimerSync.reconcile(local, remote.signal, lockTimerClosedStartedAt, now)) {
        LockTimerSync.Action.None -> LockTimerSyncResult.NONE
        is LockTimerSync.Action.Adopt -> {
            lockTimer = action.signal.toLockTimer(lockTimerPreset)
            LockTimerSyncResult.ADOPTED
        }
        LockTimerSync.Action.ClearLocal -> {
            lockTimer = null
            LockTimerSyncResult.CLEARED
        }
        LockTimerSync.Action.PushLocal -> {
            local?.let { PomodoroSyncClient.writeLockTimerSignal(fbDatabaseUrl, fbApiKey, LockTimerSignal.of(it, cancelled = false, nowMillis = now)) }
            LockTimerSyncResult.NONE
        }
        LockTimerSync.Action.PushCancel -> {
            remote.signal?.let { PomodoroSyncClient.writeLockTimerSignal(fbDatabaseUrl, fbApiKey, it.asCancelled(now)) }
            LockTimerSyncResult.NONE
        }
    }
}

/**
 * 새 약속을 건다. 먼저 원격과 맞춰 보고, 다른 기기에서 이미 진행 중인 약속이 있으면(그걸 이 기기로 받아왔다)
 * 새로 걸지 않고 false를 돌려준다 — 한 번에 하나의 약속만 있다.
 */
fun Repository.startLockTimer(timer: LockTimer): Boolean {
    syncLockTimer()
    if (lockTimer != null) return false
    lockTimer = timer
    if (!isEffectivelyOffline()) {
        PomodoroSyncClient.writeLockTimerSignal(fbDatabaseUrl, fbApiKey, LockTimerSignal.of(timer, cancelled = false, nowMillis = System.currentTimeMillis()))
    }
    return true
}

/** 해제 절차를 마친 약속을 푼다 — 다른 기기에서도 풀리도록 올린다. 못 올려도 [syncLockTimer]가 다시 올린다. */
fun Repository.releaseLockTimer() {
    val timer = lockTimer ?: return
    lockTimer = null
    lockTimerClosedStartedAt = timer.startedAtMillis
    if (!isEffectivelyOffline()) {
        PomodoroSyncClient.writeLockTimerSignal(fbDatabaseUrl, fbApiKey, LockTimerSignal.of(timer, cancelled = true, nowMillis = System.currentTimeMillis()))
    }
}

/**
 * 지금 이 기기에서 실제로 잠금을 거는 약속 — 잠금 단계이고, "뽀모도로 휴식 중엔 풀기"를 켠 약속이 휴식 중이 아닐 때만
 * 돌려준다. 휴식 여부는 공부 타이머 신호(5초 캐시, 네트워크 대기 가능)라 화면 그리기 경로가 아니라 감시·판정 쪽에서만
 * 부를 것. 휴식 여부를 못 알아내면 잠근 채로 둔다(fail-safe, [PomodoroSyncClient.isBreakActive]).
 */
fun Repository.enforcedLockTimer(timer: LockTimer?, nowMillis: Long = System.currentTimeMillis()): LockTimer? {
    if (timer == null || !timer.isLockedAt(nowMillis)) return null
    if (timer.pomodoroBreakUnlock && PomodoroSyncClient.isBreakActive(fbDatabaseUrl, fbApiKey)) return null
    return timer
}

/** 이 기기에서 해제 절차로 푼 마지막 약속의 시작 시각 — [LockTimerSync.reconcile]의 closedStartedAtMillis. */
var Repository.lockTimerClosedStartedAt: Long
    get() = synchronized(lock) { data.lockTimerClosedStartedAt }
    set(value) = synchronized(lock) {
        data.lockTimerClosedStartedAt = value
        persist()
    }
