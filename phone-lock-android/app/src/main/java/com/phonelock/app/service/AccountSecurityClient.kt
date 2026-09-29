package com.phonelock.app.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.phonelock.app.BuildConfig
import com.phonelock.app.data.AppPreferences
import com.phonelock.shared.auth.AuthPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "AccountSecurity"

/**
 * 다중 로그인의 DB 쪽(140차) — `loginIds`(아이디 → 로그인 이메일)와 `authMeta/{uid}`(로그인된 기기·보안 기록·
 * "다른 기기 모두 로그아웃" 시각·구글 연결 기록). 규칙은 `firebase-database.rules.json`, 모델은 [AuthPolicy].
 *
 * 세션 무효화: `authMeta/{uid}/revokedBefore`(초)보다 먼저 로그인한 세션은 **DB 규칙이** 모든 읽기·쓰기를 막는다
 * (`auth.token.auth_time` 비교) — 그 기기의 앱이 협조하지 않아도 데이터에 접근할 수 없다. 앱은 [isSessionRevoked]로
 * 이를 알아채고 스스로 로그아웃한다.
 *
 * 규칙을 콘솔에 아직 게시하지 않았으면 이 노드들은 전부 거절된다 — 아이디 조회는 "항목 없음"으로 보고 옛 방식으로
 * 로그인을 이어 가고, 나머지 기록은 조용히 건너뛴다. 구글 연결·이메일 등록처럼 기록이 꼭 필요한 작업만 실패를 알린다.
 */
object AccountSecurityClient {
    private const val TIMEOUT_MS = 8_000

    /** 규칙이 거절했다(콘솔 규칙 미게시 또는 세션 무효화) — 네트워크 오류와 구분한다. */
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

    // ------------------------------------------------------------ loginIds

    /**
     * 로그인 전(인증 없이) 아이디 하나를 조회한다 — 규칙상 정확한 키 하나만 읽을 수 있고 목록은 읽을 수 없다.
     * 항목이 없거나 규칙이 아직 없으면 `success(null)`, 통신 실패만 failure.
     */
    suspend fun lookupLoginId(databaseUrl: String?, key: String): Result<AuthPolicy.LoginIdRecord?> {
        if (databaseUrl.isNullOrBlank()) return Result.success(null)
        return withContext(Dispatchers.IO) {
            runCatching {
                val (code, body) = request("GET", URL("${databaseUrl.trimEnd('/')}/loginIds/${enc(key)}.json"))
                when {
                    code == 401 || code == 403 -> null
                    code !in 200..299 -> error("HTTP $code")
                    body.isNullOrBlank() || body == "null" -> null
                    else -> parseRecord(JSONObject(body))
                }
            }
        }
    }

    suspend fun writeLoginId(databaseUrl: String?, apiKey: String?, key: String, record: AuthPolicy.LoginIdRecord): Result<Unit> =
        authed(databaseUrl) { base, token, _ ->
            val body = JSONObject().apply {
                put("uid", record.uid)
                put("id", record.id)
                put("e", record.email)
                record.pendingEmail?.let { put("pe", it) }
                if (record.retired) put("r", true)
            }
            expectOk(request("PUT", URL("$base/loginIds/${enc(key)}.json?auth=$token"), body.toString()))
        }

    suspend fun deleteLoginId(databaseUrl: String?, apiKey: String?, key: String): Result<Unit> =
        authed(databaseUrl) { base, token, _ -> expectOk(request("DELETE", URL("$base/loginIds/${enc(key)}.json?auth=$token"))) }

    /**
     * `loginIds/{아이디}`를 지금 로그인 이메일에 맞춘다(없으면 만들고, 이메일 인증을 마쳤으면 새 이메일로 올린다).
     * 아이디 로그인이 로그인 이메일을 찾는 곳이라, 이메일을 바꾸는 흐름은 이것이 성공해야 진행한다.
     */
    suspend fun healLoginId(databaseUrl: String?, apiKey: String?, id: String): Result<AuthPolicy.LoginIdRecord?> {
        val user = AuthManager.currentUser ?: return Result.failure(IllegalStateException("로그인이 필요합니다."))
        val email = user.email?.takeIf { it.isNotBlank() } ?: return Result.success(null)
        val key = AuthPolicy.loginIdKey(id)
        val existing = lookupLoginId(databaseUrl, key).getOrElse { return Result.failure(it) }
        val healed = AuthPolicy.healedRecord(existing, user.uid, id, email) ?: return Result.success(existing?.takeIf { it.uid == user.uid })
        return writeLoginId(databaseUrl, apiKey, key, healed).map { healed }
    }

