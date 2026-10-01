package com.phonelock.shared

/**
 * 홈 탭 "상점"의 성장 물약(138차, 사용자 요청: "꾸미기 → 상점으로 개편, 일정 시간 동안 성장을 가속하는 물약도
 * 팔 것") — 판정은 전부 여기 한 곳에서 하고, 저장/동기화만 각 플랫폼 저장소가 맡는다([GrowthSystem]과 같은 분리).
 *
 * 설계 원칙([[DECISIONS.md]] 138차):
 * - **성장은 실제 활동에서만 나온다.** 물약은 EXP를 직접 주지 않고, 효과 시간 동안 **공부로** 얻는 EXP에만 배율을
 *   곱한다 — "물약을 샀으니 지금 공부하자"로 이어지게 하려는 것이고, 포인트를 EXP로 바로 바꾸는 아이템은 이 원칙에
 *   어긋나 넣지 않았다. 루틴·일정 완료는 누르는 순간의 이벤트라 물약 시간에 몰아서 누르는 요령만 생기고, 138차부터
 *   "하루 달성 비율"로 다시 계산되는 적립이라 배율을 섞으면 계산이 꼬인다 — 그래서 공부 경험치에만 건다.
 * - 공부는 효과 시간과 **겹친 만큼만** 배율을 받는다([averageMultiplier]) — 물약이 끝나기 직전에 3시간짜리
 *   타이머를 정지해도 3시간 전부가 배로 오르지 않고, 효과 중에 시작해 효과가 끝난 뒤 정지해도 효과 시간만큼은
 *   제대로 받는다.
 * - 물약 효과는 **한 번에 하나**만 — 배율끼리 겹치면 계산도 설명도 어려워진다. 같은 물약을 다시 사면 남은 시간이
 *   늘어나고(최대 [MAX_REMAINING_MINUTES]), 다른 물약은 지금 효과가 끝나야 살 수 있다.
 */
object GrowthBoost {

    data class Potion(
        val id: String,
        val emoji: String,
        val label: String,
        val description: String,
        val cost: Int,
        val multiplier: Double,
        val durationMinutes: Int
    )

    /**
     * 판매 목록 — 세 물약 모두 "포인트 1당 공부 경험치 +0.75"(2.5시간 공부 기준)로 효율을 맞추고, 쓰는 방식만
     * 다르게 했다. 성실하게 하루를 보내면 포인트가 약 55P(공부 2.5시간 15P + 루틴 20P + 스트릭 10P + 일정 10P) 쌓이고,
     * 그걸 전부 물약에 써도 공부 경험치가 하루 약 +41(약 +2할) 늘어나는 정도다 — 이 "물약까지 성실히 쓰는 경로"가
     * 약 9개월에 500레벨이 되도록 환생 배율([GrowthSystem.expMultiplier])을 함께 맞췄다([[DECISIONS.md]] 138차).
     */
    val POTIONS: List<Potion> = listOf(
        Potion("boost_small", "🧪", "성장 촉진제", "집중 한 세션 시작 전에 가볍게", 40, 1.5, 60),
        Potion("boost_strong", "⚗️", "폭풍 성장 물약", "한 시간 바짝 몰아칠 때", 80, 2.0, 60),
        Potion("boost_long", "⏳", "지속형 촉진제", "3시간 넘게 집중하는 날 가장 이득", 100, 1.5, 180)
    )

    fun potionById(id: String): Potion? = POTIONS.find { it.id == id }

    /** 같은 물약을 거듭 사서 쌓아둘 수 있는 남은 시간의 상한 — 미리 몇 날치를 사두는 건 막는다. */
    const val MAX_REMAINING_MINUTES = 360

    /** 지난 효과 구간을 남겨두는 기간 — 효과가 끝난 뒤에 정지한 공부 기록도 겹친 시간을 계산할 수 있게 하루 넘게 둔다. */
    const val HISTORY_KEEP_MS = 2L * 24 * 60 * 60 * 1000

    /** 물약 효과 한 구간 [startMillis, endMillis). */
    data class Window(val potionId: String, val startMillis: Long, val endMillis: Long, val multiplier: Double)

