package com.phonelock.desktop.monitor

import com.phonelock.desktop.BuildInfo
import com.phonelock.shared.auth.AuthPolicy
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * 다중 로그인의 DB 쪽(140차, 안드로이드 `AccountSecurityClient`와 대칭) — `loginIds`(아이디 → 로그인 이메일)와
 * `authMeta/{uid}`(로그인된 기기·보안 기록·"다른 기기 모두 로그아웃" 시각·구글 연결 기록). 규칙은
 * `phone-lock-android/firebase-database.rules.json`, 모델은 [AuthPolicy].
 *
 * 전부 블로킹 호출이다 — UI 스레드(EDT)에서 부르지 말 것(`Thread { }`나 `Dispatchers.IO`에서).
 * 이 기기 전용 상태(설치 ID·로그인 실패 횟수·인증 대기 이메일·본 세션 목록)는 `data.json`과 섞지 않으려고
 * 같은 폴더의 `auth_state.json`에 따로 둔다.
 */
object AccountSecurityClient {
    private const val TIMEOUT_SECONDS = 8L
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build()

    class DeniedException : Exception("서버가 요청을 거절했습니다. 앱을 최신으로 업데이트했는데도 계속되면 관리자에게 알려 주세요.")

    data class Session(
        val id: String,
        val platform: String,
        val deviceName: String,
        val method: String?,
        val authTimeSec: Long,
        val lastSeenMillis: Long,
        val appVersion: String?
    )

    data class Event(
        val id: String,
        val type: AuthPolicy.SecurityEvent?,
        val rawType: String,
        val atMillis: Long,
        val method: String?,
        val platform: String?,
        val deviceName: String?
    )

    // ------------------------------------------------------------ 이 기기 전용 상태(auth_state.json)

    private val stateFile = File(File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PhoneLockDesktop"), "auth_state.json")
    private val stateLock = Any()

    /** 파일은 처음 한 번만 읽고 이후엔 메모리 사본을 쓴다(이 프로세스만 쓰는 파일) — 화면 그리는 중에 읽어도 디스크를 건드리지 않게. */
    private var stateCache: JSONObject? = null

    private fun readState(): JSONObject = synchronized(stateLock) {
        stateCache ?: runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }.also { stateCache = it }
    }

    private fun updateState(block: (JSONObject) -> Unit) {
        synchronized(stateLock) {
            val json = readState()
            block(json)
            runCatching {
                stateFile.parentFile?.mkdirs()
                stateFile.writeText(json.toString())
            }
        }
    }

    /** 이 설치본의 고정 식별자 — "로그인된 기기" 목록의 키(기기 정보가 아닌 무작위 값). */
    val installId: String
        get() = readState().optString("installId").takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { id -> updateState { it.put("installId", id) } }

    var loginFailureCount: Int
        get() = readState().optInt("loginFailureCount", 0)
        set(value) = updateState { it.put("loginFailureCount", value) }
    var loginFailureAtMillis: Long
        get() = readState().optLong("loginFailureAtMillis", 0L)
        set(value) = updateState { it.put("loginFailureAtMillis", value) }

    /** 인증을 기다리는 새 이메일(아이디 없는 계정용)과 인증·재설정 메일을 마지막으로 보낸 시각. */
    var pendingEmailChange: String?
        get() = readState().optString("pendingEmailChange").takeIf { it.isNotBlank() }
        set(value) = updateState { if (value == null) it.remove("pendingEmailChange") else it.put("pendingEmailChange", value) }
    var emailMailSentAtMillis: Long
        get() = readState().optLong("emailMailSentAtMillis", 0L)
        set(value) = updateState { it.put("emailMailSentAtMillis", value) }

    private var knownSessionIds: Set<String>
        get() = readState().optString("knownSessionIds").split(',').filter { it.isNotBlank() }.toSet()
        set(value) = updateState { it.put("knownSessionIds", value.joinToString(",")) }