    // ------------------------------------------------------------ authMeta

    suspend fun readRevokedBeforeSec(databaseUrl: String?, apiKey: String?): Result<Long?> =
        authed(databaseUrl) { base, token, uid ->
            val (code, body) = request("GET", URL("$base/authMeta/$uid/revokedBefore.json?auth=$token"))
            if (code == 401 || code == 403) return@authed null
            if (code !in 200..299) error("HTTP $code")
            body?.trim()?.takeIf { it != "null" }?.toDoubleOrNull()?.toLong()
        }

    /** 이 세션이 "다른 기기 모두 로그아웃"으로 끊겼는가 — 확인하지 못하면 null(로그아웃시키지 않는다). */
    suspend fun isSessionRevoked(databaseUrl: String?, apiKey: String?): Boolean? {
        val revokedBefore = readRevokedBeforeSec(databaseUrl, apiKey).getOrElse { return null } ?: return false
        val authTime = AuthManager.authTimeSec() ?: return null
        return AuthPolicy.isSessionRevoked(authTime, revokedBefore)
    }

    /**
     * 이 기기를 뺀 모든 로그인 세션을 끊는다 — 방금 재인증해 로그인 시각이 가장 최근인 이 기기만 남는다(규칙이
     * 5분 안의 재인증을 요구한다). 비밀번호 변경·구글 연결 해제 뒤에도 불러 즉시 끊는다.
     */
    suspend fun revokeOtherSessions(databaseUrl: String?, apiKey: String?): Result<Unit> {
        val authTime = AuthManager.authTimeSec(forceRefresh = true)
            ?: return Result.failure(IllegalStateException("로그인 정보를 확인하지 못했습니다."))
        return authed(databaseUrl) { base, token, uid ->
            expectOk(request("PUT", URL("$base/authMeta/$uid/revokedBefore.json?auth=$token"), authTime.toString()))
        }
    }

    suspend fun readSessions(databaseUrl: String?, apiKey: String?): Result<List<Session>> =
        authed(databaseUrl) { base, token, uid ->
            val (code, body) = request("GET", URL("$base/authMeta/$uid/sessions.json?auth=$token"))
            if (code == 401 || code == 403) throw DeniedException()
            if (code !in 200..299) error("HTTP $code")
            val json = body?.takeIf { it.isNotBlank() && it != "null" }?.let { JSONObject(it) } ?: return@authed emptyList()
            json.keys().asSequence().mapNotNull { id ->
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
            }.sortedByDescending { it.lastSeenMillis }.toList()
        }