    /** 지금 켜져 있는 효과 — 없으면 null. */
    fun activeWindow(windows: List<Window>, nowMillis: Long): Window? =
        windows.lastOrNull { nowMillis >= it.startMillis && nowMillis < it.endMillis }

    /** 한 시각의 배율(효과 밖이면 1) — 길이가 0인 공부 구간에 쓴다. */
    fun multiplierAt(windows: List<Window>, atMillis: Long): Double =
        windows.lastOrNull { atMillis >= it.startMillis && atMillis < it.endMillis }?.multiplier ?: 1.0

    /** 공부 한 구간([startMillis]~[endMillis])이 받는 평균 배율 — 효과 구간과 겹친 시간만큼만 오른다.
     *  구간끼리는 겹치지 않으므로(한 번에 하나) 겹친 길이를 그대로 더하면 된다. */
    fun averageMultiplier(windows: List<Window>, startMillis: Long, endMillis: Long): Double {
        if (endMillis <= startMillis) return multiplierAt(windows, startMillis)
        var extra = 0.0
        windows.forEach { w ->
            val overlap = minOf(endMillis, w.endMillis) - maxOf(startMillis, w.startMillis)
            if (overlap > 0) extra += overlap * (w.multiplier - 1.0)
        }
        return 1.0 + extra / (endMillis - startMillis)
    }

    enum class PurchaseCheck { NEW, EXTEND, OTHER_ACTIVE, TOO_LONG }

    /** 지금 [potion]을 살 수 있는지 — 포인트 잔액은 호출부가 따로 본다. */
    fun checkPurchase(windows: List<Window>, potion: Potion, nowMillis: Long): PurchaseCheck {
        val active = activeWindow(windows, nowMillis) ?: return PurchaseCheck.NEW
        if (active.potionId != potion.id) return PurchaseCheck.OTHER_ACTIVE
        val remainingAfter = (active.endMillis - nowMillis) + potion.durationMinutes * 60_000L
        return if (remainingAfter > MAX_REMAINING_MINUTES * 60_000L) PurchaseCheck.TOO_LONG else PurchaseCheck.EXTEND
    }

    /** 구매를 반영한 새 구간 목록(오래된 기록은 정리) — 살 수 없는 상태면 null. */
    fun applyPurchase(windows: List<Window>, potion: Potion, nowMillis: Long): List<Window>? {
        val kept = prune(windows, nowMillis)
        return when (checkPurchase(kept, potion, nowMillis)) {
            PurchaseCheck.NEW -> kept + Window(potion.id, nowMillis, nowMillis + potion.durationMinutes * 60_000L, potion.multiplier)
            PurchaseCheck.EXTEND -> {
                val active = activeWindow(kept, nowMillis)!!
                kept.map { if (it == active) it.copy(endMillis = it.endMillis + potion.durationMinutes * 60_000L) else it }
            }
            else -> null
        }
    }

    fun prune(windows: List<Window>, nowMillis: Long): List<Window> =
        windows.filter { it.endMillis >= nowMillis - HISTORY_KEEP_MS }

    // ---- 화면 표기(양 플랫폼 상점/홈이 같은 문구를 쓰게) ----

    /** "×1.5" / "×2". */
    fun multiplierLabel(multiplier: Double): String =
        if (multiplier == Math.floor(multiplier)) "×${multiplier.toInt()}" else "×$multiplier"

    /** "1시간" / "1시간 30분" / "45분" — 물약 지속·남은 시간, 홈 "오늘" 카드의 공부 시간이 같이 쓴다. */
    fun durationLabel(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}시간 ${m}분"
            h > 0 -> "${h}시간"
            else -> "${m}분"
        }
    }

    /** 남은 시간 표기 — 1분 미만도 "1분"으로 올려 "0분 남음"이 뜨지 않게 한다. */
    fun remainingLabel(remainingMillis: Long): String =
        durationLabel(((remainingMillis + 59_999) / 60_000).toInt().coerceAtLeast(1))
}
