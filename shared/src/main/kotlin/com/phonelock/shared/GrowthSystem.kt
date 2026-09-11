package com.phonelock.shared

/**
 * "식물 성장" 시스템(105차 후속, 사용자와 긴 설계 논의 끝에 확정) — 기존 `StudyLevel`(공부 분 기준 레벨)과
 * `CharacterGrowth`(전체 포인트 기준 8단계 캐릭터)를 완전히 대체한다. 두 파일은 서로 다른 축이라
 * "레벨업=식물 성장"이 성립하지 않는 문제가 있었음([[DECISIONS.md]] 105차 참고) — 이제 하나의 경험치
 * 축(growthExp)이 레벨과 식물 칭호/일러스트를 동시에 결정한다.
 *
 * 핵심 설계(사용자 확정):
 * - "칭호"(식물 이름)는 레벨마다 바뀌는 게 아니라 정해진 레벨 구간(STAGES)에 도달해야 바뀐다 —
 *   레벨 숫자는 자주, 칭호는 듬성듬성 오르게 해서 두 종류의 도파민을 분리했다.
 * - 칭호는 런타임에 랜덤 조합하지 않고 전부 미리 정해둔 고정 테이블(STAGES)이다 — 칭호마다 전용
 *   일러스트(`illustrationId`, PlantScreen.kt의 GroundScene이 분기해서 그림)가 1:1로 짝지어져 있어
 *   칭호와 그림이 항상 일치한다(불일치 불가능).
 * - **레벨업 게임 루프(109차 재조정, 이어서 "500레벨+연간 시즌" 개편)**: "빠른 성장 → 성장 둔화 →
 *   정체(벽) → 환생 → 가속 → 다시 정체"가 반복되도록 설계됐다 — 자세한 시뮬레이션/근거는
 *   [[DECISIONS.md]] 109차 "레벨 성장 곡선과 환생 게임 루프 재설계"/"500레벨 및 연간 성장 시스템" 참고.
 *   환생은 레벨/경험치를 초기화하는 대신 영구 EXP 배율을 얻는데, 사이클마다 요구 레벨도 함께
 *   커져서(`rebirthRequiredLevel`) "환생할수록 전부 다 쉬워지는" 게 아니라 "이전 벽을 훨씬 빠르게
 *   돌파하고 더 높은 새 벽에 도달하는" 구조다.
 * - **최대 레벨은 [LEVEL_CAP](500)** — 도달하면 그 시즌(해)의 "정상 성장 완료"로 취급한다. 하루
 *   2~3시간 플레이를 가정하면 약 9개월(270일)에 도달하도록 곡선을 역산했다(daily EXP 가정 등 자세한
 *   근거는 [[DECISIONS.md]] 참고) — 이 가정은 실사용 데이터가 아니라 현재 적립 구조 기반의 추정치이므로
 *   실사용 검증 후 재조정이 필요할 수 있다. **"레벨업 속도"를 빠르게 해달라는 요청은 이 성장 곡선이
 *   아니라 식물 탭의 경험치 적용 애니메이션 재생 속도를 뜻했다** — `PlantScreen.kt`의
 *   `animateExpSegment`/`animateExpApplication` 참고.
 * - **연간 시즌**: 매년 1월 1일(dailyResetHour 기준 "오늘")이 지나면 이번 시즌의 성장 기록
 *   (누적/대기 EXP, 환생 횟수)만 초기화된다 — 계정/설정 등 다른 데이터는 그대로. 초기화 직전 값은
 *   영구 기록(`lifetimeMaxLevel`/`lifetimeRebirthCount`)에 누적해서 남긴다
 *   (`Repository.checkAndResetGrowthSeasonIfNeeded`/`PhoneLockRepository` 동일 함수 참고).
 * - EXP 획득 시 레벨에 즉시 반영되지 않는다(108차) — 획득분은 별도 "대기 EXP"에 먼저 쌓이고, 사용자가
 *   식물 탭에서 "적용" 버튼을 눌러야 그 순간 누적 EXP에 실제로 더해진다([ApplyResult] 참고).
 * - **칭호/등급 체계(109차 후속, 500레벨 상한에 맞춰 전면 재설계)**: 이전(105차) 1150레벨까지의 브레인롯
 *   테이블은 폐기하고, 500레벨을 5개 등급(정상/이상함/초월급/종말급/최강자급)으로 나눈 23단계 고정
 *   테이블로 교체했다 — 사용자와 여러 차례 논의를 거쳐 톤/등급 경계/칭호 문구를 확정([[DECISIONS.md]]
 *   참고). `Stage.tier`가 GroundScene의 배경·나무 형태(등급별로 완전히 다른 실루엣)를 직접 분기한다.
 */
