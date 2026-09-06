package com.phonelock.desktop.data

import org.json.JSONObject

/**
 * 그룹 설정 크로스디바이스 동기화(87차+ 세션, 94차에 opt-in 방식으로 전면 개편, 안드로이드판과 대칭) —
 * 그룹 이름·설명·시간대/한도/실행확인·스누즈·기간지정 강화 등 "설정값"만 동기화 대상이고, 아래는 절대
 * 이 채널에 안 실린다(사용자 명시 요구):
 * - 제어할 앱/사이트(processNames/domains) — 기기마다 다르게 두는 게 원래 목적.
 * - groupEnabled(그룹 전체 on/off) — "그룹 목록" 화면 스위치는 기기별로 따로 켜고 끌 수 있어야 함.
 * - groupOffPending/groupOffMessageIndex — 회유 절차 진행 중 여부, 그 자리에서만 의미 있는 임시 UI 상태.
 * - snoozedUntilEpochMillis/snoozeUsedDate/snoozeUsedCount — 이미 snoozeSync 채널이 별도로 기기 간
 *   최신값 병합을 하고 있어 여기서 같이 다루면 두 메커니즘이 서로 다르게 덮어쓸 위험이 있음.
 * - selfMessageText — 원래부터 "순수 로컬 텍스트, 동기화 안 함"으로 설계된 필드(Models.kt 주석 참고).
 * - syncEnabled 자체 — 이 그룹을 동기화에 참여시킬지는 기기마다 따로 정하는 로컬 스위치라 동기화
 *   대상이 아니다(94차 신규).
 *
 * **94차 전면 개편**: 88차의 "그룹 화면에 들어가면 원격에 있는 모든 이름을 자동으로 로컬에 병합"하는
 * 방식이 사용자 의도와 안 맞아(원치 않는 규칙까지 저절로 생김) 그룹별 opt-in(syncEnabled) 방식으로
 * 바꿨다. syncEnabled=false인 그룹은 원격에 올라가지도, 원격 값으로 갱신되지도 않는다 — 완전히 로컬
 * 전용. 새로 원격 규칙을 로컬로 들여오는 건 "불러오기" 화면(GroupImportDialog)에서 사용자가 직접
 * 골라야 한다. 이름 충돌 시 확인 절차는 GroupEditScreen이 [findRemoteGroupSettingByName]으로 처리한다.
 */

