package com.phonelock.desktop.monitor

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 공부앱(별도 웹앱, Firebase Realtime Database 사용)이 뽀모도로 휴식 시작/종료 시점에 올리는
 * `users/{user}/pomodoro` 상태를 읽기 전용으로 폴링한다. 관리앱은 이 값을 쓰지 않고 읽기만 하므로,
 * 설정이 비어있거나 네트워크/파싱 오류가 나도 절대 예외를 던지지 않고 항상 "휴식 아님"(false)으로
 * 안전하게(fail-safe) 처리한다 — 이 값으로 그룹 잠금을 실제로 해제하기 때문에, 불확실할 땐 해제하지
 * 않는 쪽이 안전하다.
 *
 * phaseEndAt(공부앱이 올린 "이 휴식이 끝나는 시각")이 지나면 breakActive가 true로 남아있어도 무효로
 * 간주한다 — 공부앱 브라우저 탭이 갑자기 닫히는 등으로 breakActive:false를 못 올렸을 때도 관리앱 쪽에서
 * 영구 해제 상태로 남지 않도록 하는 안전장치.
 *
 * **로그인 필수(2단계 이후)**: 모든 함수가 [AuthManager] 로그인 여부로 동작한다 — 로그인
 * 돼 있으면 그 계정의 uid를 경로(`users/{uid}/...`)로 쓰고 그 파일이 관리하는(자동 갱신되는) Firebase
 * ID 토큰을 그대로 쓴다. 로그인이 안 돼 있으면 다른 판정 함수들과 같은 fail-safe 원칙으로 조용히
 * 아무것도 하지 않는다(동기화 없음) — 예전엔 로그인 없이도 익명 인증 + 설정에 입력한 사용자 텍스트로
 * 동작했지만, 이 앱은 여러 사용자가 각자 쓰는 걸 전제하므로 기기마다 텍스트가 어긋나 동기화가 안 맞는
 * 문제의 근본 원인이라 로그인 필수로 전환하며 그 경로(사용자 텍스트 파라미터 자체)를 완전히
 * 제거했다(사용자 확인).
 */
object PomodoroSyncClient {
    private const val GRACE_MS = 15_000L
    private const val CACHE_TTL_MS = 5_000L
    private const val TIMEOUT_SECONDS = 3L

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build()

    private data class StatusCache(
        val breakActive: Boolean, val phaseEndAt: Long, val timerActive: Boolean, val mode: String,
        val phaseStartedAt: Long, val taskName: String, val remoteUpdatedAt: Long, val fetchedAtMillis: Long
    )
    private var statusCache: StatusCache? = null

    /** 공부앱의 뽀모도로 휴식이 지금 유효하게 진행 중인지. 설정이 비어있거나 어떤 오류가 나도 false를 반환한다. */
    fun isBreakActive(databaseUrl: String?, apiKey: String?): Boolean {
        return refreshStatusCache(databaseUrl, apiKey)?.breakActive ?: false
    }

    /** 지금 휴식이 유효하다면 그 휴식이 끝나는 시각(epoch millis), 아니면 0. isBreakActive()와 같은
     *  5초 캐시를 공유하므로 오버레이 표시용으로 남은 시간을 구할 때 추가 네트워크 호출이 생기지 않는다. */
    fun currentPhaseEndAt(databaseUrl: String?, apiKey: String?): Long {
        if (!isBreakActive(databaseUrl, apiKey)) return 0L
        return statusCache?.phaseEndAt ?: 0L
    }

    /**
     * 공부앱 타이머가 지금 "공부" 페이즈로 진행 중인지(일반 모드는 항상 공부로 취급, 뽀모도로 휴식
     * 페이즈에선 false). 이 값이 true인 동안 관리앱은 전체화면 공부 잠금을 걸고
     * [readAllowedDesktopApps]에 등록된 프로그램만 예외로 허용한다. 다른 판정과 마찬가지로 오류/설정
     * 누락 시 항상 false(잠그지 않음)로 fail-safe.
     */
    fun isStudyTimerActive(databaseUrl: String?, apiKey: String?): Boolean {
        return refreshStatusCache(databaseUrl, apiKey)?.timerActive ?: false
    }

    /** 공부앱 타이머가 뽀모도로 모드인지("plain"이면 휴식 개념이 없어 전환 버튼을 보여줄 필요가 없음). */
    fun isPomodoroMode(databaseUrl: String?, apiKey: String?): Boolean {
        return refreshStatusCache(databaseUrl, apiKey)?.mode == "pomodoro"
    }