    // ------------------------------------------------------------ loginIds

    /** 로그인 전(인증 없이) 아이디 하나를 조회한다. 항목이 없거나 규칙이 아직 없으면 `success(null)`, 통신 실패만 failure. */
    fun lookupLoginId(databaseUrl: String?, key: String): Result<AuthPolicy.LoginIdRecord?> {
        if (databaseUrl.isNullOrBlank()) return Result.success(null)
        return runCatching {
            val (code, body) = request("GET", "${databaseUrl.trimEnd('/')}/loginIds/${enc(key)}.json")
            when {
                code == 401 || code == 403 -> null
                code !in 200..299 -> error("HTTP $code")
                body.isNullOrBlank() || body == "null" -> null
                else -> parseRecord(JSONObject(body))
            }
        }
    }

    fun writeLoginId(databaseUrl: String?, apiKey: String?, key: String, record: AuthPolicy.LoginIdRecord): Result<Unit> =
        authed(databaseUrl, apiKey) { base, token, _ ->
            val body = JSONObject().apply {
                put("uid", record.uid)
                put("id", record.id)
                put("e", record.email)
                record.pendingEmail?.let { put("pe", it) }
                if (record.retired) put("r", true)
            }
            expectOk(request("PUT", "$base/loginIds/${enc(key)}.json?auth=$token", body.toString()))
        }

    fun deleteLoginId(databaseUrl: String?, apiKey: String?, key: String): Result<Unit> =
        authed(databaseUrl, apiKey) { base, token, _ -> expectOk(request("DELETE", "$base/loginIds/${enc(key)}.json?auth=$token")) }

    /** `loginIds/{아이디}`를 지금 로그인 이메일에 맞춘다(없으면 만들고, 이메일 인증을 마쳤으면 새 이메일로 올린다). */
    fun healLoginId(databaseUrl: String?, apiKey: String?, id: String): Result<AuthPolicy.LoginIdRecord?> {
        val uid = AuthManager.currentUid ?: return Result.failure(IllegalStateException("로그인이 필요합니다."))
        val email = (AuthManager.accountInfo?.email ?: AuthManager.currentEmail)?.takeIf { it.isNotBlank() } ?: return Result.success(null)
        val key = AuthPolicy.loginIdKey(id)
        val existing = lookupLoginId(databaseUrl, key).getOrElse { return Result.failure(it) }
        val healed = AuthPolicy.healedRecord(existing, uid, id, email) ?: return Result.success(existing?.takeIf { it.uid == uid })
        return writeLoginId(databaseUrl, apiKey, key, healed).map { healed }
    }

    // ------------------------------------------------------------ authMeta

    fun readRevokedBeforeSec(databaseUrl: String?, apiKey: String?): Result<Long?> =
        authed(databaseUrl, apiKey) { base, token, uid ->
            val (code, body) = request("GET", "$base/authMeta/$uid/revokedBefore.json?auth=$token")
            if (code == 401 || code == 403) return@authed null
            if (code !in 200..299) error("HTTP $code")
            body?.trim()?.takeIf { it != "null" }?.toDoubleOrNull()?.toLong()
        }

    /** 이 세션이 "다른 기기 모두 로그아웃"으로 끊겼는가 — 확인하지 못하면 null(로그아웃시키지 않는다). */
    fun isSessionRevoked(databaseUrl: String?, apiKey: String?): Boolean? {
        val key = apiKey ?: return null
        val revokedBefore = readRevokedBeforeSec(databaseUrl, apiKey).getOrElse { return null } ?: return false
        val authTime = AuthManager.authTimeSec(key) ?: return null
        return AuthPolicy.isSessionRevoked(authTime, revokedBefore)
    }

