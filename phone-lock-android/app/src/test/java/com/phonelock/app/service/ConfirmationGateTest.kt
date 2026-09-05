package com.phonelock.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ConfirmationGate 인메모리 쿨다운 최소 유닛테스트(82차, 감사 TOP20 20위/§14) — 데스크탑판
 * ConfirmationGateTest와 대칭 시나리오. [ConfirmationGate]는 프로세스 전역 싱글턴이라 다른 테스트와
 * 상태를 공유하지 않도록 각 테스트마다 다른 groupId를 쓴다.
 */
class ConfirmationGateTest {

    @Test
    fun `isRecentlyConfirmed is false before any confirmation is recorded`() {
        assertFalse(ConfirmationGate.isRecentlyConfirmed(groupId = 9001L, cooldownSeconds = 60))
    }

    @Test
    fun `isRecentlyConfirmed is true right after markConfirmed`() {
        ConfirmationGate.markConfirmed(groupId = 9002L)

        assertTrue(ConfirmationGate.isRecentlyConfirmed(groupId = 9002L, cooldownSeconds = 60))
    }

    @Test
    fun `isRecentlyConfirmed is false once cooldown has already elapsed`() {
        ConfirmationGate.markConfirmed(groupId = 9003L)

        // cooldownSeconds=0이면 "확인 직후"도 이미 유예시간이 다 지난 것으로 취급되어야 한다.
        assertFalse(ConfirmationGate.isRecentlyConfirmed(groupId = 9003L, cooldownSeconds = 0))
    }

    @Test
    fun `remainingCooldownSeconds is 0 when there is no confirmation record`() {
        assertTrue(ConfirmationGate.remainingCooldownSeconds(groupId = 9004L, cooldownSeconds = 60) == 0)
    }

    @Test
    fun `remainingCooldownSeconds is close to full cooldown right after markConfirmed`() {
        ConfirmationGate.markConfirmed(groupId = 9005L)

        val remaining = ConfirmationGate.remainingCooldownSeconds(groupId = 9005L, cooldownSeconds = 60)

        assertTrue("expected remaining in 55..60 but was $remaining", remaining in 55..60)
    }

    @Test
    fun `remainingCooldownSeconds never goes below 0 once elapsed exceeds cooldown`() {
        ConfirmationGate.markConfirmed(groupId = 9006L)

        assertTrue(ConfirmationGate.remainingCooldownSeconds(groupId = 9006L, cooldownSeconds = 0) == 0)
    }

    @Test
    fun `cooldowns for different groups do not affect each other`() {
        ConfirmationGate.markConfirmed(groupId = 9007L)

        assertTrue(ConfirmationGate.isRecentlyConfirmed(groupId = 9007L, cooldownSeconds = 60))
        assertFalse(ConfirmationGate.isRecentlyConfirmed(groupId = 9008L, cooldownSeconds = 60))
    }
}