private fun Group.toGroupSettingsJson(): JSONObject = JSONObject().apply {
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

/** 원격 JSON 한 그룹분을 [Group]에 적용한다 — apps/sites·groupEnabled·groupOffPending류·스누즈
 *  진행상태·selfMessageText·syncEnabled는 건드리지 않고 나머지 설정 필드만 덮어쓴다. */
fun Group.applyGroupSettingsJson(json: JSONObject): Group = copy(
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

/** "불러오기" 화면에 보여줄 원격 항목 한 건. */
data class ImportableGroupSetting(val name: String, val json: JSONObject)

fun Repository.groupSettingsToJson(): JSONObject = synchronized(lock) {
    val root = JSONObject()
    data.groups.filter { it.syncEnabled }.forEach { g ->
        root.put(com.phonelock.desktop.monitor.PomodoroSyncClient.groupSettingsSafeKey(g.name), g.toGroupSettingsJson())
    }
    root
}

/** 변경 직후 fire-and-forget으로 Firebase에 전체 그룹 설정 문서를 올린다(syncEnabled=true인 그룹만
 *  포함, 호출부는 이미 lock을 쥐고 있어도 안전 — 새 스레드에서 실행). */
fun Repository.pushGroupSettingsToFirebase() {
    val ts = System.currentTimeMillis()
    val json = synchronized(lock) {
        data.groupSettingsTs = ts
        groupSettingsToJson()
    }
    val url = synchronized(lock) { data.fbDatabaseUrl }
    val key = synchronized(lock) { data.fbApiKey }
    Thread {
        com.phonelock.desktop.monitor.PomodoroSyncClient.writeGroupSettings(url, key, json, ts)
    }.start()
}

/**
 * 그룹 화면 진입 시 호출 — **이미 syncEnabled=true인 로컬 그룹만** 원격 최신값으로 갱신한다(문서 단위
 * LWW). syncEnabled=false인 로컬 그룹은 전혀 건드리지 않고, 원격에만 있고 로컬에 없는 이름을 새로
 * 만드는 일도 없다(94차 — 그건 "불러오기" 화면에서 사용자가 명시적으로 골라야 한다). 로컬이 더
 * 최신이면 반대로 원격에 푸시한다. 네트워크 호출을 포함하므로 호출부(UI)에서 백그라운드 스레드/코루틴에서 실행할 것.
 */
fun Repository.syncGroupSettingsFromFirebase() {
    val (url, key) = synchronized(lock) { data.fbDatabaseUrl to data.fbApiKey }
    val result = com.phonelock.desktop.monitor.PomodoroSyncClient.readGroupSettings(url, key) ?: return
    synchronized(lock) {
        if (result.ts > data.groupSettingsTs) {
            data.groups.forEachIndexed { idx, g ->
                if (!g.syncEnabled) return@forEachIndexed
                val entry = result.groupsJson.optJSONObject(com.phonelock.desktop.monitor.PomodoroSyncClient.groupSettingsSafeKey(g.name)) ?: return@forEachIndexed
                data.groups[idx] = g.applyGroupSettingsJson(entry)
            }
            data.groupSettingsTs = result.ts
            persist()
        } else if (data.groupSettingsTs > result.ts) {
            pushGroupSettingsToFirebase()
        }
    }
}

/**
 * "불러오기" 화면용 — 원격 문서에 있는 항목 중, 이 기기에 아직 동기화로 연결되지 않은(=같은 이름의
 * 로컬 그룹이 없거나, 있어도 syncEnabled가 꺼져 있는) 것들만 골라 돌려준다.
 */
fun Repository.fetchImportableGroupSettings(): List<ImportableGroupSetting> {
    val (url, key) = synchronized(lock) { data.fbDatabaseUrl to data.fbApiKey }
    val result = com.phonelock.desktop.monitor.PomodoroSyncClient.readGroupSettings(url, key) ?: return emptyList()
    val locallySyncedNames = synchronized(lock) { data.groups.filter { it.syncEnabled }.map { it.name }.toSet() }
    val list = mutableListOf<ImportableGroupSetting>()
    val keys = result.groupsJson.keys()
    while (keys.hasNext()) {
        val key2 = keys.next()
        if (key2 == "_ts") continue
        val entry = result.groupsJson.optJSONObject(key2) ?: continue
        val name = entry.optString("name", "")
        if (name.isBlank() || name in locallySyncedNames) continue
        list.add(ImportableGroupSetting(name, entry))
    }
    return list
}

/** 선택한 원격 규칙을 로컬로 불러온다 — 같은 이름의 로컬 그룹이 있으면 설정만 덮어쓰고 syncEnabled를
 *  켠다(앱/사이트 목록은 그대로 유지), 없으면 앱/사이트 없이 새로 만든다. */
fun Repository.importGroupSetting(entry: JSONObject) {
    val name = entry.optString("name", "")
    if (name.isBlank()) return
    synchronized(lock) {
        val idx = data.groups.indexOfFirst { it.name == name }
        if (idx >= 0) {
            data.groups[idx] = data.groups[idx].applyGroupSettingsJson(entry).copy(syncEnabled = true)
        } else {
            val newId = data.nextGroupId
            data.nextGroupId += 1
            data.groups.add(Group(id = newId, name = name, syncEnabled = true).applyGroupSettingsJson(entry))
        }
        persist()
    }
}

/** 이름이 일치하는 원격 그룹 설정 항목을 찾는다 — 새 규칙 생성/동기화 토글 켜기 시 이름 충돌 확인용. */
fun Repository.findRemoteGroupSettingByName(name: String): JSONObject? {
    if (name.isBlank()) return null
    val (url, key) = synchronized(lock) { data.fbDatabaseUrl to data.fbApiKey }
    val result = com.phonelock.desktop.monitor.PomodoroSyncClient.readGroupSettings(url, key) ?: return null
    return result.groupsJson.optJSONObject(com.phonelock.desktop.monitor.PomodoroSyncClient.groupSettingsSafeKey(name))
        ?.takeIf { it.optString("name", "") == name }
}
