package com.phonelock.desktop.data

import com.phonelock.shared.GrowthSystem
import org.json.JSONArray
import org.json.JSONObject

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
    pushGrowthToFirebase()
}

/** [awardGrowthExp]의 반대(안드로이드판과 대칭) — 루틴/캘린더 완료가 취소돼 포인트를 회수할 때 그때
 *  같이 얹혔던 EXP도 걷어낸다(125차 후속 버그: 포인트 원장만 되돌리고 EXP는 그대로 둬서 완료/미완료를
 *  반복하는 것만으로 EXP를 무한히 불릴 수 있었다). 아직 "적용" 전이면 대기 EXP에서 빼고, 이미 적용돼
 *  누적으로 넘어간 뒤라면 모자란 만큼 누적에서 마저 뺀다 — 대기에서만 빼면 "적립 → 적용 → 취소"를
 *  반복해 그대로 무한 복사가 되기 때문이다. 어느 쪽도 0 밑으로는 내려가지 않는다(환생/시즌 초기화로
 *  이미 0이 된 뒤에 들어온 취소는 뺄 것이 없으므로 그냥 흡수된다).
 *  호출부(Repository.Points.kt)가 이미 lock을 쥐고 있어야 한다. */
internal fun Repository.revokeGrowthExp(rawAmount: Double) {
    if (rawAmount <= 0.0) return
    var remaining = rawAmount * GrowthSystem.expMultiplier(data.rebirthCount)
    val fromPending = remaining.coerceAtMost(data.growthExpPending).coerceAtLeast(0.0)
    data.growthExpPending -= fromPending
    remaining -= fromPending
    if (remaining > 0.0) data.growthExpTotal = (data.growthExpTotal - remaining).coerceAtLeast(0.0)
    persist()
    pushGrowthToFirebase()
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
    data.lifetimeMaxLevel = maxOf(data.lifetimeMaxLevel, GrowthSystem.levelForExp(after))
    persist()
    pushGrowthToFirebase()
    GrowthSystem.ApplyResult(before, after, levelBefore, GrowthSystem.levelForExp(after))
}

/** 환생 — 현재 레벨이 다음 환생에 필요한 레벨 이상이면 누적/대기 EXP를 전부 초기화하고 이번 시즌 환생
 *  횟수를 올린다(EXP 배율 상승, LEVEL_CAP 도달 전까지 유효). 조건 미달이면 아무 일도 안 하고 false. */
fun Repository.rebirth(): Boolean = synchronized(lock) {
    val level = GrowthSystem.levelForExp(data.growthExpTotal)
    if (!GrowthSystem.canRebirth(level, data.rebirthCount)) return@synchronized false
    data.growthExpTotal = 0.0
    data.growthExpPending = 0.0
    data.rebirthCount += 1
    persist()
    pushGrowthToFirebase()
    true
}

fun Repository.getLifetimeMaxLevel(): Int = synchronized(lock) { data.lifetimeMaxLevel }

fun Repository.getLifetimeRebirthCount(): Int = synchronized(lock) { data.lifetimeRebirthCount }

/** 연간 시즌 초기화(109차 후속, "500레벨+연간 성장 시스템") — 매년 1월 1일(dailyResetHour 기준 "오늘")이
 *  지나면 이번 시즌의 성장 기록(누적/대기 EXP, 이번 시즌 환생 횟수)만 초기화한다. 계정/설정/포인트 등
 *  다른 데이터는 손대지 않는다 — "무엇을 초기화할지"는 이 3개 필드로 명확히 한정된다. 초기화 직전 값은
 *  버리지 않고 영구 기록([AppData.lifetimeMaxLevel]/[lifetimeRebirthCount])에 누적해서 남긴다. 최초
 *  실행(growthSeasonYear=0)이면 지울 게 없으므로 연도만 기록하고 끝낸다. 하루 1회 그룹 자동 재활성화
 *  (`applyDailyGroupResetIfNeeded`)와 같은 tick에서 호출되는 걸 전제로, 이미 올해 처리됐으면 아무 일도
 *  안 한다(가벼운 가드라 매 tick 호출해도 무방). */
fun Repository.checkAndResetGrowthSeasonIfNeeded() = synchronized(lock) {
    val currentYear = effectiveDate(data.dailyResetHour).year
    if (data.growthSeasonYear == currentYear) return@synchronized
    if (data.growthSeasonYear != 0) {
        val level = GrowthSystem.levelForExp(data.growthExpTotal)
        data.lifetimeMaxLevel = maxOf(data.lifetimeMaxLevel, level)
        data.lifetimeRebirthCount += data.rebirthCount
        data.growthExpTotal = 0.0
        data.growthExpPending = 0.0
        data.rebirthCount = 0
    }
    data.growthSeasonYear = currentYear
    persist()
    pushGrowthToFirebase()
}

