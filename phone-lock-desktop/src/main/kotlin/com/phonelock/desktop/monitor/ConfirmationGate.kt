package com.phonelock.desktop.monitor

/**
 * 그룹 단위로 "예"를 선택한 뒤 짧은 시간 동안 같은 그룹 내 다른 프로그램/사이트도 다시 확인하지 않도록 하는
 * 인메모리 쿨다운. EnforcementService(프로그램)와 SiteEnforcement(사이트)가 함께 공유한다.
 */
object ConfirmationGate {
    private val confirmedAt = HashMap<Long, Long>()

    @Synchronized
    fun markConfirmed(groupId: Long) {
        confirmedAt[groupId] = System.currentTimeMillis()
    }

    /** cooldownSeconds: 그룹별로 설정하는 "재확인까지 유예시간(초)". */
    @Synchronized
    fun isRecentlyConfirmed(groupId: Long, cooldownSeconds: Int): Boolean {
        val ts = confirmedAt[groupId] ?: return false
        return System.currentTimeMillis() - ts < cooldownSeconds * 1000L
    }

    /** 다음 확인이 다시 뜨기까지 남은 시간(초). 확인 기록이 없거나 이미 유예시간이 지났으면 0. */
    @Synchronized
    fun remainingCooldownSeconds(groupId: Long, cooldownSeconds: Int): Int {
        val ts = confirmedAt[groupId] ?: return 0
        val elapsedSeconds = (System.currentTimeMillis() - ts) / 1000L
        return (cooldownSeconds.toLong() - elapsedSeconds).coerceAtLeast(0L).toInt()
    }
}
