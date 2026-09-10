package com.phonelock.shared

/**
 * 캐릭터/식물 키우기(102차+ 세션, IDEAS.md "최우선 후보" 게이미피케이션 2번째 항목) — 누적 획득 포인트
 * (보상 교환으로 줄어드는 잔액과 달리, ledger의 양수 delta만 합산한 값)를 기준으로 8단계 식물이 자란다.
 * 보상을 교환해도 캐릭터는 줄어들지 않는다(성장 자체가 그동안의 누적 노력을 보여주는 훈장 개념).
 * 순수 계산 로직만 담아 안드로이드/데스크탑 대칭 유지를 위해 :shared에 작성(StudyProgressQuotes와 동일 패턴).
 */
object CharacterGrowth {
    data class Stage(val index: Int, val emoji: String, val label: String, val threshold: Int)

    val STAGES = listOf(
        Stage(0, "🌰", "씨앗", 0),
        Stage(1, "🌱", "새싹", 30),
        Stage(2, "🌿", "떡잎", 100),
        Stage(3, "🪴", "어린 화분", 250),
        Stage(4, "🌳", "자라는 나무", 500),
        Stage(5, "🌷", "봉오리", 1000),
        Stage(6, "🌺", "개화", 2000),
        Stage(7, "🌻", "만개", 4000)
    )

    /** totalEarnedPoints 기준으로 도달한 가장 높은 단계. */
    fun stageFor(totalEarnedPoints: Int): Stage =
        STAGES.lastOrNull { totalEarnedPoints >= it.threshold } ?: STAGES.first()

    /** 다음 단계까지의 진행률(0.0~1.0). 이미 마지막 단계면 1.0. */
    fun progressToNext(totalEarnedPoints: Int): Float {
        val stage = stageFor(totalEarnedPoints)
        val next = STAGES.getOrNull(stage.index + 1) ?: return 1f
        val span = next.threshold - stage.threshold
        if (span <= 0) return 1f
        return ((totalEarnedPoints - stage.threshold).toFloat() / span).coerceIn(0f, 1f)
    }

    /** 다음 단계까지 남은 포인트. 이미 마지막 단계면 null. */
    fun pointsToNextStage(totalEarnedPoints: Int): Int? {
        val stage = stageFor(totalEarnedPoints)
        val next = STAGES.getOrNull(stage.index + 1) ?: return null
        return (next.threshold - totalEarnedPoints).coerceAtLeast(0)
    }
}
