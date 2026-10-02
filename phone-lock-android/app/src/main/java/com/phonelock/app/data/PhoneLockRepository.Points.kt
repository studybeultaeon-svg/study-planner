package com.phonelock.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.phonelock.shared.GrowthBoost
import com.phonelock.shared.GrowthSystem
import java.time.LocalDate

/**
 * 포인트/보상 시스템(101차+ 세션, IDEAS.md "최우선 후보" — 1차 구현 범위는 포인트 적립+보상 언락만).
 * 잔액은 별도 저장 없이 [PointsLedgerEntry] 전체를 합산해 매번 계산한다(RoutineEngine.currentStreak과
 * 같은 "매번 다시 훑는 순수 파생값" 패턴). 캘린더/루틴과 동일한 "전체 문서 단위 LWW"로 Firebase 동기화
 * (users/{user}/points, 데스크탑판과 대칭).
 *
 * 적립 기준(사용자 확정, 138차에 루틴·일정을 "하루 달성 비율"로 변경):
 * - 공부 시간 비례: 10분당 1포인트([awardStudyPoints], addStudyLogEntry에서 호출)
 * - 루틴: 그날 예정된 루틴 달성 비율 × [GrowthSystem.ROUTINE_DAY_POOL]([onRoutineToggled], toggleRoutineLog에서 호출)
 * - 캘린더 일정: 그날 일정 달성 비율 × [GrowthSystem.CALENDAR_DAY_POOL]([refreshCalendarDayReward], setCalendarTaskStatus에서 호출)
 * - 스트릭(그날 예정된 루틴 전부 완료) 유지 보너스, 날짜당 1회([refreshStreakBonusForDate])
 */

private const val STUDY_SECONDS_PER_POINT = 600 // 10분당 1포인트
private const val STREAK_BONUS_POINTS = 10

/** "하루 달성 비율" 적립이 날짜마다 하나씩 두는 원장 항목의 refId — 137차까지의 완료 1개당 항목(refId
 *  "routine:{id}"/"calendar:{id}")과 구분된다. */
private const val DAY_RATIO_REF_ID = "day"

private var PhoneLockRepository.pointsTs: Long
    get() = preferences.pointsTs
    set(value) { preferences.pointsTs = value }

fun PhoneLockRepository.observePointsBalance(): Flow<Int> = pointsLedgerDao.observeBalance()

suspend fun PhoneLockRepository.getPointsBalance(): Int = pointsLedgerDao.getBalance()

fun PhoneLockRepository.observePointsLedger(): Flow<List<PointsLedgerEntry>> = pointsLedgerDao.observeAll()

/** 캐릭터/식물 성장 기준값 — 보상 교환으로 줄어드는 잔액과 달리 양수 delta만 누적 합산(줄어들지 않음). */
fun PhoneLockRepository.observeEarnedPointsTotal(): Flow<Int> = pointsLedgerDao.observeEarnedTotal()

/** 레벨업 시스템(104차) 기준값 — STUDY 원장 항목만 합산해 분으로 환산(포인트 1개 = 10분). */
fun PhoneLockRepository.observeTotalStudyMinutes(): Flow<Int> = pointsLedgerDao.observeStudyPointsTotal().map { it * 10 }

/** reason+refId+dateKey 조합이 이미 있으면 아무 일도 안 한다(중복 적립 방지) — STREAK처럼
 *  "완료 상태"에 매달린 적립에 쓴다. */
private suspend fun PhoneLockRepository.awardPointsOnce(delta: Int, reason: String, refId: String, dateKey: String) {
    if (pointsLedgerDao.find(reason, refId, dateKey) != null) return
    pointsLedgerDao.insert(PointsLedgerEntry(delta = delta, reason = reason, refId = refId, dateKey = dateKey, timestampMillis = System.currentTimeMillis()))
    pushPointsToFirebase()
    awardGrowthExp(delta.toDouble())
}

/** awardPointsOnce의 반대 — 완료가 취소되면 그때 적립됐던 원장 항목을 그대로 지우고, 같은 적립에
 *  편승했던 성장 EXP도 [revokeGrowthExp]로 같이 걷어낸다(EXP까지 되돌리지 않으면 완료/미완료 토글만
 *  반복해 EXP를 무한히 불릴 수 있다 — 126차 버그 수정). 실제로 적립됐던 양을 그대로 쓰려고 상수가
 *  아니라 원장 항목의 delta를 읽는다. */
private suspend fun PhoneLockRepository.revokePointsOnce(reason: String, refId: String, dateKey: String) {
    val entry = pointsLedgerDao.find(reason, refId, dateKey) ?: return
    pointsLedgerDao.deleteBy(reason, refId, dateKey)
    pushPointsToFirebase()
    revokeGrowthExp(entry.delta.toDouble())
}