    /** 다른 기기가 이 페이즈를 시작한 시각(epoch millis) — 공부 페이즈 경과시간을 로컬에서 계산할 때 씀. 값 없으면 0. */
    fun remotePhaseStartedAt(databaseUrl: String?, apiKey: String?): Long {
        return refreshStatusCache(databaseUrl, apiKey)?.phaseStartedAt ?: 0L
    }

    /** 다른 기기가 지금 재고 있는 업무 이름(캘린더 일정 이름). 값 없으면 빈 문자열. */
    fun remoteTaskName(databaseUrl: String?, apiKey: String?): String {
        return refreshStatusCache(databaseUrl, apiKey)?.taskName ?: ""
    }

    /** 다른 기기가 이 상태를 마지막으로 write한 시각(epoch millis). 값 없으면 0 — 화면에서 "너무 오래된
     *  신호"(예: 그 기기가 정지 없이 앱을 꺼서 갱신이 끊긴 경우)를 걸러낼 때 쓴다. */
    fun remoteUpdatedAtMillis(databaseUrl: String?, apiKey: String?): Long {
        return refreshStatusCache(databaseUrl, apiKey)?.remoteUpdatedAt ?: 0L
    }

    private fun refreshStatusCache(databaseUrl: String?, apiKey: String?): StatusCache? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        val now = System.currentTimeMillis()
        val cached = statusCache
        if (cached != null && now - cached.fetchedAtMillis < CACHE_TTL_MS) return cached

