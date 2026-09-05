package com.phonelock.app.data

import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 그룹 설정 크로스디바이스 동기화(87차+ 세션, 사용자 요청, 데스크탑 Repository.GroupSync.kt와 대칭) —
 * 그룹 이름·설명·시간대/한도/실행확인·스누즈·기간지정 강화 등 "설정값"만 동기화하고, 아래는 절대
 * 이 채널에 안 실린다(사용자 명시 요구):
 * - 제어할 앱/사이트(GroupMember/GroupSite 별도 테이블) — 기기마다 다르게 두는 게 원래 목적.
 * - groupEnabled(그룹 전체 on/off) — "그룹 목록" 화면 스위치는 기기별로 따로 켜고 끌 수 있어야 함.
 * - groupOffPending/groupOffMessageIndex — 회유 절차 진행 중 여부, 그 자리에서만 의미 있는 임시 UI 상태.
 * - snoozedUntilEpochMillis/snoozeUsedDate/snoozeUsedCount — 이미 snoozeSync 채널이 별도로 기기 간
 *   최신값 병합을 하고 있어 여기서 같이 다루면 두 메커니즘이 서로 다르게 덮어쓸 위험이 있음.
 * - selfMessageText — 원래부터 "순수 로컬 텍스트, 동기화 안 함"으로 설계된 필드(Entities.kt 주석 참고).
 *
 * 루틴/캘린더와 같은 "전체 문서 단위 LWW"(users/{user}/groupSettings)이지만, 그룹은 기기마다 로컬
 * 전용인 앱/사이트 목록(GroupMember/GroupSite)을 물고 있어서 루틴처럼 delete+insert로 전체 대체하면
 * 안 된다 — 원격에 없는 이름의 로컬 그룹을 지웠다간 그 그룹의 앱/사이트 목록이 영영 사라진다. 그래서
 * 병합 방식이 다르다: 원격에 있는 이름은 로컬을 찾아 설정 필드만 갱신(없으면 앱/사이트 없이 새로 생성)
 * 하고, 원격에 없는 이름의 로컬 그룹은 그대로 둔다(삭제 전파 없음 — 그룹 삭제/앱-사이트 편집은 항상
 * 기기별 로컬 판단).
 */

private var PhoneLockRepository.groupSettingsTs: Long
    get() = preferences.groupSettingsTs
    set(value) { preferences.groupSettingsTs = value }

private fun AppGroup.toGroupSettingsJson(): JSONObject = JSONObject().apply {
    put("name", name)
    put("description", description)
    put("dailyLimitSeconds", dailyLimitSeconds ?: JSONObject.NULL)
    put("dailyLimitApplyStartMinute", dailyLimitApplyStartMinute ?: JSONObject.NULL)
    put("dailyLimitApplyEndMinute", dailyLimitApplyEndMinute ?: JSONObject.NULL)
    put("dailyLimitDaysMask", dailyLimitDaysMask)
    put("scheduleStartMinute", scheduleStartMinute ?: JSONObject.NULL)
    put("scheduleEndMinute", scheduleEndMinute ?: JSONObject.NULL)
    put("scheduleDaysMask", scheduleDaysMask)
    put("enabled", enabled)
    put("confirmEnabled", confirmEnabled)
    put("confirmApplyStartMinute", confirmApplyStartMinute ?: JSONObject.NULL)
    put("confirmApplyEndMinute", confirmApplyEndMinute ?: JSONObject.NULL)
    put("confirmDaysMask", confirmDaysMask)
    put("initialWaitSeconds", initialWaitSeconds)
    put("waitIncrementSeconds", waitIncrementSeconds)
    put("confirmCooldownSeconds", confirmCooldownSeconds)
    put("usageOverlayEnabled", usageOverlayEnabled)
    put("overlayLevelStepsToMax", overlayLevelStepsToMax)
    put("pomodoroUnlockEnabled", pomodoroUnlockEnabled)
    put("levelDecayEnabled", levelDecayEnabled)
    put("levelDecayIntervalSeconds", levelDecayIntervalSeconds)
    put("scheduleEnabled", scheduleEnabled)
    put("snoozeEnabled", snoozeEnabled)
    put("snoozeMinutes", snoozeMinutes)
    put("snoozeDailyLimit", snoozeDailyLimit)
    put("forceEnabledFrom", forceEnabledFrom ?: JSONObject.NULL)
    put("forceEnabledUntil", forceEnabledUntil ?: JSONObject.NULL)
    put("blockAttemptDate", blockAttemptDate)
    put("blockAttemptCount", blockAttemptCount)
}

/** 원격 JSON 한 그룹분을 [AppGroup]에 적용한다 — GroupMember/GroupSite·groupEnabled·groupOffPending류·
 *  스누즈 진행상태·selfMessageText는 건드리지 않고 나머지 설정 필드만 덮어쓴다. */
