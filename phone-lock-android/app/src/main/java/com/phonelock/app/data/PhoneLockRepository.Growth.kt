package com.phonelock.app.data

import com.phonelock.shared.GrowthSystem

/**
 * "식물 성장" EXP/환생(105차 후속, 데스크탑판과 대칭) — [GrowthSystem]의 순수 로직을 실제 저장값
 * (AppPreferences.growthExpTotal/rebirthCount)에 연결한다. EXP 적립은 기존 포인트 적립 이벤트
 * (PhoneLockRepository.Points.kt의 awardStudyPoints/awardPointsOnce)에 편승한다 — 같은
 * "공부/루틴/캘린더/스트릭" 트리거를 그대로 쓰고, 환생 배율만 곱해서 별도 누적값에 더한다. 포인트
 * (보상샵 화폐)는 이 시스템과 무관하게 그대로 둔다.
 *
 * 108차 후속: 적립된 EXP는 growthExpTotal에 바로 더해지지 않고 growthExpPending에 먼저 쌓인다 — 식물
 * 탭에서 사용자가 "적용" 버튼을 눌러야 [applyPendingGrowthExp]가 그 순간 레벨에 반영한다.
 */

/** 포인트 적립과 같은 raw 양에 환생 배율을 곱해 "대기 EXP"에 적립. */
internal fun PhoneLockRepository.awardGrowthExp(rawAmount: Double) {
    if (rawAmount <= 0.0) return
    preferences.growthExpPending += rawAmount * GrowthSystem.expMultiplier(preferences.rebirthCount)
}

fun PhoneLockRepository.getGrowthExpTotal(): Double = preferences.growthExpTotal

fun PhoneLockRepository.getGrowthExpPending(): Double = preferences.growthExpPending

fun PhoneLockRepository.getRebirthCount(): Int = preferences.rebirthCount

/** 대기 EXP를 누적 EXP에 반영 — 적용 전/후 EXP·레벨을 함께 돌려줘서 UI가 그 구간을 애니메이션으로
 *  재생할 수 있게 한다. 대기 EXP가 0 이하면 아무 일도 안 하고 null. */
fun PhoneLockRepository.applyPendingGrowthExp(): GrowthSystem.ApplyResult? {
    val pending = preferences.growthExpPending
    if (pending <= 0.0) return null
    val before = preferences.growthExpTotal
    val levelBefore = GrowthSystem.levelForExp(before)
    val after = before + pending
    preferences.growthExpTotal = after
    preferences.growthExpPending = 0.0
    return GrowthSystem.ApplyResult(before, after, levelBefore, GrowthSystem.levelForExp(after))
}

/** 환생 — 현재 레벨이 다음 환생에 필요한 레벨 이상이면 누적/대기 EXP를 전부 초기화하고 이번 시즌 환생
 *  횟수를 올린다(EXP 배율 상승, LEVEL_CAP 도달 전까지 유효). 조건 미달이면 아무 일도 안 하고 false. */
fun PhoneLockRepository.rebirth(): Boolean {
    val level = GrowthSystem.levelForExp(preferences.growthExpTotal)
    if (!GrowthSystem.canRebirth(level, preferences.rebirthCount)) return false
    preferences.growthExpTotal = 0.0
    preferences.growthExpPending = 0.0
    preferences.rebirthCount += 1
    return true
}

fun PhoneLockRepository.getLifetimeMaxLevel(): Int = preferences.lifetimeMaxLevel

fun PhoneLockRepository.getLifetimeRebirthCount(): Int = preferences.lifetimeRebirthCount

/** 연간 시즌 초기화(109차 후속, "500레벨+연간 성장 시스템", 데스크탑판과 대칭) — 매년 1월 1일
 *  (dailyResetHour 기준 "오늘")이 지나면 이번 시즌의 성장 기록(누적/대기 EXP, 이번 시즌 환생 횟수)만
 *  초기화한다. 계정/설정/포인트 등 다른 데이터는 손대지 않는다. 초기화 직전 값은 영구 기록
 *  (lifetimeMaxLevel/lifetimeRebirthCount)에 누적해서 남긴다. 최초 실행(growthSeasonYear=0)이면
 *  지울 게 없으므로 연도만 기록하고 끝낸다. `runDailyMaintenanceIfNeeded`와 같은 하루 1회 가드 경로에서
 *  호출되는 걸 전제로, 이미 올해 처리됐으면 아무 일도 안 한다. */
fun PhoneLockRepository.checkAndResetGrowthSeasonIfNeeded() {
    val currentYear = effectiveDate(preferences.dailyResetHour).year
    if (preferences.growthSeasonYear == currentYear) return
    if (preferences.growthSeasonYear != 0) {
        val level = GrowthSystem.levelForExp(preferences.growthExpTotal)
        preferences.lifetimeMaxLevel = maxOf(preferences.lifetimeMaxLevel, level)
        preferences.lifetimeRebirthCount += preferences.rebirthCount
        preferences.growthExpTotal = 0.0
        preferences.growthExpPending = 0.0
        preferences.rebirthCount = 0
    }
    preferences.growthSeasonYear = currentYear
}
