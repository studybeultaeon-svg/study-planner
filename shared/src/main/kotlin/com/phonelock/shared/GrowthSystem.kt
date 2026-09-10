package com.phonelock.shared

/**
 * "식물 성장" 시스템(105차 후속, 사용자와 긴 설계 논의 끝에 확정) — 기존 `StudyLevel`(공부 분 기준 레벨)과
 * `CharacterGrowth`(전체 포인트 기준 8단계 캐릭터)를 완전히 대체한다. 두 파일은 서로 다른 축이라
 * "레벨업=식물 성장"이 성립하지 않는 문제가 있었음([[DECISIONS.md]] 105차 참고) — 이제 하나의 경험치
 * 축(growthExp)이 레벨과 식물 칭호/일러스트를 동시에 결정한다.
 *
 * 핵심 설계(사용자 확정):
 * - 레벨은 숫자 그대로 유지하고 자주 오른다(요구 EXP가 완만하게 증가, 네자리수까지 현실적으로 도달 가능).
 * - "칭호"(식물 이름)는 레벨마다 바뀌는 게 아니라 정해진 레벨 구간(STAGES)에 도달해야 바뀐다 —
 *   레벨 숫자는 자주, 칭호는 듬성듬성 오르게 해서 두 종류의 도파민을 분리했다.
 * - 칭호는 런타임에 랜덤 조합하지 않고 전부 미리 정해둔 고정 테이블(STAGES)이다 — 칭호마다 전용
 *   일러스트(`illustrationId`, PlantScreen.kt의 GroundScene이 분기해서 그림)가 1:1로 짝지어져 있어
 *   칭호와 그림이 항상 일치한다(불일치 불가능).
 * - 환생(Rebirth): 레벨/경험치를 초기화하는 대신 영구 EXP 배율을 얻는다. 요구 레벨은 완만하게
 *   증가(+5/회)하고 배율도 선형(+1.5/회)으로만 늘어 폭주하지 않는다 — 실제 시뮬레이션으로 검증(첫
 *   9~10회 환생까지는 매 회차가 이전보다 빨라짐, [[DECISIONS.md]] 105차 참고).
 */
object GrowthSystem {

    // ---- 레벨/EXP ----

    /** 레벨 L→L+1로 가는 데 필요한 EXP. 완만한 다항식(지수 없음) — 초반은 거의 선형, 후반은 서서히 증가.
     *  숫자가 아무리 커져도(수만 레벨) 안전하게 계산되도록 지수 기반 공식은 쓰지 않았다. */
    fun expRequiredForLevel(level: Int): Double {
        val l = level.coerceAtLeast(1).toDouble()
        return 2.0 + 0.3 * Math.pow(l, 1.1)
    }

    /** 레벨 level에 도달하기 위한 누적 EXP(레벨 1은 0). 레벨이 매우 커져도(수천 단위) 단순 루프라 충분히 빠르다. */
    fun cumulativeExpForLevel(level: Int): Double {
        if (level <= 1) return 0.0
        var sum = 0.0
        for (l in 1 until level) sum += expRequiredForLevel(l)
        return sum
    }

    /** 누적 EXP 기준 현재 레벨(1부터, 끝없이 증가). 이분 탐색으로 레벨이 커져도 빠르게 찾는다. */
    fun levelForExp(totalExp: Double): Int {
        if (totalExp <= 0.0) return 1
        var hi = 2
        while (cumulativeExpForLevel(hi) <= totalExp) hi *= 2
        var lo = 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cumulativeExpForLevel(mid) <= totalExp) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** 현재 레벨 구간 안에서의 진행률(0.0~1.0). */
    fun progressToNextLevel(totalExp: Double): Float {
        val level = levelForExp(totalExp)
        val base = cumulativeExpForLevel(level)
        val next = cumulativeExpForLevel(level + 1)
        val span = next - base
        if (span <= 0.0) return 0f
        return ((totalExp - base) / span).toFloat().coerceIn(0f, 1f)
    }

    /** 다음 레벨까지 남은 EXP. */
    fun expToNextLevel(totalExp: Double): Double {
        val level = levelForExp(totalExp)
        return (cumulativeExpForLevel(level + 1) - totalExp).coerceAtLeast(0.0)
    }