object GrowthSystem {

    /** 시즌(해)당 최대 레벨 — 도달하면 그 해의 "정상 성장"은 완료로 취급한다([[DECISIONS.md]] 참고). */
    const val LEVEL_CAP = 500

    // ---- 레벨/EXP ----

    /** 레벨 L→L+1로 가는 데 필요한 EXP — 지수 곡선(매 레벨 4%씩 요구량 복리 증가, 기준값은 20).
     *  500레벨 상한 도입에 맞춰 계수를 다시 맞췄다(하루 2~3시간 플레이 가정 시 약 9개월에 500레벨 도달,
     *  시뮬레이션 근거는 [[DECISIONS.md]] 참고). 복리율을 4%로 맞춘 이유는 "환생하고 나면 이전 환생
     *  임계점까지는 아주 쉽게 가고, 그 이후 다음 임계점까지가 힘들어야 한다"는 요청 때문 — 50레벨 단위
     *  사이클 안에서 요구량이 이 정도 복리면 사이클 끝(새 임계점)의 요구량이 사이클 시작 근처(이전
     *  임계점)의 요구량보다 압도적으로 커져서, 환생 직후 "이전 임계점까지"는 새 배율 덕에 사이클 전체
     *  시간의 12~14%만에 순식간에 지나가고 나머지 86~88%가 전부 "이전엔 못 가본 새 구간"에 쓰인다
     *  ([[DECISIONS.md]] 시뮬레이션 참고). 복리율을 올린 만큼 아래 [expMultiplier]도 선형에서 지수
     *  증가로 바꿔 총 시즌 길이(9개월)를 그대로 유지했다 — 복리율만 올리고 배율을 안 바꾸면 후반
     *  사이클이 감당 불가능해지는 문제가 있었다(1차 시도, [[DECISIONS.md]] 참고). LEVEL_CAP(500)의
     *  60배가 넘는 레벨까지도 Double 오버플로 없이 안전. */
    fun expRequiredForLevel(level: Int): Double {
        val l = level.coerceAtLeast(1).toDouble()
        return 20.0 * Math.pow(1.04, l)
    }

    /** 레벨 level에 도달하기 위한 누적 EXP(레벨 1은 0). 레벨이 매우 커져도(수천 단위) 단순 루프라 충분히 빠르다. */
    fun cumulativeExpForLevel(level: Int): Double {
        if (level <= 1) return 0.0
        var sum = 0.0
        for (l in 1 until level) sum += expRequiredForLevel(l)
        return sum
    }

    /** 누적 EXP 기준 현재 레벨(1부터, [LEVEL_CAP]에서 상한) — 도달하면 그 시즌은 "다 컸다"로 취급하고
     *  더 이상 레벨이 오르지 않는다(이분 탐색으로 레벨이 커져도 빠르게 찾는다). */
    fun levelForExp(totalExp: Double): Int {
        if (totalExp <= 0.0) return 1
        var hi = 2
        while (hi < LEVEL_CAP && cumulativeExpForLevel(hi) <= totalExp) hi *= 2
        hi = hi.coerceAtMost(LEVEL_CAP)
        var lo = 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cumulativeExpForLevel(mid) <= totalExp) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun isMaxLevel(level: Int): Boolean = level >= LEVEL_CAP

