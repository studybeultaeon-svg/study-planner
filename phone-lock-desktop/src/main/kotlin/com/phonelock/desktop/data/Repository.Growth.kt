package com.phonelock.desktop.data

import com.phonelock.shared.GrowthSystem

/**
 * "식물 성장" EXP/환생(105차 후속) — [GrowthSystem]의 순수 로직을 실제 저장값([AppData.growthExpTotal]/
 * [AppData.rebirthCount])에 연결한다. EXP 적립은 기존 포인트 적립 이벤트(Repository.Points.kt의
 * awardStudyPoints/awardPointsOnce)에 편승한다 — 같은 "공부/루틴/캘린더/스트릭" 트리거를 그대로 쓰고,
 * 환생 배율만 곱해서 별도 누적값에 더한다. 포인트(보상샵 화폐)는 이 시스템과 무관하게 그대로 둔다.
 *
 * 108차 후속: 적립된 EXP는 [AppData.growthExpTotal]에 바로 더해지지 않고 [AppData.growthExpPending]에
 * 먼저 쌓인다 — 식물 탭에서 사용자가 "적용" 버튼을 눌러야 [applyPendingGrowthExp]가 그 순간 레벨에
 * 반영한다(사용자가 레벨업 과정에 직접 참여하는 느낌을 주기 위한 게임성 강화, 사용자 요청).
 */

/** 포인트 적립과 같은 raw 양(공부 분, 혹은 루틴/캘린더/스트릭 지급량)에 환생 배율을 곱해 "대기 EXP"에 적립.
 *  호출부(Repository.Points.kt)가 이미 lock을 쥐고 있어야 한다. */
internal fun Repository.awardGrowthExp(rawAmount: Double) {
    if (rawAmount <= 0.0) return
    data.growthExpPending += rawAmount * GrowthSystem.expMultiplier(data.rebirthCount)
    persist()
}

fun Repository.getGrowthExpTotal(): Double = synchronized(lock) { data.growthExpTotal }

fun Repository.getGrowthExpPending(): Double = synchronized(lock) { data.growthExpPending }

fun Repository.getRebirthCount(): Int = synchronized(lock) { data.rebirthCount }

/** 대기 EXP를 누적 EXP에 반영 — 적용 전/후 EXP·레벨을 함께 돌려줘서 UI가 그 구간을 애니메이션으로
 *  재생할 수 있게 한다. 대기 EXP가 0 이하면 아무 일도 안 하고 null. */
fun Repository.applyPendingGrowthExp(): GrowthSystem.ApplyResult? = synchronized(lock) {
    val pending = data.growthExpPending
    if (pending <= 0.0) return@synchronized null
    val before = data.growthExpTotal
    val levelBefore = GrowthSystem.levelForExp(before)
    val after = before + pending
    data.growthExpTotal = after
    data.growthExpPending = 0.0
    persist()
    GrowthSystem.ApplyResult(before, after, levelBefore, GrowthSystem.levelForExp(after))
}

/** 환생 — 현재 레벨이 다음 환생에 필요한 레벨 이상이면 누적/대기 EXP를 전부 초기화하고 환생 횟수를 올린다
 *  (영구, EXP 배율 상승). 조건 미달이면 아무 일도 안 하고 false. */
fun Repository.rebirth(): Boolean = synchronized(lock) {
    val level = GrowthSystem.levelForExp(data.growthExpTotal)
    if (!GrowthSystem.canRebirth(level, data.rebirthCount)) return@synchronized false
    data.growthExpTotal = 0.0
    data.growthExpPending = 0.0
    data.rebirthCount += 1
    persist()
    true
}
