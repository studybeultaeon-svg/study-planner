package com.phonelock.desktop.monitor

/**
 * 오버레이에 "보여주는" 실행확인 레벨을 한 쿨다운 사이클 안에서는 위로 튀지 못하게 붙잡아 두는 캡.
 *
 * 확인/escalation 로직(ConfirmationGate, Repository의 레벨 계산)은 전혀 건드리지 않는다 — 재확인
 * 화면이 뜨는 시점/빈도, 레벨이 오르내리는 규칙은 원래 그대로다. 다만 모바일과 Firebase로
 * 레벨을 공유하다 보니, 같은 쿨다운 사이클이 도는 도중(사용자가 새로 재확인한 게 아닌데도) 다른 기기의
 * 재확인으로 레벨이 갑자기 튀어 오르면 오버레이 불투명도가 뜬금없이 확 진해지는 문제가 있었다
 * (2026-07-30). remaining(다음 확인까지 남은 시간)이 직전보다 커진 순간 = 진짜로 새로 재확인해서
 * 쿨다운이 꽉 차게 리셋된 것이므로 그때만 새 레벨을 캡 없이 반영하고, 그 사이(계속 줄어드는 동안)엔
 * 직전에 보여준 값 이하로만 표시한다(내려가는 건 자유롭게 허용).
 */
object OverlayLevelRatchet {
    private data class State(val lastRemainingSeconds: Int, val lastDisplayedLevel: Int)

    private val state = HashMap<Long, State>()

    @Synchronized
    fun displayLevel(groupId: Long, rawLevel: Int, remainingSeconds: Int): Int {
        val prev = state[groupId]
        val isNewCycle = prev == null || remainingSeconds > prev.lastRemainingSeconds
        val displayed = if (isNewCycle) rawLevel else minOf(rawLevel, prev!!.lastDisplayedLevel)
        state[groupId] = State(remainingSeconds, displayed)
        return displayed
    }

    /** 오버레이가 내려간 뒤(쿨다운이 다 지나거나 그룹을 벗어남) 다음에 뜨는 건 새 사이클이므로 캡을 지운다. */
    @Synchronized
    fun reset(groupId: Long) {
        state.remove(groupId)
    }
}
