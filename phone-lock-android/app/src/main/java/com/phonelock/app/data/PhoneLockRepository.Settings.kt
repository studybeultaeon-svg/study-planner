package com.phonelock.app.data

import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 85차(사용자 요청) — 설정 탭의 계산기 기본 다회독값(defaultPassCount/defaultPassIntervalsCsv/
 * defaultMultiPassEnabled)과 관리앱 탭의 일일 사용 한도 초기화 시각(dailyResetHour)이 기기 간
 * 동기화되지 않던 문제를 고치기 위해 신설. 캘린더/루틴과 같은 문서 단위 LWW 패턴
 * (users/{user}/settings, _ts) — 값을 바꾸는 즉시 push하고, 앱 시작 시 한 번 pull한다.
 */
var PhoneLockRepository.settingsTs: Long
    get() = preferences.settingsTs
    set(value) { preferences.settingsTs = value }

fun PhoneLockRepository.pushSettingsToFirebase() {
    val ts = System.currentTimeMillis()
    settingsTs = ts
    val json = JSONObject().apply {
        put("dailyResetHour", preferences.dailyResetHour)
        put("defaultMultiPassEnabled", preferences.defaultMultiPassEnabled)
        put("defaultPassCount", preferences.defaultPassCount)
        put("defaultPassIntervalsCsv", preferences.defaultPassIntervalsCsv)
        // 129차: 수정·삭제 방지도 동기화한다 — 한 기기에서만 방지를 꺼두고 거기서 약화시킨 규칙을
        // 다른 기기로 밀어넣는 우회가 가능해지기 때문(그룹 설정은 syncEnabled면 기기 간 공유됨).
        put("editProtectionEnabled", preferences.editProtectionEnabled)
        put("editProtectionStartHour", preferences.editProtectionStartHour)
        put("editProtectionEndHour", preferences.editProtectionEndHour)
    }
    ioScope.launch {
        com.phonelock.app.service.PomodoroSyncClient.writeSettings(fbDatabaseUrl, fbApiKey, json, ts)
    }
}

/** 설정 화면 진입 시 호출 — 원격이 로컬보다 최신이면 로컬에 반영하고, 로컬이 더 최신이면 반대로 원격에 푸시한다. */
suspend fun PhoneLockRepository.syncSettingsFromFirebase() {
    val result = com.phonelock.app.service.PomodoroSyncClient.readSettings(fbDatabaseUrl, fbApiKey) ?: return
    if (result.ts > settingsTs) {
        val json = result.json
        if (json.has("dailyResetHour")) preferences.dailyResetHour = json.optInt("dailyResetHour", preferences.dailyResetHour)
        if (json.has("defaultMultiPassEnabled")) preferences.defaultMultiPassEnabled = json.optBoolean("defaultMultiPassEnabled", preferences.defaultMultiPassEnabled)
        if (json.has("defaultPassCount")) preferences.defaultPassCount = json.optInt("defaultPassCount", preferences.defaultPassCount)
        if (json.has("defaultPassIntervalsCsv")) preferences.defaultPassIntervalsCsv = json.optString("defaultPassIntervalsCsv", preferences.defaultPassIntervalsCsv)
        if (json.has("editProtectionEnabled")) preferences.editProtectionEnabled = json.optBoolean("editProtectionEnabled", preferences.editProtectionEnabled)
        if (json.has("editProtectionStartHour")) preferences.editProtectionStartHour = json.optInt("editProtectionStartHour", preferences.editProtectionStartHour)
        if (json.has("editProtectionEndHour")) preferences.editProtectionEndHour = json.optInt("editProtectionEndHour", preferences.editProtectionEndHour)
        settingsTs = result.ts
    } else if (settingsTs > result.ts) {
        pushSettingsToFirebase()
    }
}
