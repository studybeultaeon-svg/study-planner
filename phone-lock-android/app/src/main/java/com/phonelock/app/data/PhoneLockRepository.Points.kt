package com.phonelock.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 포인트/보상 시스템(101차+ 세션, IDEAS.md "최우선 후보" — 1차 구현 범위는 포인트 적립+보상 언락만).
 * 잔액은 별도 저장 없이 [PointsLedgerEntry] 전체를 합산해 매번 계산한다(RoutineEngine.currentStreak과
 * 같은 "매번 다시 훑는 순수 파생값" 패턴). 캘린더/루틴과 동일한 "전체 문서 단위 LWW"로 Firebase 동기화
 * (users/{user}/points, 데스크탑판과 대칭).
 *
 * 적립 기준(사용자 확정):
 * - 공부 시간 비례: 10분당 1포인트([awardStudyPoints], addStudyLogEntry에서 호출)
 * - 루틴 완료 시 고정 포인트([onRoutineToggled], toggleRoutineLog에서 호출)
 * - 캘린더 일정 완료 시 고정 포인트(setCalendarTaskStatus에서 호출)
 * - 스트릭(그날 예정된 루틴 전부 완료) 유지 보너스, 날짜당 1회([refreshStreakBonusForDate])
 */

private const val STUDY_SECONDS_PER_POINT = 600 // 10분당 1포인트
private const val ROUTINE_COMPLETE_POINTS = 5
private const val CALENDAR_COMPLETE_POINTS = 5
private const val STREAK_BONUS_POINTS = 10

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

/** reason+refId+dateKey 조합이 이미 있으면 아무 일도 안 한다(중복 적립 방지) — ROUTINE/CALENDAR/STREAK처럼
 *  "완료 상태"에 매달린 적립에 쓴다. */
private suspend fun PhoneLockRepository.awardPointsOnce(delta: Int, reason: String, refId: String, dateKey: String) {
    if (pointsLedgerDao.find(reason, refId, dateKey) != null) return
    pointsLedgerDao.insert(PointsLedgerEntry(delta = delta, reason = reason, refId = refId, dateKey = dateKey, timestampMillis = System.currentTimeMillis()))
    pushPointsToFirebase()
    awardGrowthExp(delta.toDouble())
}

/** awardPointsOnce의 반대 — 완료가 취소되면 그때 적립됐던 원장 항목을 그대로 지운다. */
private suspend fun PhoneLockRepository.revokePointsOnce(reason: String, refId: String, dateKey: String) {
    if (pointsLedgerDao.find(reason, refId, dateKey) == null) return
    pointsLedgerDao.deleteBy(reason, refId, dateKey)
    pushPointsToFirebase()
}

/** 공부 시간 비례 적립(10분당 1포인트) — 세션마다 별개 기록이라 중복 판정 없이 매번 insert. */
suspend fun PhoneLockRepository.awardStudyPoints(seconds: Int, dateKey: String) {
    val points = seconds / STUDY_SECONDS_PER_POINT
    if (points <= 0) return
    pointsLedgerDao.insert(PointsLedgerEntry(delta = points, reason = "STUDY", refId = "", dateKey = dateKey, timestampMillis = System.currentTimeMillis()))
    pushPointsToFirebase()
    // "식물 성장" EXP는 1분=1EXP로 더 촘촘하게 — 포인트(10분=1P)와 단위가 달라 seconds에서 직접 계산.
    awardGrowthExp(seconds / 60.0)
}

/** 루틴 완료 토글 직후 호출 — 완료 포인트 적립/회수 + 그날 스트릭 보너스 재판정. */
suspend fun PhoneLockRepository.onRoutineToggled(routineId: Long, dateKey: String, completed: Boolean) {
    if (completed) {
        awardPointsOnce(ROUTINE_COMPLETE_POINTS, "ROUTINE", "routine:$routineId", dateKey)
    } else {
        revokePointsOnce("ROUTINE", "routine:$routineId", dateKey)
    }
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

/** 캘린더 일정 완료 전환 직후 호출(setCalendarTaskStatus) — completed=true면 적립, false면 회수. */
suspend fun PhoneLockRepository.onCalendarTaskCompletionChanged(taskId: Long, dateKey: String, completed: Boolean) {
    if (completed) {
        awardPointsOnce(CALENDAR_COMPLETE_POINTS, "CALENDAR", "calendar:$taskId", dateKey)
    } else {
        revokePointsOnce("CALENDAR", "calendar:$taskId", dateKey)
    }
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
