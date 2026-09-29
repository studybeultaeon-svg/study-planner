package com.phonelock.app.data

import com.phonelock.shared.GrowthBoost
import com.phonelock.shared.GrowthSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 성장 밸런스 회귀 테스트(138차) — 설계 의도("하루 2~3시간 공부를 성실히 하면 약 9개월에 500레벨", "환생하면 이전 벽까지는
 * 훨씬 쉽게")를 숫자로 고정한다. 적립 규칙·물약·환생 배율·레벨 곡선 중 하나만 바뀌어도 여기서 드러난다.
 * 시뮬레이션 근거는 [[DECISIONS.md]] 138차.
 */
class GrowthBalanceTest {

    /** 성실한 하루(2.5시간 공부 + 루틴·일정 전부 + 스트릭 + 그날 포인트를 전부 물약에)의 raw EXP. */
    private fun diligentDailyRaw(studyMinutes: Int, usePotions: Boolean): Double {
        val routine = GrowthSystem.dayRatioReward(GrowthSystem.ROUTINE_DAY_POOL, 5, 5)
        val streak = 10
        val calendar = GrowthSystem.dayRatioReward(GrowthSystem.CALENDAR_DAY_POOL, 3, 3)
        val points = studyMinutes / 10 + routine + streak + calendar
        // 세 물약 모두 포인트 1당 공부 EXP +0.75(2.5시간 공부 기준) — 가장 싼 물약으로 계산해도 같다.
        val small = GrowthBoost.potionById("boost_small")!!
        val extraPerPoint = small.durationMinutes * (small.multiplier - 1.0) / small.cost
        val potionExtra = if (usePotions) minOf(points * extraPerPoint, studyMinutes.toDouble()) else 0.0
        return studyMinutes + routine + streak + calendar + potionExtra
    }

    /** 10사이클(Lv.50, 100, …, 450에서 바로 환생, 마지막은 Lv.500)까지 걸리는 날수. */
    private fun seasonDays(dailyRaw: Double): Double =
        (0 until 10).sumOf { n ->
            GrowthSystem.cumulativeExpForLevel(GrowthSystem.rebirthRequiredLevel(n + 1)) / (dailyRaw * GrowthSystem.expMultiplier(n))
        }

    @Test
    fun `diligent study with potions reaches level 500 in about nine months`() {
        val days = seasonDays(diligentDailyRaw(150, usePotions = true))
        assertTrue("9개월(270일) 근처여야 한다: $days", days in 255.0..290.0)
    }

    @Test
    fun `without potions the cap is still reachable within the yearly season`() {
        val days = seasonDays(diligentDailyRaw(150, usePotions = false))
        assertTrue("물약 없이도 1년 시즌 안이어야 한다: $days", days < 365.0)
        assertTrue("물약을 안 쓰면 더 걸려야 한다: $days", days > seasonDays(diligentDailyRaw(150, usePotions = true)))
    }

    @Test
    fun `after a rebirth the previous wall comes back much faster than the grind that preceded it`() {
        val daily = diligentDailyRaw(150, usePotions = true)
        for (n in 1 until 10) {
            val wall = GrowthSystem.rebirthRequiredLevel(n)
            val previousCycle = GrowthSystem.cumulativeExpForLevel(wall) / (daily * GrowthSystem.expMultiplier(n - 1))
            val lastTenLevels = (GrowthSystem.cumulativeExpForLevel(wall) - GrowthSystem.cumulativeExpForLevel(wall - 10)) /
                (daily * GrowthSystem.expMultiplier(n - 1))
            val backToWall = GrowthSystem.cumulativeExpForLevel(wall) / (daily * GrowthSystem.expMultiplier(n))
            // 이전 벽까지 돌아가는 데 걸리는 시간 전체가, 직전 사이클의 "마지막 10레벨"보다도 짧다.
            assertTrue("환생 $n: $backToWall vs $lastTenLevels", backToWall < lastTenLevels)
            assertTrue("환생 $n: 직전 사이클의 2할 미만", backToWall < previousCycle * 0.2)
        }
    }

    @Test
    fun `routine and calendar rewards do not grow with the number of items`() {
        assertEquals(GrowthSystem.ROUTINE_DAY_POOL, GrowthSystem.dayRatioReward(GrowthSystem.ROUTINE_DAY_POOL, 3, 3))
        assertEquals(GrowthSystem.ROUTINE_DAY_POOL, GrowthSystem.dayRatioReward(GrowthSystem.ROUTINE_DAY_POOL, 20, 20))
        assertEquals(10, GrowthSystem.dayRatioReward(20, 3, 6))
        assertEquals(7, GrowthSystem.dayRatioReward(20, 1, 3)) // 6.67 → 7
        assertEquals(0, GrowthSystem.dayRatioReward(20, 0, 0))
        assertEquals(20, GrowthSystem.dayRatioReward(20, 5, 3)) // 예정보다 많이 끝내도(예정 밖 루틴) 하루 몫을 넘지 않는다
    }

    @Test
    fun `large exp amounts are shortened for display`() {
        assertEquals("12.5", GrowthSystem.formatExp(12.5))
        assertEquals("3.7억", GrowthSystem.formatExp(370_000_000.0))
        assertEquals("1.2만", GrowthSystem.formatExp(12_000.0))
    }
}
