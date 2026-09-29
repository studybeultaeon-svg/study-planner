package com.phonelock.app.data

import com.phonelock.shared.GrowthBoost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 상점 성장 물약(138차) 판정 테스트 — 로직은 :shared의 순수 코드라 안드로이드 유닛테스트에서 그대로 검증한다
 * (RoutineRepeatTest와 같은 방식). 공부가 효과 시간과 겹친 만큼만 배율을 받는지, 한 번에 하나·연장 상한이 지켜지는지 고정한다.
 */
class GrowthBoostTest {

    private val minute = 60_000L
    private val t0 = 1_800_000_000_000L
    private val small = GrowthBoost.potionById("boost_small")!!   // 1시간 ×1.5
    private val strong = GrowthBoost.potionById("boost_strong")!! // 1시간 ×2

    private fun boughtAt(potion: GrowthBoost.Potion, at: Long) = GrowthBoost.applyPurchase(emptyList(), potion, at)!!

    @Test
    fun `study fully inside the effect gets the whole multiplier`() {
        val windows = boughtAt(strong, t0)
        assertEquals(2.0, GrowthBoost.averageMultiplier(windows, t0 + 10 * minute, t0 + 40 * minute), 1e-9)
    }

    @Test
    fun `study that runs past the effect is boosted only for the overlapping part`() {
        // 효과 30분 남은 시점부터 60분 공부 → 절반만 ×2 → 평균 ×1.5.
        val windows = boughtAt(strong, t0)
        assertEquals(1.5, GrowthBoost.averageMultiplier(windows, t0 + 30 * minute, t0 + 90 * minute), 1e-9)
    }

    @Test
    fun `study that started before buying is boosted only from the purchase on`() {
        val windows = boughtAt(strong, t0)
        assertEquals(1.5, GrowthBoost.averageMultiplier(windows, t0 - 30 * minute, t0 + 30 * minute), 1e-9)
    }

    @Test
    fun `no overlap means no boost`() {
        val windows = boughtAt(strong, t0)
        assertEquals(1.0, GrowthBoost.averageMultiplier(windows, t0 + 60 * minute, t0 + 120 * minute), 1e-9)
        assertEquals(1.0, GrowthBoost.multiplierAt(windows, t0 - 1), 1e-9)
    }

    @Test
    fun `zero length study uses the multiplier at that instant - start inclusive, end exclusive`() {
        val windows = boughtAt(small, t0)
        assertEquals(1.5, GrowthBoost.averageMultiplier(windows, t0, t0), 1e-9)
        assertEquals(1.5, GrowthBoost.multiplierAt(windows, t0 + 60 * minute - 1), 1e-9)
        assertEquals(1.0, GrowthBoost.multiplierAt(windows, t0 + 60 * minute), 1e-9)
    }

    @Test
    fun `buying the same potion again extends the running effect`() {
        val first = boughtAt(small, t0)
        val extended = GrowthBoost.applyPurchase(first, small, t0 + 20 * minute)!!
        assertEquals(1, extended.size)
        assertEquals(t0 + 120 * minute, extended.single().endMillis)
        assertEquals(GrowthBoost.PurchaseCheck.EXTEND, GrowthBoost.checkPurchase(first, small, t0 + 20 * minute))
    }

    @Test
    fun `a different potion cannot be bought while one is running, but can after it ends`() {
        val first = boughtAt(small, t0)
        assertEquals(GrowthBoost.PurchaseCheck.OTHER_ACTIVE, GrowthBoost.checkPurchase(first, strong, t0 + 5 * minute))
        assertNull(GrowthBoost.applyPurchase(first, strong, t0 + 5 * minute))
        val after = GrowthBoost.applyPurchase(first, strong, t0 + 61 * minute)!!
        assertEquals(2, after.size)
        assertEquals(2.0, GrowthBoost.multiplierAt(after, t0 + 70 * minute), 1e-9)
        // 지난 구간도 남아 있어 효과가 끝난 뒤에 정지한 공부의 겹친 시간을 계산할 수 있다.
        assertEquals(1.5, GrowthBoost.multiplierAt(after, t0 + 30 * minute), 1e-9)
    }

    @Test
    fun `stacking is capped at the maximum remaining time`() {
        var windows = boughtAt(small, t0)
        repeat(5) { windows = GrowthBoost.applyPurchase(windows, small, t0)!! } // 6시간째까지는 가능
        assertEquals(t0 + GrowthBoost.MAX_REMAINING_MINUTES * minute, windows.single().endMillis)
        assertEquals(GrowthBoost.PurchaseCheck.TOO_LONG, GrowthBoost.checkPurchase(windows, small, t0))
        assertNull(GrowthBoost.applyPurchase(windows, small, t0))
    }

    @Test
    fun `old windows are pruned on purchase but recent history stays`() {
        val day = 24 * 60 * minute
        val old = GrowthBoost.Window("boost_small", t0, t0 + 60 * minute, 1.5)
        val recent = GrowthBoost.Window("boost_strong", t0 + 2 * day, t0 + 2 * day + 60 * minute, 2.0)
        val now = t0 + 3 * day
        val next = GrowthBoost.applyPurchase(listOf(old, recent), small, now)!!
        assertTrue(old !in next)
        assertTrue(recent in next)
        assertEquals(2, next.size)
    }

    @Test
    fun `labels`() {
        assertEquals("×1.5", GrowthBoost.multiplierLabel(1.5))
        assertEquals("×2", GrowthBoost.multiplierLabel(2.0))
        assertEquals("1시간 30분", GrowthBoost.durationLabel(90))
        assertEquals("3시간", GrowthBoost.durationLabel(180))
        assertEquals("1분", GrowthBoost.remainingLabel(30_000L))
        assertEquals("1시간 1분", GrowthBoost.remainingLabel(60 * minute + 1))
    }
}