    /** 이 기기를 뺀 모든 로그인 세션을 끊는다(규칙이 5분 안의 재인증을 요구). */
    fun revokeOtherSessions(databaseUrl: String?, apiKey: String?): Result<Unit> {
        val key = apiKey ?: return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        val authTime = AuthManager.authTimeSec(key, forceRefresh = true)
            ?: return Result.failure(IllegalStateException("로그인 정보를 확인하지 못했습니다."))
        return authed(databaseUrl, apiKey) { base, token, uid ->
            expectOk(request("PUT", "$base/authMeta/$uid/revokedBefore.json?auth=$token", authTime.toString()))
        }
    }

    fun readSessions(databaseUrl: String?, apiKey: String?): Result<List<Session>> =
        authed(databaseUrl, apiKey) { base, token, uid ->
            val (code, body) = request("GET", "$base/authMeta/$uid/sessions.json?auth=$token")
            if (code == 401 || code == 403) throw DeniedException()
            if (code !in 200..299) error("HTTP $code")
            val json = body?.takeIf { it.isNotBlank() && it != "null" }?.let { JSONObject(it) } ?: return@authed emptyList()
            json.keySet().mapNotNull { id ->
                val o = json.optJSONObject(id) ?: return@mapNotNull null
                Session(
                    id = id,
                    platform = o.optString("p"),
                    deviceName = o.optString("n"),
                    method = o.optString("m").takeIf { it.isNotBlank() },
                    authTimeSec = o.optLong("at"),
                    lastSeenMillis = o.optLong("seen"),
                    appVersion = o.optString("v").takeIf { it.isNotBlank() }
                )
            }.sortedByDescending { it.lastSeenMillis }
        }

