package com.phonelock.desktop.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 포인트/보상 시스템(101차+ 세션, IDEAS.md "최우선 후보" — 1차 구현 범위는 포인트 적립+보상 언락만),
 * 안드로이드 PhoneLockRepository.Points.kt와 대칭. 잔액은 저장하지 않고 [AppData.pointsLedger] 전체를
 * 합산해 매번 계산한다. 캘린더/루틴과 동일한 "전체 문서 단위 LWW"로 Firebase 동기화(users/{user}/points).
 *
 * 적립 기준(사용자 확정): 공부 10분당 1P / 루틴 완료 5P / 캘린더 일정 완료 5P / 그날 예정 루틴 전부
 * 완료 시 스트릭 보너스 10P(날짜당 1회).
 */

private const val STUDY_SECONDS_PER_POINT = 600
private const val ROUTINE_COMPLETE_POINTS = 5
private const val CALENDAR_COMPLETE_POINTS = 5
private const val STREAK_BONUS_POINTS = 10

fun Repository.getPointsBalance(): Int = synchronized(lock) { data.pointsLedger.sumOf { it.delta } }

fun Repository.getPointsLedger(): List<PointsLedgerEntry> = synchronized(lock) { data.pointsLedger.toList() }

/** 캐릭터/식물 성장 기준값 — 보상 교환으로 줄어드는 잔액과 달리 양수 delta만 누적 합산(줄어들지 않음). */
fun Repository.getEarnedPointsTotal(): Int = synchronized(lock) { data.pointsLedger.filter { it.delta > 0 }.sumOf { it.delta } }

/** 레벨업 시스템(104차) 기준값 — STUDY 원장 항목만 합산해 분으로 환산(포인트 1개 = 10분). */
fun Repository.getTotalStudyMinutes(): Int = synchronized(lock) { data.pointsLedger.filter { it.reason == "STUDY" }.sumOf { it.delta } * 10 }

/** reason+refId+dateKey 조합이 이미 있으면 아무 일도 안 한다(중복 적립 방지). 호출부가 이미 lock을 쥐고 있어야 한다. */
private fun Repository.awardPointsOnce(delta: Int, reason: String, refId: String, dateKey: String) {
    if (data.pointsLedger.any { it.reason == reason && it.refId == refId && it.dateKey == dateKey }) return
    data.pointsLedger.add(PointsLedgerEntry(delta = delta, reason = reason, refId = refId, dateKey = dateKey, timestampMillis = System.currentTimeMillis()))
    persist()
    pushPointsToFirebase()
    awardGrowthExp(delta.toDouble())
}

/** awardPointsOnce의 반대 — 완료가 취소되면 그때 적립됐던 원장 항목을 그대로 지운다. 호출부가 이미 lock을 쥐고 있어야 한다. */
private fun Repository.revokePointsOnce(reason: String, refId: String, dateKey: String) {
    val existed = data.pointsLedger.removeAll { it.reason == reason && it.refId == refId && it.dateKey == dateKey }
    if (!existed) return
    persist()
    pushPointsToFirebase()
}

/** 공부 시간 비례 적립(10분당 1포인트) — 세션마다 별개 기록이라 중복 판정 없이 매번 추가. 호출부가 이미 lock을 쥐고 있어야 한다. */
fun Repository.awardStudyPoints(seconds: Int, dateKey: String) {
    val points = seconds / STUDY_SECONDS_PER_POINT
    if (points <= 0) return
    data.pointsLedger.add(PointsLedgerEntry(delta = points, reason = "STUDY", refId = "", dateKey = dateKey, timestampMillis = System.currentTimeMillis()))
    persist()
    pushPointsToFirebase()
    // "식물 성장" EXP는 1분=1EXP로 더 촘촘하게 — 포인트(10분=1P)와 단위가 달라 seconds에서 직접 계산.
    awardGrowthExp(seconds / 60.0)
}

