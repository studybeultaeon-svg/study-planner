package com.phonelock.app.ui

import com.phonelock.shared.GrowthSystem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 149차: 홈 천체 그림은 [GrowthSystem.STAGES]의 그림 id로 고른다 — 칭호만 추가되고 그림이 빠지면 조용히 기본 그림(우주 먼지)으로
 * 떨어지므로 단계 표와 그림 표가 같은 id 집합인지 묶어 두고, 칭호를 바꾸면서 레벨 경계·등급 수가 같이 흔들리지 않았는지도 고정한다
 * (경계는 109차 표 그대로여야 이미 쌓은 레벨의 단계가 바뀌지 않는다).
 */
class CosmosSceneTest {

    @Test
    fun everyStageHasItsOwnDrawing() {
        val stageIds = GrowthSystem.STAGES.map { it.illustrationId }
        assertEquals("단계마다 그림 id가 달라야 한다", stageIds.size, stageIds.toSet().size)
        assertEquals(stageIds.toSet(), COSMOS_BODY_IDS)
    }

    @Test
    fun stageThresholdsAndTiersStayTheSame() {
        assertEquals(
            listOf(1, 4, 8, 14, 22, 33, 48, 68, 95, 125, 150, 175, 200, 225, 250, 280, 310, 350, 380, 410, 450, 475, 500),
            GrowthSystem.STAGES.map { it.levelThreshold }
        )
        assertEquals(listOf(10, 4, 3, 3, 3), (0..4).map { tier -> GrowthSystem.STAGES.count { it.tier == tier } })
        assertEquals("우주 먼지", GrowthSystem.stageForLevel(1).title)
        assertEquals("오메가 포인트", GrowthSystem.stageForLevel(GrowthSystem.LEVEL_CAP).title)
        assertEquals("별의 최후", GrowthSystem.tierName(GrowthSystem.stageForLevel(310).tier))
    }
}