/**
 * "하루 달성 비율" 적립(138차) — 그 날짜의 [reason] 적립을 원장 항목 하나([DAY_RATIO_REF_ID])로 두고 [target]에
 * 맞춘다. 옛 규칙의 완료 1개당 항목이 남아 있으면 그 날짜를 다시 건드린 이 시점에 함께 걷어내 새 규칙으로 옮긴다.
 * EXP는 차이만큼만 더하거나 뺀다 — 통째로 회수했다 다시 주면, 이미 레벨에 적용된 몫이 빠졌다가 대기 EXP로
 * 돌아와 루틴을 하나 더 끝냈는데 레벨이 내려가 보이는 일이 생긴다.
 */
private suspend fun PhoneLockRepository.setDayRatioReward(reason: String, dateKey: String, target: Int) {
    val entries = pointsLedgerDao.getByReasonAndDate(reason, dateKey)
    val current = entries.sumOf { it.delta }
    val hasLegacy = entries.any { it.refId != DAY_RATIO_REF_ID }
    if (current == target && !hasLegacy) return
    db.withTransaction {
        pointsLedgerDao.deleteByReasonAndDate(reason, dateKey)
        if (target > 0) {
            pointsLedgerDao.insert(PointsLedgerEntry(delta = target, reason = reason, refId = DAY_RATIO_REF_ID, dateKey = dateKey, timestampMillis = System.currentTimeMillis()))
        }
    }
    pushPointsToFirebase()
    if (target > current) awardGrowthExp((target - current).toDouble())
    else if (target < current) revokeGrowthExp((current - target).toDouble())
}

/** 공부 시간 비례 적립(10분당 1포인트) — 세션마다 별개 기록이라 중복 판정 없이 매번 insert.
 *  [startedAt]은 그 공부 구간의 시작 시각 — 물약 효과와 겹친 시간만큼만 EXP 배율을 받는다(138차). */
suspend fun PhoneLockRepository.awardStudyPoints(seconds: Int, dateKey: String, startedAt: Long) {
    val points = seconds / STUDY_SECONDS_PER_POINT
    if (points <= 0) return
    pointsLedgerDao.insert(PointsLedgerEntry(delta = points, reason = "STUDY", refId = "", dateKey = dateKey, timestampMillis = System.currentTimeMillis()))
    pushPointsToFirebase()
    // "식물 성장" EXP는 1분=1EXP로 더 촘촘하게 — 포인트(10분=1P)와 단위가 달라 seconds에서 직접 계산.
    awardGrowthExp(seconds / 60.0, GrowthBoost.averageMultiplier(growthBoostWindows(), startedAt, startedAt + seconds * 1000L))
}

/** 루틴 완료 토글 직후 호출 — 그날 루틴 달성 비율 적립 재계산 + 스트릭 보너스 재판정. 138차: 완료 1개당 5를
 *  주던 방식은 루틴을 늘리기만 해도 경험치가 불어나서, 그날 예정된 루틴 중 끝낸 비율로 바꿨다. */
suspend fun PhoneLockRepository.onRoutineToggled(dateKey: String) {
    val date = runCatching { LocalDate.parse(dateKey) }.getOrNull() ?: return
    val scheduled = routineDao.getAll().filter { com.phonelock.app.routine.RoutineEngine.isScheduledOn(it, date) }
    val doneIds = routineLogDao.getByDate(dateKey).map { it.routineId }.toSet()
    val target = GrowthSystem.dayRatioReward(GrowthSystem.ROUTINE_DAY_POOL, scheduled.count { it.id in doneIds }, scheduled.size)
    setDayRatioReward("ROUTINE", dateKey, target)
    refreshStreakBonusForDate(dateKey)
}

/** RoutineEngine.dayResult와 동일한 "그날 예정된 루틴을 전부 완료했는가" 판정 — 전부 완료면 날짜당 1회
 *  보너스를 적립하고, 이미 적립된 상태에서 하나라도 미완료가 되면 되돌린다. */
suspend fun PhoneLockRepository.refreshStreakBonusForDate(dateKey: String) {
    val date = runCatching { LocalDate.parse(dateKey) }.getOrNull() ?: return
    val routines = routineDao.getAll()
    val scheduled = routines.filter { com.phonelock.app.routine.RoutineEngine.isScheduledOn(it, date) }
    if (scheduled.isEmpty()) {
        revokePointsOnce("STREAK", "streak", dateKey)
        return
    }
    val doneIds = routineLogDao.getByDate(dateKey).map { it.routineId }.toSet()
    val allDone = scheduled.all { it.id in doneIds }
    if (allDone) awardPointsOnce(STREAK_BONUS_POINTS, "STREAK", "streak", dateKey)
    else revokePointsOnce("STREAK", "streak", dateKey)
}

/** 캘린더 일정 완료/미완료 전환 직후 호출(setCalendarTaskStatus) — 그날 일정 달성 비율로 적립을 다시 맞춘다
 *  (138차, 루틴과 같은 이유로 완료 1개당 5에서 변경). */