/** 루틴 완료 토글 직후 호출 — 완료 포인트 적립/회수 + 그날 스트릭 보너스 재판정. 호출부가 이미 lock을 쥐고 있어야 한다. */
fun Repository.onRoutineToggled(routineId: Long, dateKey: String, completed: Boolean) {
    if (completed) awardPointsOnce(ROUTINE_COMPLETE_POINTS, "ROUTINE", "routine:$routineId", dateKey)
    else revokePointsOnce("ROUTINE", "routine:$routineId", dateKey)
    refreshStreakBonusForDate(dateKey)
}

/** RoutineEngine의 "그날 예정된 루틴을 전부 완료했는가" 판정과 동일 — 전부 완료면 날짜당 1회 보너스를
 *  적립하고, 이미 적립된 상태에서 하나라도 미완료가 되면 되돌린다. 호출부가 이미 lock을 쥐고 있어야 한다. */
private fun Repository.refreshStreakBonusForDate(dateKey: String) {
    val date = runCatching { java.time.LocalDate.parse(dateKey) }.getOrNull() ?: return
    val scheduled = data.routines.filter { !it.archived && com.phonelock.desktop.routine.RoutineEngine.isScheduledOn(it, date) }
    if (scheduled.isEmpty()) {
        revokePointsOnce("STREAK", "streak", dateKey)
        return
    }
    val doneIds = data.routineLogs.filter { it.dateKey == dateKey }.map { it.routineId }.toSet()
    val allDone = scheduled.all { it.id in doneIds }
    if (allDone) awardPointsOnce(STREAK_BONUS_POINTS, "STREAK", "streak", dateKey)
    else revokePointsOnce("STREAK", "streak", dateKey)
}

/** 캘린더 일정 완료 전환 직후 호출(setCalendarTaskStatus) — completed=true면 적립, false면 회수.
 *  호출부가 이미 lock을 쥐고 있어야 한다. */
fun Repository.onCalendarTaskCompletionChanged(refId: String, dateKey: String, completed: Boolean) {
    if (completed) awardPointsOnce(CALENDAR_COMPLETE_POINTS, "CALENDAR", refId, dateKey)
    else revokePointsOnce("CALENDAR", refId, dateKey)
}

// ══════════════════════════════════════════════════════
// 나무 주변 장식 아이템(116차) — 포인트로 구매해 홈 화면에 배치. 121차부터 소유/장착 상태도 성장
// 문서(users/{user}/growth)에 같이 실려 기기 간에 따라간다 — 구매 재화(포인트)가 이미 동기화되는데
// 구매 결과만 한 기기에 갇혀 있으면 "포인트를 썼는데 이 기기엔 아무것도 없다"가 되기 때문(116차 판단 변경).
// ══════════════════════════════════════════════════════

/** 홈 화면에 동시에 배치할 수 있는 장식 개수 — UI(PlantScreen)와 저장 계층이 같은 상한을 보게 공유한다.
 *  121차에 3 → 5로 올렸다: 장식이 "소품"과 "배경" 두 종류로 나뉘면서 배경 하나만 걸어도 소품 자리가 두 개밖에
 *  안 남아, 사서 배치할 수 있는 양이 구매 만족감에 비해 너무 적었다. */
const val MAX_EQUIPPED_DECORATIONS = 5

fun Repository.getOwnedDecorationIds(): Set<String> = synchronized(lock) { data.ownedDecorationIds.toSet() }

fun Repository.getEquippedDecorationIds(): List<String> = synchronized(lock) { data.equippedDecorationIds.toList() }

/** 이미 소유했거나 포인트가 모자라면 false. 성공하면 잔액에서 즉시 차감(음수 delta 원장 항목)하고 소유 목록에
 *  추가한다. 121차: 자리가 남아 있으면 곧바로 배치까지 해준다 — "구매 → 홈 화면에 눈에 띄는 변화"가 한 번의
 *  동작으로 이어져야 포인트를 쓴 보람이 느껴진다는 사용자 요청. 자리가 없으면 소유만 하고 배치는 사용자가 고른다. */