    /** 현재 레벨 구간 안에서의 진행률(0.0~1.0) — 최대 레벨이면 항상 가득 찬 것으로 표시. */
    fun progressToNextLevel(totalExp: Double): Float {
        val level = levelForExp(totalExp)
        if (isMaxLevel(level)) return 1f
        val base = cumulativeExpForLevel(level)
        val next = cumulativeExpForLevel(level + 1)
        val span = next - base
        if (span <= 0.0) return 0f
        return ((totalExp - base) / span).toFloat().coerceIn(0f, 1f)
    }

    /** 다음 레벨까지 남은 EXP — 최대 레벨이면 0. */
    fun expToNextLevel(totalExp: Double): Double {
        val level = levelForExp(totalExp)
        if (isMaxLevel(level)) return 0.0
        return (cumulativeExpForLevel(level + 1) - totalExp).coerceAtLeast(0.0)
    }

    // ---- 환생(Rebirth) ----

    /** 환생 n회차(1부터)를 하기 위해 필요한 레벨 — 500레벨 상한에 맞춰 10회차에 정확히 LEVEL_CAP(500)에
     *  도달하도록 균등 배치(50/100/150/.../500, [[DECISIONS.md]] 시뮬레이션 참고). 1차 시도 때 "환생
     *  0 구간을 늦추려" 첫 요구 레벨을 150까지 올렸다가 "환생 가능 레벨이 너무 높다"는 피드백을 받고
     *  되돌림 — 대신 위 expRequiredForLevel의 기준값을 올려서 난이도를 확보했으므로, 요구 레벨 자체는
     *  다시 익숙한 수준(50 단위)으로 유지한다. */
    fun rebirthRequiredLevel(rebirthIndex: Int): Int = 50 * rebirthIndex

    /** 환생 n회 완료 후 적용되는 EXP 획득 배율 — 지수 증가(`6.64^n`). 위 expRequiredForLevel의 복리율
     *  (4%)과 짝을 이뤄 "환생 직후 이전 임계점까지는 순식간에, 그 이후 다음 임계점까지는 힘들게"를
     *  만든다 — 복리율만으로는 사이클 하나의 총 소요 시간이 계속 늘어나기만 하므로, 배율이 그만큼
     *  빠르게 따라와야 총 9개월이 유지된다(선형 배율로 억지로 맞추면 사이클 사이에 절벽이 생김,
     *  [[DECISIONS.md]] 참고 시뮬레이션). 지수 배율이라 숫자 자체는 후반 회차에서 아주 커 보이지만
     *  (9회차 ≈2500만배) Double 정밀도 안에서 문제없이 동작하며, 사이클 사이 소요 시간은 매끄럽게
     *  이어진다(16.9→20.7→22.6→...→36.4일). */
    fun expMultiplier(rebirthCount: Int): Double = Math.pow(6.64, rebirthCount.toDouble())

    /** 현재 레벨로 환생 가능한지(완료한 환생 횟수 기준 다음 환생 요구 레벨과 비교). */
    fun canRebirth(currentLevel: Int, rebirthCount: Int): Boolean =
        currentLevel >= rebirthRequiredLevel(rebirthCount + 1)

    // ---- 경험치 적용(108차 후속: 획득한 EXP는 즉시 반영되지 않고 누적됐다가 사용자가 "적용"할 때 반영) ----

    /** [Repository/PhoneLockRepository].applyPendingGrowthExp()의 결과 — 적용 전/후 누적 EXP와 레벨을
     *  함께 담아, 호출부(UI)가 그 구간을 애니메이션(경험치바 상승 → 레벨업 → 다음 레벨...)으로 재생할 수 있게 한다. */
    data class ApplyResult(val expBefore: Double, val expAfter: Double, val levelBefore: Int, val levelAfter: Int) {
        val leveledUp: Boolean get() = levelAfter > levelBefore
    }