private fun AppGroup.applyGroupSettingsJson(json: JSONObject): AppGroup = copy(
    description = json.optString("description", description),
    dailyLimitSeconds = if (json.isNull("dailyLimitSeconds")) null else json.optInt("dailyLimitSeconds"),
    dailyLimitApplyStartMinute = if (json.isNull("dailyLimitApplyStartMinute")) null else json.optInt("dailyLimitApplyStartMinute"),
    dailyLimitApplyEndMinute = if (json.isNull("dailyLimitApplyEndMinute")) null else json.optInt("dailyLimitApplyEndMinute"),
    dailyLimitDaysMask = json.optInt("dailyLimitDaysMask", dailyLimitDaysMask),
    scheduleStartMinute = if (json.isNull("scheduleStartMinute")) null else json.optInt("scheduleStartMinute"),
    scheduleEndMinute = if (json.isNull("scheduleEndMinute")) null else json.optInt("scheduleEndMinute"),
    scheduleDaysMask = json.optInt("scheduleDaysMask", scheduleDaysMask),
    enabled = json.optBoolean("enabled", enabled),
    confirmEnabled = json.optBoolean("confirmEnabled", confirmEnabled),
    confirmApplyStartMinute = if (json.isNull("confirmApplyStartMinute")) null else json.optInt("confirmApplyStartMinute"),
    confirmApplyEndMinute = if (json.isNull("confirmApplyEndMinute")) null else json.optInt("confirmApplyEndMinute"),
    confirmDaysMask = json.optInt("confirmDaysMask", confirmDaysMask),
    initialWaitSeconds = json.optInt("initialWaitSeconds", initialWaitSeconds),
    waitIncrementSeconds = json.optInt("waitIncrementSeconds", waitIncrementSeconds),
    confirmCooldownSeconds = json.optInt("confirmCooldownSeconds", confirmCooldownSeconds),
    usageOverlayEnabled = json.optBoolean("usageOverlayEnabled", usageOverlayEnabled),
    overlayLevelStepsToMax = json.optInt("overlayLevelStepsToMax", overlayLevelStepsToMax),
    pomodoroUnlockEnabled = json.optBoolean("pomodoroUnlockEnabled", pomodoroUnlockEnabled),
    levelDecayEnabled = json.optBoolean("levelDecayEnabled", levelDecayEnabled),
    levelDecayIntervalSeconds = json.optInt("levelDecayIntervalSeconds", levelDecayIntervalSeconds),
    scheduleEnabled = json.optBoolean("scheduleEnabled", scheduleEnabled),
    snoozeEnabled = json.optBoolean("snoozeEnabled", snoozeEnabled),
    snoozeMinutes = json.optInt("snoozeMinutes", snoozeMinutes),
    snoozeDailyLimit = json.optInt("snoozeDailyLimit", snoozeDailyLimit),
    forceEnabledFrom = if (json.isNull("forceEnabledFrom")) null else json.optString("forceEnabledFrom", null),
    forceEnabledUntil = if (json.isNull("forceEnabledUntil")) null else json.optString("forceEnabledUntil", null),
    blockAttemptDate = json.optString("blockAttemptDate", blockAttemptDate),
    blockAttemptCount = json.optInt("blockAttemptCount", blockAttemptCount)
)

/** 새 이름의 원격 그룹을 로컬에 만들 때 쓰는 기본값 — 앱/사이트는 비워두고(기기별 로컬 입력을 기다림),
 *  groupEnabled는 기본 켜짐, 스누즈 진행상태/groupOffPending류는 초기값. */
private fun newGroupFromSettingsJson(json: JSONObject): AppGroup =
    AppGroup(name = json.optString("name", "")).applyGroupSettingsJson(json)

suspend fun PhoneLockRepository.groupSettingsToJson(): JSONObject {
    val root = JSONObject()
    groupDao.getAllOnce().forEach { g ->
        root.put(com.phonelock.app.service.PomodoroSyncClient.groupSettingsSafeKey(g.name), g.toGroupSettingsJson())
    }
    return root
}

/** 변경 직후 fire-and-forget으로 Firebase에 전체 그룹 설정 문서를 올린다. */
fun PhoneLockRepository.pushGroupSettingsToFirebase() {
    val ts = System.currentTimeMillis()
    groupSettingsTs = ts
    ioScope.launch {
        val json = groupSettingsToJson()
        com.phonelock.app.service.PomodoroSyncClient.writeGroupSettings(fbDatabaseUrl, fbApiKey, json, ts)
    }
}

/**
 * 그룹 화면 진입 시 호출 — 원격이 로컬보다 최신이면(문서 단위 LWW) 이름이 일치하는 로컬 그룹의 설정
 * 필드만 덮어쓰고(GroupMember/GroupSite·groupEnabled 등은 그대로), 이름이 없는 원격 그룹은 앱/사이트
 * 없이 새로 만든다. 로컬에만 있는 그룹(원격 문서에 이름이 없음)은 삭제하지 않는다. 로컬이 더 최신이면
 * 반대로 원격에 푸시한다.
 */
suspend fun PhoneLockRepository.syncGroupSettingsFromFirebase() {
    val result = com.phonelock.app.service.PomodoroSyncClient.readGroupSettings(fbDatabaseUrl, fbApiKey) ?: return
    if (result.ts > groupSettingsTs) {
        val local = groupDao.getAllOnce()
        val keys = result.groupsJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "_ts") continue
            val entry = result.groupsJson.optJSONObject(key) ?: continue
            val name = entry.optString("name", "")
            if (name.isBlank()) continue
            val existing = local.find { it.name == name }
            if (existing != null) {
                groupDao.update(existing.applyGroupSettingsJson(entry))
            } else {
                groupDao.insert(newGroupFromSettingsJson(entry))
            }
        }
        groupSettingsTs = result.ts
    } else if (groupSettingsTs > result.ts) {
        pushGroupSettingsToFirebase()
    }
}
