package com.phonelock.desktop.data

import org.json.JSONObject

/**
 * 85차(사용자 요청, 안드로이드판과 대칭) — 설정 탭의 계산기 기본 다회독값(defaultPassCount/
 * defaultPassIntervalsCsv/defaultMultiPassEnabled)과 일일 사용 한도 초기화 시각(dailyResetHour)이
 * 기기 간 동기화되지 않던 문제를 고치기 위해 신설. 캘린더와 같은 문서 단위 LWW 패턴
 * (users/{user}/settings, _ts) — 값을 바꾸는 즉시 push하고, 설정 화면 진입 시 한 번 pull한다.
 */
fun Repository.pushSettingsToFirebase() {
    val ts = System.currentTimeMillis()
    val json = synchronized(lock) {
        data.settingsTs = ts
        JSONObject().apply {
            put("dailyResetHour", data.dailyResetHour)
            put("defaultMultiPassEnabled", data.defaultMultiPassEnabled)
            put("defaultPassCount", data.defaultPassCount)
            put("defaultPassIntervalsCsv", data.defaultPassIntervalsCsv)
        }
    }
    persist()
    val url = data.fbDatabaseUrl; val key = data.fbApiKey
    Thread {
        com.phonelock.desktop.monitor.PomodoroSyncClient.writeSettings(url, key, json, ts)
    }.start()
}

/** 설정 화면 진입 시 호출 — 원격이 로컬보다 최신이면 로컬에 반영하고, 로컬이 더 최신이면 반대로 원격에 푸시한다. */
fun Repository.syncSettingsFromFirebase() {
    val (url, key) = synchronized(lock) { data.fbDatabaseUrl to data.fbApiKey }
    val result = com.phonelock.desktop.monitor.PomodoroSyncClient.readSettings(url, key) ?: return
    synchronized(lock) {
        if (result.ts > data.settingsTs) {
            val json = result.json
            if (json.has("dailyResetHour")) data.dailyResetHour = json.optInt("dailyResetHour", data.dailyResetHour)
            if (json.has("defaultMultiPassEnabled")) data.defaultMultiPassEnabled = json.optBoolean("defaultMultiPassEnabled", data.defaultMultiPassEnabled)
            if (json.has("defaultPassCount")) data.defaultPassCount = json.optInt("defaultPassCount", data.defaultPassCount)
            if (json.has("defaultPassIntervalsCsv")) data.defaultPassIntervalsCsv = json.optString("defaultPassIntervalsCsv", data.defaultPassIntervalsCsv)
            data.settingsTs = result.ts
            persist()
        } else if (data.settingsTs > result.ts) {
            pushSettingsToFirebase()
        }
    }
}