    fun readRecentEvents(databaseUrl: String?, apiKey: String?, limit: Int = 30): Result<List<Event>> =
        authed(databaseUrl, apiKey) { base, token, uid ->
            val (code, body) = request("GET", "$base/authMeta/$uid/events.json?auth=$token&orderBy=${enc("\"at\"")}&limitToLast=$limit")
            if (code == 401 || code == 403) throw DeniedException()
            if (code !in 200..299) error("HTTP $code")
            val json = body?.takeIf { it.isNotBlank() && it != "null" }?.let { JSONObject(it) } ?: return@authed emptyList()
            json.keySet().mapNotNull { id ->
                val o = json.optJSONObject(id) ?: return@mapNotNull null
                val raw = o.optString("t")
                Event(
                    id = id,
                    type = AuthPolicy.SecurityEvent.fromCode(raw),
                    rawType = raw,
                    atMillis = o.optLong("at"),
                    method = o.optString("m").takeIf { it.isNotBlank() },
                    platform = o.optString("p").takeIf { it.isNotBlank() },
                    deviceName = o.optString("n").takeIf { it.isNotBlank() }
                )
            }.sortedByDescending { it.atMillis }
        }

    /** 보안 기록 한 줄(서버 시각). 실패해도 흐름을 막지 않는다. */
    fun appendEvent(databaseUrl: String?, apiKey: String?, type: AuthPolicy.SecurityEvent, method: String? = null) {
        authed(databaseUrl, apiKey) { base, token, uid ->
            val body = JSONObject().apply {
                put("t", type.code)
                put("at", JSONObject().put(".sv", "timestamp"))
                method?.let { put("m", it) }
                put("p", PLATFORM)
                put("n", deviceName())
            }
            expectOk(request("POST", "$base/authMeta/$uid/events.json?auth=$token", body.toString()))
        }
    }

    fun upsertSession(databaseUrl: String?, apiKey: String?, method: String?): Result<Unit> =
        authed(databaseUrl, apiKey) { base, token, uid ->
            // 규칙이 at === auth.token.auth_time을 요구하므로, 요청에 쓰는 바로 그 토큰의 로그인 시각을 넣는다.
            val authTime = GoogleDesktopOAuth.decodeJwtPayload(token)?.optLong("auth_time") ?: error("no auth_time")
            val body = JSONObject().apply {
                put("p", PLATFORM)
                put("n", deviceName())
                method?.let { put("m", it) }
                put("at", authTime)
                put("seen", JSONObject().put(".sv", "timestamp"))
                put("v", BuildInfo.BUILD_TIMESTAMP.toString())
            }
            expectOk(request("PUT", "$base/authMeta/$uid/sessions/${enc(installId)}.json?auth=$token", body.toString()))
        }

    fun removeSession(databaseUrl: String?, apiKey: String?, sessionId: String): Result<Unit> =
        authed(databaseUrl, apiKey) { base, token, uid -> expectOk(request("DELETE", "$base/authMeta/$uid/sessions/${enc(sessionId)}.json?auth=$token")) }

    fun readGoogleIdentitySub(databaseUrl: String?, apiKey: String?): Result<String?> =
        authed(databaseUrl, apiKey) { base, token, uid ->
            val (code, body) = request("GET", "$base/authMeta/$uid/identities/google/sub.json?auth=$token")
            if (code == 401 || code == 403) throw DeniedException()
            if (code !in 200..299) error("HTTP $code")
            body?.trim()?.takeIf { it.isNotBlank() && it != "null" }?.trim('"')
        }

    /** 구글 연결 기록 — 규칙이 15분 안의 로그인/재인증을 요구한다. 이것 없이는 구글을 연결하지 않는다. */
    fun setGoogleIdentity(databaseUrl: String?, apiKey: String?, sub: String, email: String?): Result<Unit> =
        authed(databaseUrl, apiKey) { base, token, uid ->
            val body = JSONObject().apply {
                put("sub", sub)
                email?.let { put("email", it) }
                put("at", JSONObject().put(".sv", "timestamp"))
            }
            expectOk(request("PUT", "$base/authMeta/$uid/identities/google.json?auth=$token", body.toString()))
        }.recoverCatching { e ->
            throw if (e is DeniedException) Exception("구글 연결 기록을 남기지 못해 연결하지 않았습니다. 본인 확인을 다시 한 뒤 시도해 주세요.") else e
        }

    fun clearGoogleIdentity(databaseUrl: String?, apiKey: String?): Result<Unit> =
        authed(databaseUrl, apiKey) { base, token, uid -> expectOk(request("DELETE", "$base/authMeta/$uid/identities/google.json?auth=$token")) }

    /** 계정 삭제 — `authMeta/{uid}` 통째(규칙이 5분 안의 재인증을 요구). */
    fun deleteAllMeta(databaseUrl: String?, apiKey: String?): Result<Unit> =
        authed(databaseUrl, apiKey) { base, token, uid -> expectOk(request("DELETE", "$base/authMeta/$uid.json?auth=$token")) }

    /** 90일 지난 보안 기록 정리. */
    fun pruneOldEvents(databaseUrl: String?, apiKey: String?) {
        authed(databaseUrl, apiKey) { base, token, uid ->
            val cutoff = System.currentTimeMillis() - AuthPolicy.EVENT_RETENTION_MS - 60 * 60 * 1000L
            val (code, body) = request("GET", "$base/authMeta/$uid/events.json?auth=$token&orderBy=${enc("\"at\"")}&endAt=$cutoff&limitToFirst=50")
            if (code !in 200..299 || body.isNullOrBlank() || body == "null") return@authed
            JSONObject(body).keySet().forEach { id -> request("DELETE", "$base/authMeta/$uid/events/${enc(id)}.json?auth=$token") }
        }
    }

    // ------------------------------------------------------------ 로그인 직후 처리

    /** 이 계정의 아이디 — 프로필 customId(게스트 제외), 없으면 가짜 로그인 이메일에서. */
    fun resolveMyId(databaseUrl: String?, apiKey: String?): String? {
        if (!AuthManager.isSignedIn || AuthManager.isAnonymous) return null
        val fromProfile = AccountSyncClient.fetchMyProfile(databaseUrl, apiKey).getOrNull()
            ?.optString("customId")?.takeIf { it.isNotBlank() && AuthPolicy.ID_PATTERN.matches(it) }
        return fromProfile ?: AuthPolicy.idFromSyntheticEmail(AuthManager.accountInfo?.email ?: AuthManager.currentEmail)
    }

    /**
     * 로그인에 성공한 직후(아이디·이메일·구글 모두) — `loginIds`를 로그인 이메일에 맞추고, 이 기기를 "로그인된 기기"에
     * 올리고, 보안 기록을 남긴다. 전부 최선 노력이다.
     */
    fun afterSignIn(databaseUrl: String?, apiKey: String?, method: String, id: String? = null) {
        AuthManager.markFreshlyAuthenticated()
        val myId = id ?: resolveMyId(databaseUrl, apiKey)
        val before = myId?.let { lookupLoginId(databaseUrl, AuthPolicy.loginIdKey(it)).getOrNull() }
        if (myId != null) {
            healLoginId(databaseUrl, apiKey, myId).onSuccess { healed ->
                if (before?.pendingEmail != null && healed?.pendingEmail == null && healed?.email == before.pendingEmail) {
                    appendEvent(databaseUrl, apiKey, AuthPolicy.SecurityEvent.EMAIL_CHANGED, method)
                }
            }
        }
        pendingEmailChange?.let { pending ->
            if ((AuthManager.accountInfo?.email ?: AuthManager.currentEmail).equals(pending, ignoreCase = true)) {
                pendingEmailChange = null
                if (myId == null) appendEvent(databaseUrl, apiKey, AuthPolicy.SecurityEvent.EMAIL_CHANGED, method)
            }
        }
        loginFailureCount = 0
        upsertSession(databaseUrl, apiKey, method)
        knownSessionIds = knownSessionIds + installId
        appendEvent(databaseUrl, apiKey, AuthPolicy.SecurityEvent.SIGN_IN, method)
    }

    /** 새 기기 로그인 감지 — 이 기기가 처음 보는 세션을 돌려준다(첫 실행 때는 전부 "본 것"으로 기록만). */
    fun detectNewDevices(databaseUrl: String?, apiKey: String?): List<Session> {
        upsertSession(databaseUrl, apiKey, method = null)
        val sessions = readSessions(databaseUrl, apiKey).getOrElse { return emptyList() }
        val known = knownSessionIds
        val firstRun = known.isEmpty()
        val fresh = sessions.filter { it.id != installId && it.id !in known }
        knownSessionIds = known + sessions.map { it.id } + installId
        return if (firstRun) emptyList() else fresh
    }

    // ------------------------------------------------------------ 내부

    const val PLATFORM = "desktop"

    fun deviceName(): String =
        (System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() } ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "PC").take(80)

    private fun parseRecord(o: JSONObject): AuthPolicy.LoginIdRecord? {
        val uid = o.optString("uid").takeIf { it.isNotBlank() } ?: return null
        val email = o.optString("e").takeIf { it.isNotBlank() } ?: return null
        return AuthPolicy.LoginIdRecord(
            uid = uid,
            id = o.optString("id"),
            email = email,
            pendingEmail = o.optString("pe").takeIf { it.isNotBlank() },
            retired = o.optBoolean("r", false)
        )
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun expectOk(response: Pair<Int, String?>) {
        val code = response.first
        if (code == 401 || code == 403) throw DeniedException()
        if (code !in 200..299) error("HTTP $code")
    }

    private fun <T> authed(databaseUrl: String?, apiKey: String?, block: (base: String, token: String, uid: String) -> T): Result<T> {
        if (databaseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        return runCatching {
            val uid = AuthManager.currentUid ?: error("로그인이 필요합니다.")
            val token = AuthManager.ensureIdToken(apiKey) ?: error("로그인이 필요합니다.")
            block(databaseUrl.trimEnd('/'), token, uid)
        }
    }

    private fun request(method: String, url: String, body: String? = null): Pair<Int, String?> {
        val builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, publisher)
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }
}
