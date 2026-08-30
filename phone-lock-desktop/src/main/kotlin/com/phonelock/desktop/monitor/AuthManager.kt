package com.phonelock.desktop.monitor

import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 데스크탑 로그인 — Firebase Authentication의 email/password REST 엔드포인트를 직접 호출한다(SDK 없음,
 * 안드로이드도 동일 프로젝트를 씀). 사용자가 입력하는 "아이디"는 Firebase Auth가 이메일 형식만 지원하기
 * 때문에 내부적으로 가짜 이메일(`{아이디}@$SYNTHETIC_EMAIL_DOMAIN`)로 변환해서 인증에 쓴다 — 화면엔 노출 안 됨.
 *
 * 세션(uid/email/refreshToken)은 안드로이드처럼 SDK에 위임할 수 없어 이 파일이 직접 별도 파일
 * (`google_auth.json`, `data.json`과 같은 디렉터리 — 파일명은 과거 로그인 시절 그대로 유지해서
 * 기존 세션 파일 경로와의 호환을 지킨다)에 저장한다. [PomodoroSyncClient]는 로그인이 돼 있어야만
 * 동작한다 — 로그인이 안 돼 있으면 fail-safe로 조용히 동기화를 쉰다.
 */
object AuthManager {
    private const val SYNTHETIC_EMAIL_DOMAIN = "phonelockapp.local"
    private fun idToSyntheticEmail(id: String) = "$id@$SYNTHETIC_EMAIL_DOMAIN"

    private const val TIMEOUT_SECONDS = 10L
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build()

    private val authDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PhoneLockDesktop")
    private val authFile = File(authDir, "google_auth.json")

    private data class Session(
        val uid: String,
        val email: String?,
        var idToken: String,
        val refreshToken: String,
        var expiresAtMillis: Long,
        val isAnonymous: Boolean = false
    )

    @Volatile
    private var session: Session? = loadPersisted()

    val currentEmail: String? get() = session?.email
    val currentUid: String? get() = session?.uid
    val isSignedIn: Boolean get() = session != null
    val isAnonymous: Boolean get() = session?.isAnonymous == true

    /** 로그인 아이디(가짜 이메일에서 복원). 게스트 등 다른 방식으로 로그인된 경우 null. */
    val currentLoginId: String?
        get() = session?.email?.takeIf { it.endsWith("@$SYNTHETIC_EMAIL_DOMAIN") }
            ?.removeSuffix("@$SYNTHETIC_EMAIL_DOMAIN")

    private fun loadPersisted(): Session? = runCatching {
        if (!authFile.exists()) return null
        val json = JSONObject(authFile.readText())
        val uid = json.optString("uid", "")
        val refreshToken = json.optString("refreshToken", "")
        if (uid.isBlank() || refreshToken.isBlank()) return null
        // idToken/expiresAtMillis는 저장하지 않는다(ID 토큰은 어차피 1시간 안에 만료돼 영속화할 가치가
        // 없음) — 시작 시 만료된 것으로 취급해 첫 사용 때 refreshToken으로 자동 갱신되게 한다.
        Session(uid, json.optString("email", "").ifBlank { null }, "", refreshToken, 0L, json.optBoolean("isAnonymous", false))
    }.getOrNull()

    private fun persist(s: Session?) {
        runCatching {
            authDir.mkdirs()
            if (s == null) {
                authFile.delete()
                return
            }
            val json = JSONObject().apply {
                put("uid", s.uid)
                put("email", s.email ?: JSONObject.NULL)
                put("refreshToken", s.refreshToken)
                put("isAnonymous", s.isAnonymous)
            }
            authFile.writeText(json.toString())
        }
    }

    fun signOut() {
        session = null
        persist(null)
    }

    /** Firebase Authentication 계정 자체를 삭제한다 — 호출 전 RTDB 데이터부터 지울 것(삭제 후엔 이
     *  uid로 더 이상 인증을 못 하므로 데이터 정리 요청이 통과하지 않는다). */
    fun deleteAccount(apiKey: String): Result<Unit> = runCatching {
        val idToken = ensureIdToken(apiKey) ?: error("로그인이 필요합니다.")
        val body = JSONObject().apply { put("idToken", idToken) }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://identitytoolkit.googleapis.com/v1/accounts:delete?key=$apiKey"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) error("계정 삭제 실패: ${response.body()}")
        signOut()
    }

    fun signUp(id: String, password: String, apiKey: String): Result<String> =
        emailPasswordRequest("accounts:signUp", idToSyntheticEmail(id), password, apiKey)

    fun signIn(id: String, password: String, apiKey: String): Result<String> =
        emailPasswordRequest("accounts:signInWithPassword", idToSyntheticEmail(id), password, apiKey)