    // ---- 성장 단계(칭호+일러스트+등급, 전부 미리 정해둔 고정 테이블) ----

    /** tier: 0=정상, 1=이상함, 2=초월급, 3=종말급, 4=최강자급 — PlantScreen.kt의 GroundScene이 이 값으로
     *  하늘/땅 배경과 나무 형태(어린 식물/평범한 나무/신성한 나무/재앙의 나무/세계수) 자체를 분기한다. */
    data class Stage(val levelThreshold: Int, val title: String, val illustrationId: String, val tier: Int)

    /**
     * 칭호 고정 테이블 — 런타임에 랜덤 생성하지 않는다(109차 후속, 500레벨 상한 도입에 맞춰 전면 재설계 —
     * 이전 1150레벨까지의 "이탈리안 브레인롯/파워스케일링 밈" 톤은 폐기됨, [[DECISIONS.md]] 참고).
     * 5단계 등급: 정상(1~150, 평범한 식물 성장) → 이상함(150~250, 위화감이 스며듦) → 초월급(250~350,
     * 격조 있는 한자어로 초월적 존재감) → 종말급(350~450, 재앙급 스케일) → 최강자급(450~500, 절대적
     * 존재). 칭호와 일러스트(illustrationId, GroundScene의 when 분기)가 항상 1:1로 짝지어져 있어
     * 불일치가 날 수 없다. */
    val STAGES: List<Stage> = listOf(
        // 정상(tier 0) — 평범한 식물 성장
        Stage(1, "씨앗", "seed", 0),
        Stage(4, "발아", "sprout", 0),
        Stage(8, "새싹", "sapling", 0),
        Stage(14, "어린잎", "young_leaf", 0),
        Stage(22, "무럭무럭", "growing", 0),
        Stage(33, "꽃봉오리", "bud", 0),
        Stage(48, "첫 개화", "first_bloom", 0),
        Stage(68, "풍성한 화분", "lush_pot", 0),
        Stage(95, "든든한 나무", "sturdy_tree", 0),
        Stage(125, "거목", "giant_tree", 0),
        // 이상함(tier 1) — 뭔가 이상해지기 시작
        Stage(150, "무언가 눈을 뜬 화분", "eye_pot", 1),
        Stage(175, "닿을 수 없는 말을 읊조리는 고목", "murmur_tree", 1),
        Stage(200, "빛조차 삼켜버린 잎", "shadow_leaf", 1),
        Stage(225, "경계가 허물어지는 개화", "warped_bloom", 1),
        // 초월급(tier 2) — 인간의 영역을 벗어남
        Stage(250, "태고의 겁외에서 눈뜬 근원의 뿌리", "glowing_roots", 2),
        Stage(280, "존재 자체가 이치가 되어버린 나무", "geometric_halo", 2),
        Stage(310, "불가해의 경지에 오른 화신목", "afterimage", 2),
        // 종말급(tier 3) — 재앙급 스케일
        Stage(350, "종말조차 외경하는 태고의 존재", "cracked_start", 3),
        Stage(380, "불멸을 초월한 재앙의 근원", "floating_debris", 3),
        Stage(410, "멸망마저 좌정시키는 정원사", "crown_shockwave", 3),
        // 최강자급(tier 4) — 절대적 존재
        Stage(450, "범접(犯接)조차 불허하는 그림자", "giant_shadow", 4),
        Stage(475, "만유(萬有)의 근원이자 정점", "full_aura", 4),
        Stage(500, "형언불가(形言不可)한 절대의 존재", "ultimate", 4)
    )

    /** 레벨 기준 현재 단계(칭호+일러스트+등급). 마지막 단계를 넘는 레벨은 마지막 단계를 그대로 유지. */
    fun stageForLevel(level: Int): Stage = STAGES.lastOrNull { level >= it.levelThreshold } ?: STAGES.first()
}