// ════════════════════════════════════════════════════
// Firebase 동기화(121차, 안드로이드판과 대칭) — 포인트/루틴/캘린더와 동일한 "전체 문서 단위 LWW"
// (users/{user}/growth). 116차까지 성장 EXP와 장식은 기기 로컬 전용이었는데, 그 EXP는 정작 동기화되는
// 포인트 적립 이벤트에서 파생되기 때문에 기기를 오가면 "포인트는 맞는데 나무만 Lv.1"이 돼 홈 화면과 실제
// 데이터가 어긋난다(사용자 지적). 적립의 원천과 같은 방식을 써야 둘이 항상 같은 시점을 가리킨다([[DECISIONS.md]] 121차).
// ════════════════════════════════════════════════════

/** 지금 성장 상태 전체를 한 문서로 묶는다 — 호출부가 이미 lock을 쥐고 있어야 한다. */
private fun Repository.growthStateToJson(): JSONObject = JSONObject().apply {
    put("expTotal", data.growthExpTotal)
    put("expPending", data.growthExpPending)
    put("rebirthCount", data.rebirthCount)
    put("seasonYear", data.growthSeasonYear)
    put("lifetimeMaxLevel", data.lifetimeMaxLevel)
    put("lifetimeRebirthCount", data.lifetimeRebirthCount)
    put("ownedDecorations", JSONArray(data.ownedDecorationIds.toList()))
    put("equippedDecorations", JSONArray(data.equippedDecorationIds.toList()))
}

/**
 * 성장 값이 바뀔 때마다 fire-and-forget으로 올린다(pushPointsToFirebase와 같은 패턴).
 *
 * 문서 단위 LWW의 유일한 위험은 "아직 원격을 한 번도 안 읽은 기기가 빈 값으로 덮어쓰는 것"이다 —
 * 예를 들어 재설치 직후의 기기에서 루틴 하나를 체크하면 EXP 0.x짜리 문서가 올라가 다른 기기의 레벨을
 * 통째로 날릴 수 있다. 그래서 ① 앱 시작과 홈 탭 진입 때 [syncGrowthFromFirebase]로 먼저 받아오고,
 * ② 아직 한 번도 동기화한 적 없고(growthTs == 0) 로컬에 쌓인 성장도 전혀 없는 상태에서는 아예 올리지
 * 않는다(올려봐야 남에게 줄 정보가 없고, 덮어쓰기 위험만 있다).
 *
 *  호출부는 이미 lock을 쥐고 있어야 한다.
 */
internal fun Repository.pushGrowthToFirebase() {
    val neverSynced = data.growthTs == 0L
    val nothingToShare = data.growthExpTotal <= 0.0 && data.growthExpPending <= 0.0 &&
        data.rebirthCount == 0 && data.ownedDecorationIds.isEmpty()
    if (neverSynced && nothingToShare) return
    val ts = System.currentTimeMillis()
    data.growthTs = ts
    val json = growthStateToJson()
    val url = data.fbDatabaseUrl; val key = data.fbApiKey
    Thread {
        com.phonelock.desktop.monitor.PomodoroSyncClient.writeGrowth(url, key, json, ts)
    }.start()
}

/**
 * 홈(식물) 화면 진입 시 호출 — 원격이 더 최신이면 로컬을 덮어쓰고, 로컬이 더 최신이면 원격에 푸시한다.
 * 네트워크 호출을 포함하므로 호출부(UI)에서 백그라운드 스레드로 실행할 것. 로컬이 바뀌었으면 true.
 */
fun Repository.syncGrowthFromFirebase(): Boolean {
    val (url, key) = synchronized(lock) { data.fbDatabaseUrl to data.fbApiKey }
    val result = com.phonelock.desktop.monitor.PomodoroSyncClient.readGrowth(url, key) ?: return false
    return synchronized(lock) {
        if (result.ts > data.growthTs) {
            val json = result.json
            data.growthExpTotal = json.optDouble("expTotal", data.growthExpTotal)
            data.growthExpPending = json.optDouble("expPending", data.growthExpPending)
            data.rebirthCount = json.optInt("rebirthCount", data.rebirthCount)
            data.growthSeasonYear = json.optInt("seasonYear", data.growthSeasonYear)
            data.lifetimeMaxLevel = maxOf(data.lifetimeMaxLevel, json.optInt("lifetimeMaxLevel", 0))
            data.lifetimeRebirthCount = maxOf(data.lifetimeRebirthCount, json.optInt("lifetimeRebirthCount", 0))
            json.optJSONArray("ownedDecorations")?.let { arr ->
                data.ownedDecorationIds.clear()
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { data.ownedDecorationIds.add(it) }
            }
            json.optJSONArray("equippedDecorations")?.let { arr ->
                data.equippedDecorationIds.clear()
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { data.equippedDecorationIds.add(it) }
            }
            data.growthTs = result.ts
            persist()
            true
        } else {
            if (data.growthTs > result.ts) pushGrowthToFirebase()
            false
        }
    }
}