    private fun emailPasswordRequest(endpoint: String, email: String, password: String, apiKey: String): Result<String> = runCatching {
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
            put("returnSecureToken", true)
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://identitytoolkit.googleapis.com/v1/$endpoint?key=$apiKey"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val message = runCatching { JSONObject(response.body()).getJSONObject("error").getString("message") }.getOrNull()
            error(firebaseErrorToMessage(message))
        }
        val json = JSONObject(response.body())
        val newSession = Session(
            uid = json.getString("localId"),
            email = json.optString("email", "").ifBlank { null },
            idToken = json.getString("idToken"),
            refreshToken = json.getString("refreshToken"),
            expiresAtMillis = System.currentTimeMillis() + json.optString("expiresIn", "3600").toLong() * 1000L
        )
        session = newSession
        persist(newSession)
        newSession.uid
    }

    private fun firebaseErrorToMessage(code: String?): String = when (code) {
        "EMAIL_EXISTS" -> "이미 사용 중인 아이디입니다."
        "EMAIL_NOT_FOUND", "INVALID_LOGIN_CREDENTIALS", "INVALID_PASSWORD" -> "아이디 또는 비밀번호가 올바르지 않습니다."
        "WEAK_PASSWORD : Password should be at least 6 characters" -> "비밀번호는 6자 이상이어야 합니다."
        "USER_DISABLED" -> "사용이 제한된 계정입니다."
        null -> "요청이 실패했습니다."
        else -> code
    }

    /**
     * 익명(게스트) 로그인 — Firebase Identity Toolkit의 익명 계정 생성 엔드포인트를 호출해 새 uid를
     * 발급받는다. 브라우저를 열 필요 없이 즉시 완료된다(가입 신청 플로우에서 "게스트로 진행" 버튼용).
     * 성공하면 uid를 반환.
     */
    fun signInGuest(apiKey: String): Result<String> = runCatching {
        val body = JSONObject().apply { put("returnSecureToken", true) }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$apiKey"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) error("게스트 로그인 실패: ${response.body()}")
        val json = JSONObject(response.body())
        val newSession = Session(
            uid = json.getString("localId"),
            email = null,
            idToken = json.getString("idToken"),
            refreshToken = json.getString("refreshToken"),
            expiresAtMillis = System.currentTimeMillis() + json.optString("expiresIn", "3600").toLong() * 1000L,
            isAnonymous = true
        )
        session = newSession
        persist(newSession)
        newSession.uid
    }

    /** 비밀번호 변경 — 로그인 아이디(이메일)는 가입 신청 아이디와 통합돼있어 영구 고정이라 바꿀 수 없다. */
    fun changePassword(newPassword: String, apiKey: String): Result<Unit> = runCatching {
        val idToken = ensureIdToken(apiKey) ?: error("로그인이 필요합니다.")
        val body = JSONObject().apply {
            put("idToken", idToken)
            put("password", newPassword)
            put("returnSecureToken", true)
        }
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://identitytoolkit.googleapis.com/v1/accounts:update?key=$apiKey"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val message = runCatching { JSONObject(response.body()).getJSONObject("error").getString("message") }.getOrNull()
            error(firebaseErrorToMessage(message))
        }
        // 비밀번호 변경은 기존 refreshToken을 전부 무효화하므로(Firebase 보안 정책), 응답의 새
        // refreshToken으로 세션 전체를 교체해서 영속화해야 다음 실행 때도 로그인이 유지된다.
        val json = JSONObject(response.body())
        val current = session ?: error("로그인이 필요합니다.")
        val newSession = current.copy(
            idToken = json.getString("idToken"),
            refreshToken = json.optString("refreshToken", current.refreshToken),
            expiresAtMillis = System.currentTimeMillis() + json.optString("expiresIn", "3600").toLong() * 1000L
        )
        session = newSession
        persist(newSession)
        Unit
    }

    /** 로그인된 상태라면 유효한 Firebase ID 토큰을 반환(만료가 가까우면 자동 갱신), 아니면 null. */
    fun ensureIdToken(apiKey: String): String? {
        val current = session ?: return null
        val now = System.currentTimeMillis()
        if (current.idToken.isNotBlank() && now < current.expiresAtMillis - 60_000L) return current.idToken

        val refreshed = refreshIdToken(apiKey, current.refreshToken) ?: return null
        current.idToken = refreshed.first
        current.expiresAtMillis = refreshed.second
        return current.idToken
    }

    private fun refreshIdToken(apiKey: String, refreshToken: String): Pair<String, Long>? = runCatching {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://securetoken.googleapis.com/v1/token?key=$apiKey"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=refresh_token&refresh_token=$refreshToken"))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        val json = JSONObject(response.body())
        val idToken = json.getString("id_token")
        val expiresAt = System.currentTimeMillis() + json.optString("expires_in", "3600").toLong() * 1000L
        idToken to expiresAt
    }.getOrNull()
}