    suspend fun readRecentEvents(databaseUrl: String?, apiKey: String?, limit: Int = 30): Result<List<Event>> =
        authed(databaseUrl) { base, token, uid ->
            val query = "orderBy=${enc("\"at\"")}&limitToLast=$limit"
            val (code, body) = request("GET", URL("$base/authMeta/$uid/events.json?auth=$token&$query"))
            if (code == 401 || code == 403) throw DeniedException()
            if (code !in 200..299) error("HTTP $code")
            val json = body?.takeIf { it.isNotBlank() && it != "null" }?.let { JSONObject(it) } ?: return@authed emptyList()
            json.keys().asSequence().mapNotNull { id ->
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
            }.sortedByDescending { it.atMillis }.toList()
        }

    /** 보안 기록 한 줄(서버 시각). 실패해도 흐름을 막지 않는다. */
    suspend fun appendEvent(databaseUrl: String?, apiKey: String?, type: AuthPolicy.SecurityEvent, method: String? = null) {
        authed(databaseUrl) { base, token, uid ->
            val body = JSONObject().apply {
                put("t", type.code)
                put("at", JSONObject().put(".sv", "timestamp"))
                method?.let { put("m", it) }
                put("p", PLATFORM)
                put("n", deviceName())
            }
            expectOk(request("POST", URL("$base/authMeta/$uid/events.json?auth=$token"), body.toString()))
        }.onFailure { Log.w(TAG, "event not recorded: ${type.code}") }
    }

    suspend fun upsertSession(context: Context, databaseUrl: String?, apiKey: String?, method: String?): Result<Unit> {
        val installId = AppPreferences(context).authInstallId
        return authed(databaseUrl) { base, token, uid ->
            // 규칙이 at === auth.token.auth_time을 요구하므로, 요청에 쓰는 바로 그 토큰의 로그인 시각을 넣는다.
            val authTime = AuthManager.currentUser?.getIdToken(false)?.await()?.authTimestamp ?: error("no token")
            val body = JSONObject().apply {
                put("p", PLATFORM)
                put("n", deviceName())
                method?.let { put("m", it) }
                put("at", authTime)
                put("seen", JSONObject().put(".sv", "timestamp"))
                put("v", BuildConfig.VERSION_CODE.toString())
            }
            expectOk(request("PUT", URL("$base/authMeta/$uid/sessions/${enc(installId)}.json?auth=$token"), body.toString()))
        }
    }

    suspend fun removeSession(databaseUrl: String?, apiKey: String?, sessionId: String): Result<Unit> =
        authed(databaseUrl) { base, token, uid ->
            expectOk(request("DELETE", URL("$base/authMeta/$uid/sessions/${enc(sessionId)}.json?auth=$token")))
        }

    suspend fun readGoogleIdentitySub(databaseUrl: String?, apiKey: String?): Result<String?> =
        authed(databaseUrl) { base, token, uid ->
            val (code, body) = request("GET", URL("$base/authMeta/$uid/identities/google/sub.json?auth=$token"))
            if (code == 401 || code == 403) throw DeniedException()
            if (code !in 200..299) error("HTTP $code")
            body?.trim()?.takeIf { it.isNotBlank() && it != "null" }?.trim('"')
        }

    /** 구글 연결 기록 — 규칙이 15분 안의 로그인/재인증을 요구한다. 이것 없이는 구글을 연결하지 않는다. */
    suspend fun setGoogleIdentity(databaseUrl: String?, apiKey: String?, sub: String, email: String?): Result<Unit> =
        authed(databaseUrl) { base, token, uid ->
            val body = JSONObject().apply {
                put("sub", sub)
                email?.let { put("email", it) }
                put("at", JSONObject().put(".sv", "timestamp"))
            }
            expectOk(request("PUT", URL("$base/authMeta/$uid/identities/google.json?auth=$token"), body.toString()))
        }.recoverCatching { e ->
            throw if (e is DeniedException) Exception("구글 연결 기록을 남기지 못해 연결하지 않았습니다. 본인 확인을 다시 한 뒤 시도해 주세요.") else e
        }

    suspend fun clearGoogleIdentity(databaseUrl: String?, apiKey: String?): Result<Unit> =
        authed(databaseUrl) { base, token, uid ->
            expectOk(request("DELETE", URL("$base/authMeta/$uid/identities/google.json?auth=$token")))
        }

    /** 계정 삭제 — `authMeta/{uid}` 통째(규칙이 5분 안의 재인증을 요구). */
    suspend fun deleteAllMeta(databaseUrl: String?, apiKey: String?): Result<Unit> =
        authed(databaseUrl) { base, token, uid -> expectOk(request("DELETE", URL("$base/authMeta/$uid.json?auth=$token"))) }

    /** 90일 지난 보안 기록 정리(규칙이 그보다 새 기록의 삭제는 막는다). */
    suspend fun pruneOldEvents(databaseUrl: String?, apiKey: String?) {
        authed(databaseUrl) { base, token, uid ->
            val cutoff = System.currentTimeMillis() - AuthPolicy.EVENT_RETENTION_MS - 60 * 60 * 1000L
            val query = "orderBy=${enc("\"at\"")}&endAt=$cutoff&limitToFirst=50"
            val (code, body) = request("GET", URL("$base/authMeta/$uid/events.json?auth=$token&$query"))
            if (code !in 200..299 || body.isNullOrBlank() || body == "null") return@authed
            JSONObject(body).keys().forEach { id ->
                request("DELETE", URL("$base/authMeta/$uid/events/${enc(id)}.json?auth=$token"))
            }
        }
    }

    // ------------------------------------------------------------ 로그인 직후 처리

    /**
     * 로그인에 성공한 직후(아이디·이메일·구글 모두) 부른다: `loginIds`를 로그인 이메일에 맞추고(이메일 인증을 마친
     * 경우 새 이메일로 올림), 이 기기를 "로그인된 기기"에 올리고, 보안 기록을 남긴다. 전부 최선 노력이다.
     *
     * @param id 이 계정의 아이디. null이면 프로필의 customId → 가짜 이메일 순으로 찾는다(구글로만 가입해 아직 아이디가
     *   없는 계정·게스트는 건너뛴다).
     */
    suspend fun afterSignIn(context: Context, databaseUrl: String?, apiKey: String?, method: String, id: String?) {
        AuthManager.markFreshlyAuthenticated()
        val myId = id ?: resolveMyId(databaseUrl, apiKey)
        val before = myId?.let { lookupLoginId(databaseUrl, AuthPolicy.loginIdKey(it)).getOrNull() }
        if (myId != null) {
            healLoginId(databaseUrl, apiKey, myId).onSuccess { healed ->
                // 인증을 기다리던 새 이메일이 로그인 이메일이 됐다 = 이메일 변경 완료.
                if (before?.pendingEmail != null && healed?.pendingEmail == null && healed?.email == before.pendingEmail) {
                    appendEvent(databaseUrl, apiKey, AuthPolicy.SecurityEvent.EMAIL_CHANGED, method)
                }
            }
        }
        val prefs = AppPreferences(context)
        prefs.pendingEmailChange?.let { pending ->
            if (AuthManager.currentUser?.email.equals(pending, ignoreCase = true)) {
                prefs.pendingEmailChange = null
                if (myId == null) appendEvent(databaseUrl, apiKey, AuthPolicy.SecurityEvent.EMAIL_CHANGED, method)
            }
        }
        prefs.loginFailureCount = 0
        upsertSession(context, databaseUrl, apiKey, method).onFailure { Log.w(TAG, "session not recorded") }
        rememberSession(prefs, prefs.authInstallId)
        appendEvent(databaseUrl, apiKey, AuthPolicy.SecurityEvent.SIGN_IN, method)
    }

    /**
     * 새 기기 로그인 감지 — 이 기기가 아직 본 적 없는 세션이 생겼으면 돌려준다(처음 실행 때는 전부 "본 것"으로
     * 기록만 한다). 호출할 때마다 이 기기의 "최근 접속"도 갱신한다.
     */
    suspend fun detectNewDevices(context: Context, databaseUrl: String?, apiKey: String?): List<Session> {
        val prefs = AppPreferences(context)
        upsertSession(context, databaseUrl, apiKey, method = null)
        val sessions = readSessions(databaseUrl, apiKey).getOrElse { return emptyList() }
        val known = prefs.knownSessionIds.split(',').filter { it.isNotBlank() }.toSet()
        val firstRun = known.isEmpty()
        val fresh = sessions.filter { it.id != prefs.authInstallId && it.id !in known }
        prefs.knownSessionIds = (known + sessions.map { it.id } + prefs.authInstallId).joinToString(",")
        return if (firstRun) emptyList() else fresh
    }

    /** 이 계정의 아이디 — 프로필 customId(게스트 아이디 제외), 없으면 가짜 로그인 이메일에서. */
    suspend fun resolveMyId(databaseUrl: String?, apiKey: String?): String? {
        val user = AuthManager.currentUser ?: return null
        if (user.isAnonymous) return null
        val fromProfile = AccountSyncClient.fetchMyProfile(databaseUrl, apiKey).getOrNull()
            ?.optString("customId")?.takeIf { it.isNotBlank() && AuthPolicy.ID_PATTERN.matches(it) }
        return fromProfile ?: AuthPolicy.idFromSyntheticEmail(user.email)
    }

    private fun rememberSession(prefs: AppPreferences, id: String) {
        val known = prefs.knownSessionIds.split(',').filter { it.isNotBlank() }.toSet()
        if (id !in known) prefs.knownSessionIds = (known + id).joinToString(",")
    }

    // ------------------------------------------------------------ 내부

    const val PLATFORM = "android"

    fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").trim().take(80)
    }

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

    /** 로그인된 사용자의 토큰으로 요청한다(토큰은 URL 쿼리로만 싣고 로그에 남기지 않는다). */
    private suspend fun <T> authed(databaseUrl: String?, block: suspend (base: String, token: String, uid: String) -> T): Result<T> {
        if (databaseUrl.isNullOrBlank()) return Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
        val user = AuthManager.currentUser ?: return Result.failure(IllegalStateException("로그인이 필요합니다."))
        return withContext(Dispatchers.IO) {
            runCatching {
                val token = user.getIdToken(false).await().token ?: error("no token")
                block(databaseUrl.trimEnd('/'), token, user.uid)
            }
        }
    }

    private fun request(method: String, url: URL, body: String? = null): Pair<Int, String?> {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}