        var phaseEndAt = 0L
        var timerActive = false
        var mode = "plain"
        var phaseStartedAt = 0L
        var taskName = ""
        var remoteUpdatedAt = 0L
        val breakActive = runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching false
            val status = fetchStatus(databaseUrl, user, token) ?: return@runCatching false
            val active = status.optBoolean("breakActive", false)
            phaseEndAt = status.optLong("phaseEndAt", 0L)
            timerActive = status.optBoolean("timerActive", false)
            mode = status.optString("mode", "plain")
            phaseStartedAt = status.optLong("phaseStartedAt", 0L)
            taskName = status.optString("taskName", "")
            remoteUpdatedAt = status.optLong("_ts", 0L)
            active && now < phaseEndAt + GRACE_MS
        }.getOrDefault(false)

        val result = StatusCache(breakActive, phaseEndAt, timerActive, mode, phaseStartedAt, taskName, remoteUpdatedAt, now)
        statusCache = result
        return result
    }

    /**
     * 1단계 네이티브 타이머 재구현 이후: 이 기기의 로컬 타이머 상태가 바뀔 때(페이즈 전환 시점에만,
     * 매초 아님)마다 `users/{user}/pomodoro`에 write해서 다른 기기(모바일↔데스크탑)에도 신호를 준다.
     * 이 기기 자신의 공부 잠금 판정은 더 이상 이 값을 읽지 않고 로컬 Repository를 직접 읽는다 —
     * DECISIONS.md "공부앱을 웹/웹뷰가 아닌 완전 네이티브로 재구현" 참고. 실패해도 로컬 상태는 이미
     * 저장된 뒤이므로 조용히 무시한다.
     */
    fun pushLocalStudyStatus(
        databaseUrl: String?, apiKey: String?,
        timerActive: Boolean, breakActive: Boolean, phaseEndAt: Long, mode: String,
        phaseStartedAt: Long = 0L, taskName: String = ""
    ) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching
            val base = databaseUrl.trimEnd('/')
            val body = JSONObject().apply {
                put("timerActive", timerActive)
                put("breakActive", breakActive)
                put("phaseEndAt", phaseEndAt)
                put("mode", mode)
                put("phaseStartedAt", phaseStartedAt)
                put("taskName", taskName)
                put("_ts", System.currentTimeMillis())
            }
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/pomodoro.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    data class ConfirmSyncEntry(val level: Int, val lastConfirmedAtEpochMillis: Long)

    /** Firebase 키 제약(`.`,`#`,`$`,`[`,`]`,`/` 금지) 위반 문자를 치환한 뒤 URL 인코딩한다. */
    private fun firebaseSafeKey(groupName: String): String {
        val sanitized = groupName.map { c -> if (c in ".#$[]/") '_' else c }.joinToString("")
        return URLEncoder.encode(sanitized, "UTF-8")
    }

    /** 실행확인 레벨을 읽는다. 설정이 비어있거나 오류가 나면 null(동기화 값 없음과 동일하게 취급). */
    fun readConfirmSync(databaseUrl: String?, apiKey: String?, groupName: String): ConfirmSyncEntry? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching null
            val base = databaseUrl.trimEnd('/')
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/confirmSync/${firebaseSafeKey(groupName)}.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            val body = response.body()
            if (body.isNullOrBlank() || body == "null") return@runCatching null
            val json = JSONObject(body)
            ConfirmSyncEntry(
                level = json.optInt("level", 0),
                lastConfirmedAtEpochMillis = json.optLong("lastConfirmedAtEpochMillis", 0)
            )
        }.getOrNull()
    }

    /** 실행확인 레벨을 올린다(그룹 하나만 갱신, 실패해도 조용히 무시 — 로컬 값은 이미 저장된 뒤이므로 안전). */
    fun writeConfirmSync(databaseUrl: String?, apiKey: String?, groupName: String, level: Int, lastConfirmedAtEpochMillis: Long) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching
            val base = databaseUrl.trimEnd('/')
            val body = JSONObject().apply {
                put("level", level)
                put("lastConfirmedAtEpochMillis", lastConfirmedAtEpochMillis)
            }
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/confirmSync/${firebaseSafeKey(groupName)}.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    data class SnoozeSyncEntry(val untilEpochMillis: Long, val usedDate: String, val usedCount: Int)

    /** 스누즈(#1) 상태를 읽는다. 설정이 비어있거나 오류가 나면 null(동기화 값 없음과 동일하게 취급). */
    fun readSnoozeSync(databaseUrl: String?, apiKey: String?, groupName: String): SnoozeSyncEntry? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching null
            val base = databaseUrl.trimEnd('/')
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/snoozeSync/${firebaseSafeKey(groupName)}.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            val body = response.body()
            if (body.isNullOrBlank() || body == "null") return@runCatching null
            val json = JSONObject(body)
            SnoozeSyncEntry(
                untilEpochMillis = json.optLong("untilEpochMillis", 0L),
                usedDate = json.optString("usedDate", ""),
                usedCount = json.optInt("usedCount", 0)
            )
        }.getOrNull()
    }

    /** 스누즈 상태를 올린다(그룹 하나만 갱신, 실패해도 조용히 무시 — 로컬 값은 이미 저장된 뒤이므로 안전). */
    fun writeSnoozeSync(databaseUrl: String?, apiKey: String?, groupName: String, untilEpochMillis: Long, usedDate: String, usedCount: Int) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching
            val base = databaseUrl.trimEnd('/')
            val body = JSONObject().apply {
                put("untilEpochMillis", untilEpochMillis)
                put("usedDate", usedDate)
                put("usedCount", usedCount)
            }
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/snoozeSync/${firebaseSafeKey(groupName)}.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    /**
     * 오늘 이 그룹의 기기별 사용시간(초)을 전부 읽는다 — device(`"android"`/`"desktop"`) -> 그 기기가
     * 마지막으로 올린 오늘 누적 사용시간. 기기마다 자기 값만 쓰고(경쟁 없음), 읽는 쪽이 "내 기기를 뺀
     * 나머지 기기들의 합"을 로컬 값에 더해서 일일 한도를 기기 합산 기준으로 판정한다.
     */
    fun readDailyUsage(databaseUrl: String?, apiKey: String?, date: String, groupName: String): Map<String, Int>? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching null
            val base = databaseUrl.trimEnd('/')
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/dailyUsage/$date/${firebaseSafeKey(groupName)}.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            val body = response.body()
            if (body.isNullOrBlank() || body == "null") return@runCatching null
            val json = JSONObject(body)
            json.keySet().associateWith { json.optInt(it, 0) }
        }.getOrNull()
    }

    /** 이 기기의 오늘 사용시간(초, 그날 누적 총합)을 올린다. 실패해도 조용히 무시(로컬 값은 이미 저장됨). */
    fun writeDailyUsage(databaseUrl: String?, apiKey: String?, date: String, groupName: String, device: String, usedSeconds: Int) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching
            val base = databaseUrl.trimEnd('/')
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/dailyUsage/$date/${firebaseSafeKey(groupName)}/$device.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .PUT(HttpRequest.BodyPublishers.ofString(usedSeconds.toString()))
                .build()
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    /** 이 기기가 그날(dateKey) 기록한 공부 기록 전체를 덮어쓴다 — dailyUsage와 같은 기기별 키 패턴이라
     *  경쟁이 없다(각 기기가 자기 키에만 쓴다). */
    fun writeStudyLogForDate(databaseUrl: String?, apiKey: String?, dateKey: String, device: String, entries: org.json.JSONArray) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching
            val base = databaseUrl.trimEnd('/')
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/studyLog/$dateKey/$device.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .PUT(HttpRequest.BodyPublishers.ofString(entries.toString()))
                .build()
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    /** 그날(dateKey) 전체 기기별 공부 기록(device -> 배열)을 읽는다. 설정 누락/오류 시 null. */
    fun readStudyLogForDate(databaseUrl: String?, apiKey: String?, dateKey: String): JSONObject? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching null
            val base = databaseUrl.trimEnd('/')
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/studyLog/$dateKey.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            val body = response.body()
            if (body.isNullOrBlank() || body == "null") return@runCatching null
            JSONObject(body)
        }.getOrNull()
    }

    data class CalendarSyncResult(val tasksJson: JSONObject, val ts: Long)

    /**
     * 네이티브 캘린더(2단계) 전체 문서를 읽는다. 웹앱과 완전히 같은 경로/스키마(`users/{user}/calendar`,
     * `{tasks:{dateKey:[...]}, _ts}`)를 써서 기존 웹앱 데이터를 그대로 이어받는다. 설정 누락/오류 시 null.
     */
    fun readCalendarTasks(databaseUrl: String?, apiKey: String?): CalendarSyncResult? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching null
            val base = databaseUrl.trimEnd('/')
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/calendar.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            val body = response.body()
            // body == "null"은 네트워크 오류가 아니라 "원격 문서가 아직 없다"는 확정 응답 — routines와
            // 동일한 이유로 null 대신 빈 결과(ts=0)를 반환해야 첫 동기화 때 로컬→원격 push가 일어난다.
            if (body.isNullOrBlank() || body == "null") return@runCatching CalendarSyncResult(JSONObject(), 0L)
            val json = JSONObject(body)
            CalendarSyncResult(json.optJSONObject("tasks") ?: JSONObject(), json.optLong("_ts", 0L))
        }.getOrNull()
    }

    /** 캘린더 전체 문서를 덮어쓴다(문서 단위 LWW — 호출부가 이미 로컬이 더 최신임을 확인한 뒤 호출). */
    fun writeCalendarTasks(databaseUrl: String?, apiKey: String?, tasksJson: JSONObject, ts: Long) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching
            val base = databaseUrl.trimEnd('/')
            val body = JSONObject().apply {
                put("tasks", tasksJson)
                put("_ts", ts)
            }
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/calendar.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    data class SettingsSyncResult(val json: JSONObject, val ts: Long)

    /**
     * 85차(사용자 요청, 안드로이드판과 대칭) — 설정 탭의 계산기 기본 다회독값(defaultPassCount/
     * defaultPassIntervalsCsv/defaultMultiPassEnabled)과 일일 초기화 시각(dailyResetHour)이 기기 간
     * 동기화되지 않던 문제를 고치기 위해 신설. `users/{user}/settings`에 문서 단위 LWW로 저장한다.
     */
    fun readSettings(databaseUrl: String?, apiKey: String?): SettingsSyncResult? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching null
            val base = databaseUrl.trimEnd('/')
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/settings.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            val body = response.body()
            if (body.isNullOrBlank() || body == "null") return@runCatching SettingsSyncResult(JSONObject(), 0L)
            val json = JSONObject(body)
            SettingsSyncResult(json, json.optLong("_ts", 0L))
        }.getOrNull()
    }

    fun writeSettings(databaseUrl: String?, apiKey: String?, settingsJson: JSONObject, ts: Long) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching
            val base = databaseUrl.trimEnd('/')
            val body = JSONObject(settingsJson.toString()).apply { put("_ts", ts) }
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/settings.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    data class RoutineSyncResult(val routinesJson: org.json.JSONArray, val logsJson: org.json.JSONArray, val ts: Long)

    /**
     * 루틴앱(51차 Firebase 동기화 추가) 전체 문서를 읽는다. `users/{user}/routines`에
     * `{routines:[...], routineLogs:[...], _ts}` — 캘린더와 같은 문서 단위 LWW. 기기별 로컬 id를 그대로
     * 실어보내면 다른 기기의 id 체계와 충돌하므로, routineLogs의 각 항목은 실제 routineId 대신
     * routines 배열 안에서의 인덱스(routineIndex)로 소속 루틴을 가리킨다(호출부가 반입 시 그 인덱스의
     * 새로 배정된 로컬 id로 다시 연결). 설정 누락/오류 시 null.
     */
    fun readRoutines(databaseUrl: String?, apiKey: String?): RoutineSyncResult? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching null
            val base = databaseUrl.trimEnd('/')
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/routines.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            val body = response.body()
            // body == "null"은 네트워크 오류가 아니라 "이 uid엔 아직 원격 문서가 없다"는 확정 응답이다 —
            // 새 계정의 첫 동기화처럼 원격이 진짜 비어있는 경우, 여기서 null을 반환하면 호출부가 오류와
            // 구분 못 해 로컬 데이터를 원격에 올리지도 못하고 그냥 포기해버린다(첫 동기화 무한 실패 버그).
            if (body.isNullOrBlank() || body == "null") return@runCatching RoutineSyncResult(org.json.JSONArray(), org.json.JSONArray(), 0L)
            val json = JSONObject(body)
            RoutineSyncResult(
                json.optJSONArray("routines") ?: org.json.JSONArray(),
                json.optJSONArray("routineLogs") ?: org.json.JSONArray(),
                json.optLong("_ts", 0L)
            )
        }.getOrNull()
    }

    /** 루틴 전체 문서를 덮어쓴다(문서 단위 LWW — 호출부가 이미 로컬이 더 최신임을 확인한 뒤 호출). */
    fun writeRoutines(databaseUrl: String?, apiKey: String?, routinesJson: org.json.JSONArray, logsJson: org.json.JSONArray, ts: Long) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching
            val base = databaseUrl.trimEnd('/')
            val body = JSONObject().apply {
                put("routines", routinesJson)
                put("routineLogs", logsJson)
                put("_ts", ts)
            }
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/routines.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    data class CalculatorSyncResult(
        val tasksJson: org.json.JSONArray, val tasksTs: Long,
        val savedJson: org.json.JSONArray, val savedTs: Long,
        val folderPathsJson: org.json.JSONArray, val folderTs: Long,
        val folderOrderJson: JSONObject, val folderOrderTs: Long
    )

    /**
     * 네이티브 계산기(3단계) 전체 문서를 읽는다. 웹앱과 완전히 같은 경로/스키마(`users/{user}/calculator`,
     * `{tasks,tasksTs,saved,savedTs,savedFolderTree,savedFolderTreeTs,savedFolderOrder,savedFolderOrderTs}`)를
     * 쓴다. 다만 네이티브 쪽 로컬 폴더 트리 표현은 평평한 경로 리스트(calcFolderPaths)라서, 웹앱의 중첩
     * savedFolderTree 객체를 읽을 때 이 함수가 경로 리스트로 펼쳐서 반환한다(호출부 Repository 참고).
     */
    fun readCalculator(databaseUrl: String?, apiKey: String?): CalculatorSyncResult? {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return null
        return runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching null
            val base = databaseUrl.trimEnd('/')
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/calculator.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            val body = response.body()
            // body == "null"은 네트워크 오류가 아니라 "원격 문서가 아직 없다"는 확정 응답 — routines/
            // calendar와 동일한 이유로 null 대신 빈 결과(모든 ts=0)를 반환해야 첫 동기화 push가 일어난다.
            if (body.isNullOrBlank() || body == "null") {
                return@runCatching CalculatorSyncResult(
                    tasksJson = org.json.JSONArray(), tasksTs = 0L,
                    savedJson = org.json.JSONArray(), savedTs = 0L,
                    folderPathsJson = org.json.JSONArray(), folderTs = 0L,
                    folderOrderJson = JSONObject(), folderOrderTs = 0L
                )
            }
            val json = JSONObject(body)
            val folderPaths = org.json.JSONArray()
            flattenFolderTree(json.optJSONObject("savedFolderTree"), mutableListOf(), folderPaths)
            CalculatorSyncResult(
                tasksJson = json.optJSONArray("tasks") ?: org.json.JSONArray(),
                tasksTs = json.optLong("tasksTs", 0L),
                savedJson = json.optJSONArray("saved") ?: org.json.JSONArray(),
                savedTs = json.optLong("savedTs", 0L),
                folderPathsJson = folderPaths,
                folderTs = json.optLong("savedFolderTreeTs", 0L),
                folderOrderJson = json.optJSONObject("savedFolderOrder") ?: JSONObject(),
                folderOrderTs = json.optLong("savedFolderOrderTs", 0L)
            )
        }.getOrNull()
    }

    private fun flattenFolderTree(node: JSONObject?, prefix: MutableList<String>, out: org.json.JSONArray) {
        if (node == null) return
        node.keys().forEach { name ->
            prefix.add(name)
            out.put(org.json.JSONArray(prefix))
            flattenFolderTree(node.optJSONObject(name), prefix, out)
            prefix.removeAt(prefix.size - 1)
        }
    }

    /** 경로 리스트(calcFolderPaths)를 웹앱과 같은 중첩 savedFolderTree 객체로 다시 접는다. */
    private fun buildFolderTree(paths: List<List<String>>): JSONObject {
        val root = JSONObject()
        paths.forEach { path ->
            var node = root
            path.forEach { name ->
                val next = node.optJSONObject(name) ?: JSONObject().also { node.put(name, it) }
                node = next
            }
        }
        return root
    }

    /**
     * draft 업무 + 저장됨 목록을 함께 PATCH(update)로 반영 — PUT을 쓰면 folderTree 등 이 함수가 모르는
     * 다른 키까지 지워지므로 반드시 부분 갱신(HTTP PATCH)이어야 한다(웹앱의 `.update()`와 동일한 이유).
     */
    fun writeCalcTasksAndSaved(
        databaseUrl: String?, apiKey: String?,
        tasksJson: org.json.JSONArray, tasksTs: Long,
        savedJson: org.json.JSONArray, savedTs: Long
    ) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching
            val base = databaseUrl.trimEnd('/')
            val body = JSONObject().apply {
                put("tasks", tasksJson); put("tasksTs", tasksTs)
                put("saved", savedJson); put("savedTs", savedTs)
            }
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/calculator.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    /** 폴더 트리 + 순서만 별도 PATCH — 아이템(tasks/saved)과 완전히 분리(웹앱 pushFolderTreeToFirebase와 동일). */
    fun writeCalcFolders(
        databaseUrl: String?, apiKey: String?,
        folderPaths: List<List<String>>, folderTs: Long,
        folderOrder: Map<String, List<String>>, folderOrderTs: Long
    ) {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return
        runCatching {
            val (token, user) = resolveIdentity(apiKey) ?: return@runCatching
            val base = databaseUrl.trimEnd('/')
            val orderJson = JSONObject()
            folderOrder.forEach { (key, order) -> orderJson.put(key, org.json.JSONArray(order)) }
            val body = JSONObject().apply {
                put("savedFolderTree", buildFolderTree(folderPaths)); put("savedFolderTreeTs", folderTs)
                put("savedFolderOrder", orderJson); put("savedFolderOrderTs", folderOrderTs)
            }
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$base/users/$user/calculator.json?auth=$token"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    /**
     * (idToken, uid)를 반환한다. 로그인이 필수다 — [AuthManager]로 로그인이 안 돼 있으면
     * null(동기화 안 함)을 반환한다. 예전엔 로그인 없이도 익명 인증 + 설정에 입력한 사용자 텍스트로
     * 동작했지만, 여러 기기가 서로 다른 텍스트를 입력해 동기화가 안 맞는 문제의 근본 원인이라 로그인
     * 기능이 자리잡은 뒤로는 그 경로를 완전히 제거했다(사용자 확인) — 여러 사용자가 각자 쓰는 앱이라
     * 기기가 스스로 아이디를 대신 정해줄 이유가 없다.
     */
    private fun resolveIdentity(apiKey: String): Pair<String, String>? {
        val uid = AuthManager.currentUid ?: return null
        val token = AuthManager.ensureIdToken(apiKey)
        if (token.isNullOrBlank()) return null
        return token to uid
    }

    private fun fetchStatus(databaseUrl: String, user: String, idToken: String): JSONObject? = runCatching {
        val base = databaseUrl.trimEnd('/')
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/users/$user/pomodoro.json?auth=$idToken"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        val body = response.body()
        if (body.isNullOrBlank() || body == "null") return null
        JSONObject(body)
    }.getOrNull()
}
