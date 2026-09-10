package com.phonelock.desktop.data

import com.phonelock.shared.GrowthSystem

/**
 * "식물 성장" EXP/환생(105차 후속) — [GrowthSystem]의 순수 로직을 실제 저장값([AppData.growthExpTotal]/
 * [AppData.rebirthCount])에 연결한다. EXP 적립은 기존 포인트 적립 이벤트(Repository.Points.kt의
 * awardStudyPoints/awardPointsOnce)에 편승한다 — 같은 "공부/루틴/캘린더/스트릭" 트리거를 그대로 쓰고,
 * 환생 배율만 곱해서 별도 누적값에 더한다. 포인트(보상샵 화폐)는 이 시스템과 무관하게 그대로 둔다.
 */

/** 포인트 적립과 같은 raw 양(공부 분, 혹은 루틴/캘린더/스트릭 지급량)에 환생 배율을 곱해 적립.
 *  호출부(Repository.Points.kt)가 이미 lock을 쥐고 있어야 한다. */
internal fun Repository.awardGrowthExp(rawAmount: Double) {
    if (rawAmount <= 0.0) return
    data.growthExpTotal += rawAmount * GrowthSystem.expMultiplier(data.rebirthCount)
    persist()
}

fun Repository.getGrowthExpTotal(): Double = synchronized(lock) { data.growthExpTotal }

fun Repository.getRebirthCount(): Int = synchronized(lock) { data.rebirthCount }

/** 환생 — 현재 레벨이 다음 환생에 필요한 레벨 이상이면 누적 EXP/레벨을 초기화하고 환생 횟수를 올린다
 *  (영구, EXP 배율 상승). 조건 미달이면 아무 일도 안 하고 false. */
fun Repository.rebirth(): Boolean = synchronized(lock) {
    val level = GrowthSystem.levelForExp(data.growthExpTotal)
    if (!GrowthSystem.canRebirth(level, data.rebirthCount)) return@synchronized false
    data.growthExpTotal = 0.0
    data.rebirthCount += 1
    persist()
    true
}