suspend fun PhoneLockRepository.refreshCalendarDayReward(dateKey: String) {
    val tasks = calendarTaskDao.getByDate(dateKey)
    val target = GrowthSystem.dayRatioReward(GrowthSystem.CALENDAR_DAY_POOL, tasks.count { it.status == "O" }, tasks.size)
    setDayRatioReward("CALENDAR", dateKey, target)
}

// 꾸미기(116~121차, 포인트로 사서 식물 장면에 놓던 소품)는 149차에 장면이 우주로 바뀌면서 없앴다. 산 포인트는 돌려주지
// 않았고(사용자 결정), 소유/배치 목록은 성장 문서 동기화 호환을 위해 저장·동기화(PhoneLockRepository.Growth.kt)만 남아 있다.

val PhoneLockRepository.ownedDecorationIds: Set<String>
    get() = preferences.ownedDecorationIdsCsv.split(",").filter { it.isNotBlank() }.toSet()

val PhoneLockRepository.equippedDecorationIds: List<String>
    get() = preferences.equippedDecorationIdsCsv.split(",").filter { it.isNotBlank() }

/**
 * 상점 성장 물약 구매(138차) — 구매 즉시 효과가 시작된다(가방에 넣어뒀다 쓰는 단계를 두지 않았다: "지금부터
 * 공부할 테니 산다"가 이 아이템의 쓰임새라 한 번의 동작으로 끝나는 게 맞다). 살 수 있는지는
 * [GrowthBoost.checkPurchase]가 정하고, 포인트가 모자라거나 살 수 없는 상태면 원장도 효과도 건드리지 않고 false.
 * refId에 구매 시각을 붙여 같은 물약을 여러 번 사도 원장 항목이 서로 구분되게 한다.
 */
suspend fun PhoneLockRepository.purchasePotion(potionId: String): Boolean {
    val potion = GrowthBoost.potionById(potionId) ?: return false
    if (getPointsBalance() < potion.cost) return false
    val now = System.currentTimeMillis()
    val next = GrowthBoost.applyPurchase(growthBoostWindows(), potion, now) ?: return false
    pointsLedgerDao.insert(PointsLedgerEntry(delta = -potion.cost, reason = "POTION", refId = "${potion.id}:$now", dateKey = LocalDate.now().toString(), timestampMillis = now))
    setGrowthBoostWindows(next)
    pushPointsToFirebase()
    pushGrowthToFirebase()
    return true
}

// ══════════════════════════════════════════════════════
// Firebase 동기화 — 캘린더/루틴과 동일한 "전체 문서 단위 LWW"(users/{user}/points).
// ══════════════════════════════════════════════════════

fun PhoneLockRepository.pointsLedgerToJson(ledger: List<PointsLedgerEntry>): JSONArray {
    val arr = JSONArray()
    ledger.forEach { e ->
        arr.put(JSONObject().apply {
            put("delta", e.delta)
            put("reason", e.reason)
            put("refId", e.refId)
            put("dateKey", e.dateKey)
            put("timestampMillis", e.timestampMillis)
        })
    }
    return arr
}

private fun pointsLedgerFromJson(json: JSONArray): List<PointsLedgerEntry> {
    val out = mutableListOf<PointsLedgerEntry>()
    for (i in 0 until json.length()) {
        val e = json.getJSONObject(i)
        out.add(
            PointsLedgerEntry(
                delta = e.optInt("delta", 0),
                reason = e.optString("reason", ""),
                refId = e.optString("refId", ""),
                dateKey = e.optString("dateKey", ""),
                timestampMillis = e.optLong("timestampMillis", 0L)
            )
        )
    }
    return out
}

/** 변경 직후 fire-and-forget으로 Firebase에 포인트 원장을 올린다. "보상" 필드는 108차에 기능 자체가
 *  삭제됐지만, 다른 기기의 구버전 앱이 같은 문서를 읽을 수 있어 와이어 포맷은 그대로 유지하고 빈 배열만
 *  채워 보낸다. */
fun PhoneLockRepository.pushPointsToFirebase() {
    val ts = System.currentTimeMillis()
    pointsTs = ts
    ioScope.launch {
        val ledgerJson = pointsLedgerToJson(pointsLedgerDao.getAllOnce())
        com.phonelock.app.service.PomodoroSyncClient.writePoints(fbDatabaseUrl, fbApiKey, ledgerJson, JSONArray(), ts)
    }
}

/** 포인트 화면 진입 시 호출 — 원격이 로컬보다 최신이면 로컬을 덮어쓰고, 로컬이 더 최신이면 원격에 푸시. */
suspend fun PhoneLockRepository.syncPointsFromFirebase() {
    val result = com.phonelock.app.service.PomodoroSyncClient.readPoints(fbDatabaseUrl, fbApiKey) ?: return
    if (result.ts > pointsTs) {
        val ledger = pointsLedgerFromJson(result.ledgerJson)
        db.withTransaction {
            pointsLedgerDao.deleteAll()
            ledger.forEach { pointsLedgerDao.insert(it) }
        }
        pointsTs = result.ts
    } else if (pointsTs > result.ts) {
        pushPointsToFirebase()
    }
}
