package com.phonelock.desktop.monitor

import com.phonelock.desktop.data.Group
import com.phonelock.desktop.data.Repository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * LockEvaluator 핵심 판정 로직 최소 유닛테스트(82차, 감사 TOP20 20위/§14) — 회귀 방지가 목적이라
 * 실제 사용 시나리오 위주로만 다룬다(전수 커버리지 아님). Repository는 디스크 I/O가 있는 실 구현이라
 * MockK로 대체하고, 실제 Repository()는 테스트에서 생성하지 않는다.
 */
class LockEvaluatorTest {
    private lateinit var repository: Repository
    private lateinit var evaluator: LockEvaluator

    @BeforeEach
    fun setUp() {
        repository = mockk()
        every { repository.dailyResetHour } returns 0
        every { repository.getTodayUsageSeconds(any()) } returns 0
        every { repository.syncedSnoozeUntil(any()) } returns 0L
        evaluator = LockEvaluator(repository)
    }

    private fun baseGroup(id: Long = 1L) = Group(id = id, name = "test")

    @Test
    fun `evaluate returns schedule-locked when now is inside schedule window`() {
        val now = LocalDateTime.of(2026, 1, 7, 10, 30)
        val group = baseGroup().copy(scheduleStartMinute = 10 * 60, scheduleEndMinute = 12 * 60)

        val result = evaluator.evaluate(group, now)

        assertTrue(result.locked)
        assertEquals(LockReason.SCHEDULE, result.reason)
    }

    @Test
    fun `evaluate returns unlocked when outside schedule window`() {
        val now = LocalDateTime.of(2026, 1, 7, 13, 0)
        val group = baseGroup().copy(scheduleStartMinute = 10 * 60, scheduleEndMinute = 12 * 60)

        val result = evaluator.evaluate(group, now)

        assertFalse(result.locked)
    }

    @Test
    fun `evaluate returns unlocked when group is disabled even inside schedule window`() {
        val now = LocalDateTime.of(2026, 1, 7, 10, 30)
        val group = baseGroup().copy(
            scheduleStartMinute = 10 * 60, scheduleEndMinute = 12 * 60, groupEnabled = false
        )

        val result = evaluator.evaluate(group, now)

        assertFalse(result.locked)
    }

    @Test
    fun `evaluate returns limit-locked when usage meets daily limit`() {
        every { repository.getTodayUsageSeconds(1L) } returns 3600
        val now = LocalDateTime.of(2026, 1, 7, 13, 0)
        val group = baseGroup().copy(dailyLimitSeconds = 3600)

        val result = evaluator.evaluate(group, now)

        assertTrue(result.locked)
        assertEquals(LockReason.LIMIT, result.reason)
    }

    @Test
    fun `isGroupActive is true via forceEnabled window even when groupEnabled is false`() {
        val now = LocalDateTime.of(2026, 1, 7, 13, 0)
        val group = baseGroup().copy(
            groupEnabled = false,
            forceEnabledFrom = "2026-01-01",
            forceEnabledUntil = "2026-01-31"
        )

        assertTrue(evaluator.isGroupActive(group, now))
    }

    @Test
    fun `isWithinEditExemptionWindow is true late night and early morning, false midday`() {
        assertTrue(evaluator.isWithinEditExemptionWindow(LocalDateTime.of(2026, 1, 7, 23, 30)))
        assertTrue(evaluator.isWithinEditExemptionWindow(LocalDateTime.of(2026, 1, 7, 5, 0)))
        assertFalse(evaluator.isWithinEditExemptionWindow(LocalDateTime.of(2026, 1, 7, 14, 0)))
    }

    @Test
    fun `detectWeakeningEdit is true when reducing waitIncrementSeconds while active`() {
        val now = LocalDateTime.of(2026, 1, 7, 14, 0) // 편집 면제 시간대 밖
        val original = baseGroup().copy(confirmEnabled = true, waitIncrementSeconds = 10)
        val updated = original.copy(waitIncrementSeconds = 5)

        assertTrue(
            evaluator.detectWeakeningEdit(original, updated, emptySet(), emptySet(), emptySet(), emptySet(), now)
        )
    }

    @Test
    fun `detectWeakeningEdit is true when turning off confirmEnabled`() {
        val now = LocalDateTime.of(2026, 1, 7, 14, 0)
        val original = baseGroup().copy(confirmEnabled = true)
        val updated = original.copy(confirmEnabled = false)

        assertTrue(
            evaluator.detectWeakeningEdit(original, updated, emptySet(), emptySet(), emptySet(), emptySet(), now)
        )
    }

    @Test
    fun `detectWeakeningEdit is false during edit exemption window even for a weakening change`() {
        val now = LocalDateTime.of(2026, 1, 7, 23, 30) // 편집 면제 시간대(23시~다음날 11시)
        val original = baseGroup().copy(confirmEnabled = true)
        val updated = original.copy(confirmEnabled = false)

        assertFalse(
            evaluator.detectWeakeningEdit(original, updated, emptySet(), emptySet(), emptySet(), emptySet(), now)
        )
    }

    @Test
    fun `detectWeakeningEdit is false when the group itself is disabled`() {
        val now = LocalDateTime.of(2026, 1, 7, 14, 0)
        val original = baseGroup().copy(confirmEnabled = true, groupEnabled = false)
        val updated = original.copy(confirmEnabled = false)

        assertFalse(
            evaluator.detectWeakeningEdit(original, updated, emptySet(), emptySet(), emptySet(), emptySet(), now)
        )
    }
}