    // ---- 환생(Rebirth) ----

    /** 환생 n회차(1부터)를 하기 위해 필요한 레벨 — 완만한 선형 증가. */
    fun rebirthRequiredLevel(rebirthIndex: Int): Int = 40 + 5 * rebirthIndex

    /** 환생 n회 완료 후 적용되는 EXP 획득 배율 — 선형 증가(폭주 방지). */
    fun expMultiplier(rebirthCount: Int): Double = 1.0 + 1.5 * rebirthCount

    /** 현재 레벨로 환생 가능한지(완료한 환생 횟수 기준 다음 환생 요구 레벨과 비교). */
    fun canRebirth(currentLevel: Int, rebirthCount: Int): Boolean =
        currentLevel >= rebirthRequiredLevel(rebirthCount + 1)

    // ---- 성장 단계(칭호+일러스트, 전부 미리 정해둔 고정 테이블) ----

    data class Stage(val levelThreshold: Int, val title: String, val illustrationId: String)

    /**
     * 칭호 고정 테이블 — 런타임에 랜덤 생성하지 않는다. 레벨 1~95는 "정상 성장"(평범한 식물 성장 과정),
     * 레벨 130부터 "병맛/뇌절 성장"(이탈리안 브레인롯+한국 밈+종건급/진석 파워스케일링 밈을 혼합) —
     * 칭호의 뇌절 강도와 그에 대응하는 일러스트(GroundScene의 when(illustrationId) 분기)가 항상
     * 1:1로 짝지어져 있어 "칭호는 개뇌절인데 그림은 평범"한 불일치가 날 수 없다. 앞으로 단계를
     * 추가하려면 이 리스트에 항목을 더 넣고 GroundScene에 그 illustrationId 분기만 추가하면 된다
     * (핵심 로직 코드 수정 불필요).
     */
    val STAGES: List<Stage> = listOf(
        // 정상 성장 — 평범한 식물 성장 과정
        Stage(1, "씨앗", "seed"),
        Stage(4, "발아", "sprout"),
        Stage(8, "새싹", "sapling"),
        Stage(14, "어린 식물", "young_plant"),
        Stage(22, "무럭무럭 식물", "growing_plant"),
        Stage(33, "꽃봉오리", "budding_flower"),
        Stage(48, "첫 개화", "blooming"),
        Stage(68, "풍성한 화분", "lush_pot"),
        Stage(95, "든든한 나무", "sturdy_tree"),
        // 병맛/뇌절 성장 — 여기부터 이탈리안 브레인롯+한국 밈+파워스케일링 밈 혼합
        Stage(130, "냐냐냥콩", "nyanyang_bean"),
        Stage(175, "트랄랄레로 트랄랄라새싹", "tralalero_sprout"),
        Stage(230, "진짜 존 포크나무", "john_pork_tree"),
        Stage(300, "봄바르디노 크로코딜로나무", "bombardino_tree"),
        Stage(385, "카푸치노카푸치노 아사시노열매!!", "cappuccino_fruit"),
        Stage(490, "진짜 레전드 침팬지니 바나니니콩 종건급", "chimpanzini_jonggeon"),
        Stage(615, "목숨을 건 내친구 진석급 트랄랄레로나무", "jinseok_tralalero"),
        Stage(765, "ㅋㅋㅋ 진짜 레전드 오브 레전드 봄바르디노 단호박콩 ULTRA Ver.2.0", "ultra_bombardino"),
        Stage(945, "신조차 두려워하는 카푸치노 아사시노 잡초", "god_fearing_weed"),
        Stage(1150, "세계관 최강자급 진석조차 인정한 트랄랄레로 바나니니나무 FINAL", "final_boss_tree")
    )

    /** 레벨 기준 현재 단계(칭호+일러스트). 마지막 단계를 넘는 레벨은 마지막 단계를 그대로 유지 —
     *  더 높은 단계가 필요해지면 이 표에 항목만 추가하면 된다. */
    fun stageForLevel(level: Int): Stage = STAGES.lastOrNull { level >= it.levelThreshold } ?: STAGES.first()
}
