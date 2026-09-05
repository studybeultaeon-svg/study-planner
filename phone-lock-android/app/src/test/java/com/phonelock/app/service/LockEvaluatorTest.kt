package com.phonelock.app.service

import com.phonelock.app.data.AppGroup
import com.phonelock.app.data.PhoneLockRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/**
 * LockEvaluator 핵심 판정 로직 최소 유닛테스트(82차, 감사 TOP20 20위/§14) — 데스크탑판
 * LockEvaluatorTest와 대칭 시나리오. 회귀 방지가 목적이라 실제 사용 시나리오 위주로만 다룬다
 * (전수 커버리지 아님). PhoneLockRepository는 Context/Room이 필요한 실 구현이라 MockK로 대체한다.
 */
class LockEvaluatorTest {
    private lateinit var repository: PhoneLockRepository
    private lateinit var evaluator: LockEvaluator

    @Before
    fun setUp() {
        repository = mockk()
        every { repository.dailyResetHour } returns 0
        coEvery { repository.getTodayUsageSeconds(any()) } returns 0
        coEvery { repository.syncedSnoozeUntil(any()) } returns 0L
        evaluator = LockEvaluator(repository)
    }

    private fun baseGroup(id: Long = 1L) = AppGroup(id = id, name = "test")

    @Test
    fun `evaluate returns schedule-locked when now is inside schedule window`() = runTest {
        val now = LocalDateTime.of(2026, 1, 7, 10, 30)
        val group = baseGroup().copy(scheduleStartMinute = 10 * 60, scheduleEndMinute = 12 * 60)

        val result = evaluator.evaluate(group, now)

        assertTrue(result.locked)
        assertEquals(LockReason.SCHEDULE, result.reason)
    }

    @Test
    fun `evaluate returns unlocked when outside schedule window`() = runTest {
        val now = LocalDateTime.of(2026, 1, 7, 13, 0)
        val group = baseGroup().copy(scheduleStartMinute = 10 * 60, scheduleEndMinute = 12 * 60)

        val result = evaluator.evaluate(group, now)

        assertFalse(result.locked)
    }

    @Test
    fun `evaluate returns unlocked when group is disabled even inside schedule window`() = runTest {
        val now = LocalDateTime.of(2026, 1, 7, 10, 30)
        val group = baseGroup().copy(
            scheduleStartMinute = 10 * 60, scheduleEndMinute = 12 * 60, groupEnabled = false
        )

        val result = evaluator.evaluate(group, now)

        assertFalse(result.locked)
    }

    @Test
    fun `evaluate returns limit-locked when usage meets daily limit`() = runTest {
        coEvery { repository.getTodayUsageSeconds(1L) } returns 3600
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
    fun `detectWeakeningEdit is true when reducing waitIncrementSeconds while active`() = runTest {
        val now = LocalDateTime.of(2026, 1, 7, 14, 0) // 편집 면제 시간대 밖
        val original = baseGroup().copy(confirmEnabled = true, waitIncrementSeconds = 10)
        val updated = original.copy(waitIncrementSeconds = 5)

        assertTrue(
            evaluator.detectWeakeningEdit(original, updated, emptySet(), emptySet(), emptySet(), emptySet(), now)
        )
    }

    @Test
    fun `detectWeakeningEdit is true when turning off confirmEnabled`() = runTest {
        val now = LocalDateTime.of(2026, 1, 7, 14, 0)
        val original = baseGroup().copy(confirmEnabled = true)
        val updated = original.copy(confirmEnabled = false)

        assertTrue(
            evaluator.detectWeakeningEdit(original, updated, emptySet(), emptySet(), emptySet(), emptySet(), now)
        )
    }

    @Test
    fun `detectWeakeningEdit is false during edit exemption window even for a weakening change`() = runTest {
        val now = LocalDateTime.of(2026, 1, 7, 23, 30) // 편집 면제 시간대(23시~다음날 11시)
        val original = baseGroup().copy(confirmEnabled = true)
        val updated = original.copy(confirmEnabled = false)

        assertFalse(
            evaluator.detectWeakeningEdit(original, updated, emptySet(), emptySet(), emptySet(), emptySet(), now)
        )
    }

    @Test
    fun `detectWeakeningEdit is false when the group itself is disabled`() = runTest {
        val now = LocalDateTime.of(2026, 1, 7, 14, 0)
        val original = baseGroup().copy(confirmEnabled = true, groupEnabled = false)
        val updated = original.copy(confirmEnabled = false)

        assertFalse(
            evaluator.detectWeakeningEdit(original, updated, emptySet(), emptySet(), emptySet(), emptySet(), now)
        )
    }
}