fun Repository.purchaseDecoration(id: String, cost: Int): Boolean = synchronized(lock) {
    if (id in data.ownedDecorationIds) return@synchronized false
    if (getPointsBalance() < cost) return@synchronized false
    data.pointsLedger.add(
        PointsLedgerEntry(delta = -cost, reason = "DECORATION", refId = id, dateKey = java.time.LocalDate.now().toString(), timestampMillis = System.currentTimeMillis())
    )
    data.ownedDecorationIds.add(id)
    if (data.equippedDecorationIds.size < MAX_EQUIPPED_DECORATIONS) data.equippedDecorationIds.add(id)
    persist()
    pushPointsToFirebase()
    pushGrowthToFirebase()
    true
}

/** 소유하지 않은 id는 무시하고, 최대 3개까지만 받는다(리스트 순서=배치 슬롯 순서). */
fun Repository.setEquippedDecorationIds(ids: List<String>) {
    synchronized(lock) {
        data.equippedDecorationIds.clear()
        data.equippedDecorationIds.addAll(ids.filter { it in data.ownedDecorationIds }.take(MAX_EQUIPPED_DECORATIONS))
        persist()
        pushGrowthToFirebase()
    }
}

// ══════════════════════════════════════════════════════
// Firebase 동기화 — 캘린더/루틴과 동일한 "전체 문서 단위 LWW"(users/{user}/points).
// ══════════════════════════════════════════════════════

private fun pointsLedgerToJson(ledger: List<PointsLedgerEntry>): JSONArray {
    val arr = JSONArray()
    ledger.forEach { e ->
        arr.put(JSONObject().apply {
            put("delta", e.delta); put("reason", e.reason); put("refId", e.refId)
            put("dateKey", e.dateKey); put("timestampMillis", e.timestampMillis)
        })
    }
    return arr
}

private fun pointsLedgerFromJson(json: JSONArray): MutableList<PointsLedgerEntry> {
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

/** 변경 직후 fire-and-forget으로 Firebase에 포인트 원장을 올린다(호출부는 이미 lock을 쥐고 있음).
 *  "보상" 필드는 108차에 기능 자체가 삭제됐지만, 다른 기기의 구버전 앱이 같은 문서를 읽을 수 있어
 *  와이어 포맷은 그대로 유지하고 빈 배열만 채워 보낸다. */
fun Repository.pushPointsToFirebase() {
    val ts = System.currentTimeMillis()
    data.pointsTs = ts
    val ledgerJson = pointsLedgerToJson(data.pointsLedger)
    val url = data.fbDatabaseUrl; val key = data.fbApiKey
    Thread {
        com.phonelock.desktop.monitor.PomodoroSyncClient.writePoints(url, key, ledgerJson, JSONArray(), ts)
    }.start()
}

/**
 * 포인트 화면 진입 시 호출 — 원격이 로컬보다 최신이면 로컬을 덮어쓰고, 로컬이 더 최신이면 반대로
 * 원격에 푸시한다. 네트워크 호출을 포함하므로 호출부(UI)에서 백그라운드 스레드에서 실행할 것.
 */
fun Repository.syncPointsFromFirebase() {
    val (url, key) = synchronized(lock) { data.fbDatabaseUrl to data.fbApiKey }
    val result = com.phonelock.desktop.monitor.PomodoroSyncClient.readPoints(url, key) ?: return
    synchronized(lock) {
        if (result.ts > data.pointsTs) {
            val ledger = pointsLedgerFromJson(result.ledgerJson)
            data.pointsLedger.clear(); data.pointsLedger.addAll(ledger)
            data.pointsTs = result.ts
            persist()
        } else if (data.pointsTs > result.ts) {
            pushPointsToFirebase()
        }
    }
}
