package com.phonelock.app.data

import com.phonelock.shared.GrowthSystem
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

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
    pushGrowthToFirebase()
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
    preferences.lifetimeMaxLevel = maxOf(preferences.lifetimeMaxLevel, GrowthSystem.levelForExp(after))
    pushGrowthToFirebase()
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
    pushGrowthToFirebase()
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
    pushGrowthToFirebase()
}

// ════════════════════════════════════════════════════
// Firebase 동기화(121차) — 포인트/루틴/캘린더와 동일한 "전체 문서 단위 LWW"(users/{user}/growth).
//
// 116차까지 성장 EXP와 장식은 기기 로컬 전용이었다. 그런데 그 EXP는 동기화되는 포인트 적립 이벤트에서
// 파생되기 때문에, 다른 기기에서 공부/루틴을 하거나 앱을 재설치하면 "포인트는 살아돌아왔는데 나무만 Lv.1"이 돼
// 홈 화면과 실제 데이터가 서로 어긋난다(사용자 지적). 적립의 원천(포인트 원장)과 같은 동기화 방식을 써야
// 둘이 서로 언제나 같은 시점을 가리키므로, 여기도 포인트와 똑같은 문서 단위 LWW를 쓴다([[DECISIONS.md]] 121차).
// ════════════════════════════════════════════════════

/** 지금 성장 상태 전체를 한 문서로 묶는다(업로드용 + 테스트 가능하게 분리). */
internal fun PhoneLockRepository.growthStateToJson(): JSONObject = JSONObject().apply {
    put("expTotal", preferences.growthExpTotal)
    put("expPending", preferences.growthExpPending)
    put("rebirthCount", preferences.rebirthCount)
    put("seasonYear", preferences.growthSeasonYear)
    put("lifetimeMaxLevel", preferences.lifetimeMaxLevel)
    put("lifetimeRebirthCount", preferences.lifetimeRebirthCount)
    put("ownedDecorations", JSONArray(ownedDecorationIds.toList()))
    put("equippedDecorations", JSONArray(equippedDecorationIds))
}

/**
 * 성장 값이 바뀔 때마다 fire-and-forget으로 올린다(pushPointsToFirebase와 같은 패턴).
 *
 * 문서 단위 LWW의 유일한 위험은 "아직 원격을 한 번도 안 읽은 기기가 빈 값으로 덮어쓰는 것"이다 —
 * 예를 들어 재설치 직후의 기기에서 루틴 하나를 체크하면 EXP 0.x짜리 문서가 올라가 다른 기기의 레벨을
 * 통째로 날릴 수 있다. 그래서 ① 앱 시작과 홈 탭 진입 때 [syncGrowthFromFirebase]로 먼저 받아오고,
 * ② 아직 한 번도 동기화한 적 없고(growthTs == 0) 로컬에 쌓인 성장도 전혀 없는 상태에서는 아예 올리지
 * 않는다(올려봐야 남에게 줄 정보가 없고, 덮어쓰기 위험만 있다).
 */
internal fun PhoneLockRepository.pushGrowthToFirebase() {
    val neverSynced = preferences.growthTs == 0L
    val nothingToShare = preferences.growthExpTotal <= 0.0 && preferences.growthExpPending <= 0.0 &&
        preferences.rebirthCount == 0 && ownedDecorationIds.isEmpty()
    if (neverSynced && nothingToShare) return
    val ts = System.currentTimeMillis()
    preferences.growthTs = ts
    val json = growthStateToJson()
    ioScope.launch {
        com.phonelock.app.service.PomodoroSyncClient.writeGrowth(fbDatabaseUrl, fbApiKey, json, ts)
    }
}

/** 홈(식물) 탭 진입 시 호출 — 원격이 더 최신이면 로컬을 덮어쓰고, 로컬이 더 최신이면 원격에 푸시한다.
 *  로컬이 바뀌었으면 true를 돌려줘 화면이 그 자리에서 다시 그릴 수 있게 한다. */
suspend fun PhoneLockRepository.syncGrowthFromFirebase(): Boolean {
    val result = com.phonelock.app.service.PomodoroSyncClient.readGrowth(fbDatabaseUrl, fbApiKey) ?: return false
    if (result.ts > preferences.growthTs) {
        val json = result.json
        preferences.growthExpTotal = json.optDouble("expTotal", preferences.growthExpTotal)
        preferences.growthExpPending = json.optDouble("expPending", preferences.growthExpPending)
        preferences.rebirthCount = json.optInt("rebirthCount", preferences.rebirthCount)
        preferences.growthSeasonYear = json.optInt("seasonYear", preferences.growthSeasonYear)
        preferences.lifetimeMaxLevel = maxOf(preferences.lifetimeMaxLevel, json.optInt("lifetimeMaxLevel", 0))
        preferences.lifetimeRebirthCount = maxOf(preferences.lifetimeRebirthCount, json.optInt("lifetimeRebirthCount", 0))
        json.optJSONArray("ownedDecorations")?.let { arr ->
            preferences.ownedDecorationIdsCsv = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.joinToString(",")
        }
        json.optJSONArray("equippedDecorations")?.let { arr ->
            preferences.equippedDecorationIdsCsv = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.joinToString(",")
        }
        preferences.growthTs = result.ts
        return true
    }
    if (preferences.growthTs > result.ts) pushGrowthToFirebase()
    return false
}
