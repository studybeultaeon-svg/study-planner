package com.phonelock.desktop.ui

import com.phonelock.shared.GrowthSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 149차: 홈 천체 그림은 [GrowthSystem.STAGES]의 그림 id로 고른다 — 칭호만 추가되고 그림이 빠지면 조용히 기본 그림(우주 먼지)으로
 * 떨어지므로, 단계 표와 그림 표가 정확히 같은 id 집합인지 묶어 둔다(안드로이드판 같은 테스트와 대칭).
 */
class CosmosSceneTest {

    @Test
    fun everyStageHasItsOwnDrawing() {
        val stageIds = GrowthSystem.STAGES.map { it.illustrationId }
        assertEquals(stageIds.size, stageIds.toSet().size, "단계마다 그림 id가 달라야 한다")
        assertEquals(stageIds.toSet(), COSMOS_BODY_IDS)
    }
}
