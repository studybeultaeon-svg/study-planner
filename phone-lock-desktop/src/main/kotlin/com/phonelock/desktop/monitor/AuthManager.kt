package com.phonelock.desktop.monitor

import com.phonelock.shared.auth.AuthPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 데스크탑 로그인 — Firebase Authentication REST(Identity Toolkit)를 직접 호출한다(SDK 없음, 안드로이드와 같은
 * 프로젝트). **계정(User)은 Firebase uid 하나**이고, 아이디/이메일 비밀번호와 구글 계정은 그 uid에 붙은 로그인
 * 수단이다(140차 다중 로그인 — 모델은 [AuthPolicy], 근거는 DECISIONS.md 140차).
 *
 * - 아이디는 가짜 이메일(`{아이디}@phonelockapp.local`)로 Firebase에 넣어 왔고, 실제 이메일을 등록하면 로그인
 *   이메일이 그 이메일로 바뀐다. 아이디 로그인은 `loginIds`에서 로그인 이메일을 찾는다([AccountSecurityClient]).
 * - 구글 로그인은 브라우저 루프백 + PKCE([GoogleDesktopOAuth]) → `accounts:signInWithIdp`. 이메일이 같다고 기존
 *   계정에 자동으로 붙이지 않는다(처음 보는 구글 계정이면 [GoogleSignInOutcome.NewAccount]).
 *
 * 세션(uid/email/refreshToken)은 SDK가 없어 이 파일이 직접 `google_auth.json`(`data.json`과 같은 폴더 — 파일명은
 * 옛 로그인 시절 그대로 둬서 기존 세션과 호환)에 저장한다. 갱신 토큰이 **확실히 무효**가 되면(다른 기기에서 비밀번호·
 * 이메일 변경, 계정 삭제·정지) 세션을 지운다 — 네트워크 오류로는 절대 로그아웃하지 않는다(121차 원칙).
 * 토큰·비밀번호는 로그에 남기지 않는다.
 */
object AuthManager {
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

    /**
     * 가짜 이메일에서 되돌린 아이디 — **계정을 방금 만든 직후(가입 신청 화면)에만** 믿을 수 있다. 140차부터는 아이디를
     * 바꿔도 로그인 이메일을 바꾸지 않고, 실제 이메일을 등록하면 가짜 이메일이 사라지므로 화면에 보여줄 아이디는
     * 프로필의 customId를 쓸 것.
     */
    val currentLoginId: String? get() = AuthPolicy.idFromSyntheticEmail(session?.email)

    /** 이 빌드에 데스크탑 구글 OAuth 클라이언트가 들어 있을 때만 true(없으면 구글 버튼을 숨긴다). */
    val isGoogleSignInAvailable: Boolean get() = GoogleDesktopOAuth.isConfigured

    // ---------------------------------------------------------------- 계정 정보(로그인 수단)

    /** `accounts:lookup`으로 읽은 계정 상태 — 로그인 수단 판정용. */
    data class AccountInfo(
        val email: String?,
        val hasPassword: Boolean,
        val googleSub: String?,
        val googleEmail: String?,
        val createdAtMillis: Long
    )

    @Volatile
    var accountInfo: AccountInfo? = null
        private set

    /** 이 계정에 붙은 로그인 수단. 계정 정보를 아직 못 읽었으면 세션만 보고 추정한다(이메일이 있으면 비밀번호 계정). */
    fun loginMethods(): AuthPolicy.LoginMethods {
        val s = session
        val info = accountInfo
        return AuthPolicy.LoginMethods(
            hasPassword = info?.hasPassword ?: (s?.email != null && s.isAnonymous.not()),
            hasGoogle = info?.googleSub != null,
            isAnonymous = s?.isAnonymous == true,
            loginEmail = info?.email ?: s?.email
        )
    }

    val linkedGoogleEmail: String? get() = accountInfo?.googleEmail

    /** 서버의 계정 상태를 다시 읽는다(로그인 수단·로그인 이메일). */
    fun refreshAccountInfo(apiKey: String): Result<AccountInfo> = runCatching {
        val idToken = ensureIdToken(apiKey) ?: throw SessionEndedException()
        val json = post("accounts:lookup", JSONObject().put("idToken", idToken), apiKey)
        val user = json.optJSONArray("users")?.optJSONObject(0) ?: throw SessionEndedException()
        val providers = user.optJSONArray("providerUserInfo") ?: JSONArray()
        var hasPassword = false
        var googleSub: String? = null
        var googleEmail: String? = null
        for (i in 0 until providers.length()) {
            val p = providers.optJSONObject(i) ?: continue
            when (p.optString("providerId")) {
                "password" -> hasPassword = true
                "google.com" -> {
                    googleSub = p.optString("rawId").takeIf { it.isNotBlank() }
                    googleEmail = p.optString("email").takeIf { it.isNotBlank() }
                }
            }
        }
        val info = AccountInfo(
            email = user.optString("email").takeIf { it.isNotBlank() },
            hasPassword = hasPassword,
            googleSub = googleSub,
            googleEmail = googleEmail,
            createdAtMillis = user.optString("createdAt").toLongOrNull() ?: 0L
        )
        accountInfo = info
        val current = session
        if (current != null && current.email != info.email) {
            val updated = current.copy(email = info.email, isAnonymous = current.isAnonymous && info.email == null && !hasPassword && googleSub == null)
            session = updated
            persist(updated)
        }
        info
    }

    // ---------------------------------------------------------------- 로그인 / 가입

    data class SignInResult(val uid: String, val method: String, val loginIdKey: String?)

    /**
     * "아이디 또는 이메일" 한 칸으로 로그인한다(안드로이드판과 같은 규칙). 계정이 없을 때와 비밀번호가 틀렸을 때
     * **같은 문장**을 돌려준다.
     */
    fun signInWithIdentifier(databaseUrl: String?, input: String, password: String, apiKey: String): Result<SignInResult> {
        return when (val ident = AuthPolicy.parseIdentifier(input)) {
            is AuthPolicy.LoginIdentifier.Email -> signInWithEmail(ident.email, password, apiKey).map { SignInResult(it, "email", null) }
            is AuthPolicy.LoginIdentifier.Id -> {
                val lookup = AccountSecurityClient.lookupLoginId(databaseUrl, ident.key)
                if (lookup.isFailure) return Result.failure(Exception(NETWORK_MESSAGE))
                val record = lookup.getOrNull()
                val candidates = if (record == null) listOf(AuthPolicy.syntheticEmail(ident.id)) else record.emailCandidates()
                if (candidates.isEmpty()) return Result.failure(Exception(WRONG_CREDENTIALS_MESSAGE))
                var last: Result<String> = Result.failure(Exception(WRONG_CREDENTIALS_MESSAGE))
                for (email in candidates) {
                    last = signInWithEmail(email, password, apiKey)
                    if (last.isSuccess || !isWrongCredentials(last.exceptionOrNull())) break
                }
                last.map { SignInResult(it, "id", ident.key) }
            }
            AuthPolicy.LoginIdentifier.Invalid -> Result.failure(Exception("아이디(영문/숫자 3~20자) 또는 이메일 주소를 입력해 주세요."))
        }
    }

    private fun signInWithEmail(email: String, password: String, apiKey: String): Result<String> =
        emailPasswordRequest("accounts:signInWithPassword", email, password, apiKey)

    fun signUp(id: String, password: String, apiKey: String): Result<String> =
        emailPasswordRequest("accounts:signUp", AuthPolicy.syntheticEmail(id), password, apiKey)

    private fun emailPasswordRequest(endpoint: String, email: String, password: String, apiKey: String): Result<String> = runCatching {
        val json = post(endpoint, JSONObject().put("email", email).put("password", password).put("returnSecureToken", true), apiKey)
        adoptSession(json, isAnonymous = false)
        refreshAccountInfo(apiKey)
        markFreshlyAuthenticated()
        json.getString("localId")
    }

    /**
     * 익명(게스트) 로그인 — Firebase Identity Toolkit의 익명 계정 생성 엔드포인트를 호출해 새 uid를
     * 발급받는다. 브라우저를 열 필요 없이 즉시 완료된다(가입 신청 플로우에서 "게스트로 진행" 버튼용).
     * 성공하면 uid를 반환.
     */
    fun signInGuest(apiKey: String): Result<String> = runCatching {
        val json = post("accounts:signUp", JSONObject().put("returnSecureToken", true), apiKey)
        adoptSession(json, isAnonymous = true, email = null)
        accountInfo = null
        json.getString("localId")
    }

    // ---------------------------------------------------------------- 구글

    sealed class GoogleSignInOutcome {
        object Existing : GoogleSignInOutcome()
        /** 처음 보는 구글 계정 — 방금 새 계정이 만들어졌다. 화면이 "새 계정 / 기존 계정에 연결 / 취소"를 묻는다. */
        data class NewAccount(val google: GoogleDesktopOAuth.GoogleIdentity) : GoogleSignInOutcome()
    }

    /** 브라우저로 구글 계정을 확인만 한다(연결·재인증 전 단계). 블로킹. */
    fun pickGoogleAccount(): Result<GoogleDesktopOAuth.GoogleIdentity> = GoogleDesktopOAuth.authorize().recoverCatching { throw friendly(it) }

    /**
     * 구글로 로그인한다. 자동 연결 차단: 앱이 직접 연결할 때는 항상 먼저 `authMeta/{uid}/identities/google`에 구글 ID를
     * 적어 두므로, 기록이 없는데 구글이 붙은 기존 계정이면 Firebase가 이메일로 자동 연결한 것으로 보고 떼어낸 뒤
     * 로그아웃한다(확인하지 못해도 거절 — 안드로이드판과 같은 규칙).
     */
    fun signInWithGoogle(apiKey: String, databaseUrl: String?): Result<GoogleSignInOutcome> = runCatching {
        val google = GoogleDesktopOAuth.authorize().getOrElse { throw friendly(it) }
        val json = signInWithIdp(google.idToken, apiKey, linkToIdToken = null)
        adoptSession(json, isAnonymous = false)
        refreshAccountInfo(apiKey)
        markFreshlyAuthenticated()
        if (json.optBoolean("isNewUser", false)) return@runCatching GoogleSignInOutcome.NewAccount(google)
        val recorded = AccountSecurityClient.readGoogleIdentitySub(databaseUrl, apiKey)
        if (recorded.isFailure) {
            signOut()
            error("연결 정보를 확인하지 못했습니다. 인터넷 연결을 확인한 뒤 다시 시도해 주세요.")
        }
        if (recorded.getOrNull() != google.sub) {
            AccountSecurityClient.appendEvent(databaseUrl, apiKey, AuthPolicy.SecurityEvent.AUTO_LINK_BLOCKED, method = "google")
            runCatching { unlinkProvider(apiKey) }
            signOut()
            error(AUTO_LINK_BLOCKED_MESSAGE)
        }
        GoogleSignInOutcome.Existing
    }

    /** 방금 구글로 새로 만들어진 계정을 지운다 — 구글만 붙어 있고 만든 지 30분 안 된 계정만(기존 계정 보호). */
    fun discardNewGoogleAccount(apiKey: String) {
        val info = accountInfo
        val fresh = info != null && System.currentTimeMillis() - info.createdAtMillis < 30 * 60 * 1000L
        if (info != null && info.googleSub != null && !info.hasPassword && fresh) {
            runCatching { deleteAccount(apiKey) }
        }
        signOut()
    }

    /** 로그인한 계정에 구글을 연결한다(호출 전 재인증). 연결 기록을 **먼저** 남긴다 — 없으면 다음 구글 로그인이 거절된다. */
    fun linkGoogle(google: GoogleDesktopOAuth.GoogleIdentity, apiKey: String, databaseUrl: String?): Result<Unit> = runCatching {
        check(!loginMethods().hasGoogle) { "이미 구글 계정이 연결돼 있습니다." }
        val idToken = ensureIdToken(apiKey) ?: throw SessionEndedException()
        AccountSecurityClient.setGoogleIdentity(databaseUrl, apiKey, google.sub, google.email).getOrThrow()
        try {
            val json = signInWithIdp(google.idToken, apiKey, linkToIdToken = idToken)
            check(json.optString("localId") == currentUid) { "다른 계정이 응답했습니다." }
            adoptSession(json, isAnonymous = false, email = session?.email)
            refreshAccountInfo(apiKey)
        } catch (e: Exception) {
            AccountSecurityClient.clearGoogleIdentity(databaseUrl, apiKey)
            throw e
        }
    }

    /** 구글 연결 해제(호출 전 재인증). **마지막 로그인 수단이면 거절** — 화면이 먼저 비밀번호를 정하게 해야 한다. */
    fun unlinkGoogle(apiKey: String, databaseUrl: String?): Result<Unit> = runCatching {
        check(loginMethods().canUnlinkGoogle) { "구글이 유일한 로그인 수단이라 연결을 해제할 수 없습니다. 먼저 비밀번호를 설정해 주세요." }
        unlinkProvider(apiKey)
        AccountSecurityClient.clearGoogleIdentity(databaseUrl, apiKey)
        refreshAccountInfo(apiKey)
    }

    private fun unlinkProvider(apiKey: String) {
        val idToken = ensureIdToken(apiKey) ?: throw SessionEndedException()
        post("accounts:update", JSONObject().put("idToken", idToken).put("deleteProvider", JSONArray().put("google.com")).put("returnSecureToken", true), apiKey)
    }

    private fun signInWithIdp(googleIdToken: String, apiKey: String, linkToIdToken: String?): JSONObject {
        val body = JSONObject()
            .put("postBody", "id_token=$googleIdToken&providerId=google.com")
            .put("requestUri", "http://localhost")
            .put("returnIdpCredential", false)
            .put("returnSecureToken", true)
        linkToIdToken?.let { body.put("idToken", it) }
        val json = post("accounts:signInWithIdp", body, apiKey)
        // 연결 실패는 200 응답의 errorMessage로 오기도 한다. needConfirmation = "이메일당 계정 하나" 모드에서 같은 이메일의 다른 계정이 있음.
        json.optString("errorMessage").takeIf { it.isNotBlank() }?.let { throw friendly(RestException(it)) }
        if (json.optBoolean("needConfirmation", false) || !json.has("idToken")) error(AUTO_LINK_BLOCKED_MESSAGE)
        return json
    }

    // ---------------------------------------------------------------- 재인증

    @Volatile
    private var lastReauthAtMillis = 0L

    val isRecentlyReauthenticated: Boolean
        get() = System.currentTimeMillis() - lastReauthAtMillis < AuthPolicy.REAUTH_VALID_MS

    fun markFreshlyAuthenticated() {
        lastReauthAtMillis = System.currentTimeMillis()
    }

    /** 비밀번호로 본인 확인 — 같은 계정으로 다시 로그인해 로그인 시각(auth_time)을 새로 받는다. */
    fun reauthenticateWithPassword(password: String, apiKey: String): Result<Unit> = runCatching {
        val current = session ?: throw SessionEndedException()
        val email = accountInfo?.email ?: current.email ?: error("비밀번호로 확인할 수 없는 계정입니다.")
        val json = try {
            post("accounts:signInWithPassword", JSONObject().put("email", email).put("password", password).put("returnSecureToken", true), apiKey)
        } catch (e: Exception) {
            if (restCode(e) in WRONG_CREDENTIAL_CODES) error("비밀번호가 올바르지 않습니다.") else throw e
        }
        check(json.optString("localId") == current.uid) { "다른 계정의 비밀번호입니다." }
        adoptSession(json, isAnonymous = false, email = email)
        markFreshlyAuthenticated()
    }

    /** 구글로 본인 확인 — 이 계정에 연결된 구글 계정을 골라야 한다(다른 계정이면 그 계정은 건드리지 않고 거절). */
    fun reauthenticateWithGoogle(apiKey: String): Result<GoogleDesktopOAuth.GoogleIdentity> = runCatching {
        val current = session ?: throw SessionEndedException()
        val google = GoogleDesktopOAuth.authorize().getOrElse { throw friendly(it) }
        val json = signInWithIdp(google.idToken, apiKey, linkToIdToken = null)
        if (json.optString("localId") != current.uid) {
            // 연결 안 된 구글 계정을 골라 새 계정이 생겼으면 곧바로 지운다.
            if (json.optBoolean("isNewUser", false)) runCatching {
                post("accounts:delete", JSONObject().put("idToken", json.getString("idToken")), apiKey)
            }
            error("지금 로그인한 계정에 연결된 구글 계정과 다른 계정을 골랐습니다. 같은 계정을 골라 주세요.")
        }
        adoptSession(json, isAnonymous = false, email = current.email)
        markFreshlyAuthenticated()
        google
    }

    /** 현재 세션의 로그인(또는 마지막 재인증) 시각, 초 — DB 규칙의 `auth.token.auth_time`과 같다. */
    fun authTimeSec(apiKey: String, forceRefresh: Boolean = false): Long? {
        if (forceRefresh) session?.let { it.expiresAtMillis = 0L }
        val token = ensureIdToken(apiKey) ?: return null
        return GoogleDesktopOAuth.decodeJwtPayload(token)?.optLong("auth_time")?.takeIf { it > 0 }
    }

    // ---------------------------------------------------------------- 비밀번호 / 이메일

    /** 비밀번호 변경(호출 전 재인증). Firebase가 다른 기기의 갱신 토큰을 무효화하므로 응답의 새 토큰으로 세션을 바꾼다. */
    fun changePassword(newPassword: String, apiKey: String): Result<Unit> = runCatching {
        val idToken = ensureIdToken(apiKey) ?: throw SessionEndedException()
        val json = post("accounts:update", JSONObject().put("idToken", idToken).put("password", newPassword).put("returnSecureToken", true), apiKey)
        adoptSession(json, isAnonymous = false, email = session?.email)
    }

    /** 구글로만 가입한 계정에 비밀번호를 붙인다 — 이후 로그인 이메일(구글 이메일)과 이 비밀번호로도 로그인된다. */
    fun setPassword(newPassword: String, apiKey: String): Result<Unit> = changePassword(newPassword, apiKey).map {
        refreshAccountInfo(apiKey)
        Unit
    }

    /** 로그인 이메일을 [newEmail]로 바꾸는 인증 메일(호출 전 재인증). 링크를 누르는 순간 모든 기기의 로그인이 끊긴다. */
    fun requestEmailChange(newEmail: String, apiKey: String): Result<Unit> = runCatching {
        val idToken = ensureIdToken(apiKey) ?: throw SessionEndedException()
        post(
            "accounts:sendOobCode",
            JSONObject().put("requestType", "VERIFY_AND_CHANGE_EMAIL").put("idToken", idToken).put("newEmail", AuthPolicy.normalizeEmail(newEmail)),
            apiKey
        )
        Unit
    }

    /** 비밀번호 재설정 메일 — 계정이 없어도 성공으로 돌려준다(계정 존재 여부를 알려 주지 않기 위해). */
    fun sendPasswordReset(email: String, apiKey: String): Result<Unit> = runCatching {
        try {
            post("accounts:sendOobCode", JSONObject().put("requestType", "PASSWORD_RESET").put("email", AuthPolicy.normalizeEmail(email)), apiKey)
        } catch (e: Exception) {
            if (restCode(e) !in setOf("EMAIL_NOT_FOUND", "INVALID_EMAIL", "USER_DISABLED")) throw e
        }
        Unit
    }

    /**
     * 서버의 최신 계정 상태를 다시 읽는다(이메일 인증 확인용). 토큰을 새로 받아 보고, 이메일이 바뀌어 로그인이 끊겼으면
     * [SessionEndedException] — 화면은 다시 로그인하게 안내한다.
     */
    fun reloadUser(apiKey: String): Result<AccountInfo> {
        session?.let { it.expiresAtMillis = 0L }
        if (ensureIdToken(apiKey) == null) return Result.failure(if (session == null) SessionEndedException() else Exception(NETWORK_MESSAGE))
        return refreshAccountInfo(apiKey)
    }

    class SessionEndedException : Exception("보안을 위해 다시 로그인해야 합니다.")

    // ---------------------------------------------------------------- 로그아웃 / 삭제

    fun signOut() {
        session = null
        accountInfo = null
        signedOutBecauseRevoked = false
        persist(null)
    }

    /** 마지막 로그아웃이 "갱신 토큰 무효"(다른 기기에서 비밀번호·이메일 변경, 계정 정지 등) 때문이었나 — 게이트가 한 번 읽고 지운다. */
    @Volatile
    private var signedOutBecauseRevoked = false

    fun consumeRevokedSignOut(): Boolean = signedOutBecauseRevoked.also { signedOutBecauseRevoked = false }

    /** Firebase 계정 자체를 삭제한다 — 호출 전 재인증하고 DB 데이터부터 지울 것(삭제 후엔 이 uid로 인증할 수 없다). */
    fun deleteAccount(apiKey: String): Result<Unit> = runCatching {
        val idToken = ensureIdToken(apiKey) ?: error("로그인이 필요합니다.")
        post("accounts:delete", JSONObject().put("idToken", idToken), apiKey)
        signOut()
    }

    // ---------------------------------------------------------------- 토큰

    /**
     * 로그인된 상태라면 유효한 Firebase ID 토큰을 반환(만료가 가까우면 자동 갱신), 아니면 null. 갱신 토큰이 확실히
     * 무효(취소·계정 삭제/정지)면 세션을 지운다 — 네트워크 오류면 세션은 그대로 두고 null만 돌려준다.
     */
    fun ensureIdToken(apiKey: String): String? {
        val current = session ?: return null
        val now = System.currentTimeMillis()
        if (current.idToken.isNotBlank() && now < current.expiresAtMillis - 60_000L) return current.idToken

        return when (val refreshed = refreshIdToken(apiKey, current.refreshToken)) {
            is Refresh.Ok -> {
                current.idToken = refreshed.idToken
                current.expiresAtMillis = refreshed.expiresAtMillis
                current.idToken
            }
            Refresh.Revoked -> {
                if (session === current) {
                    signOut()
                    signedOutBecauseRevoked = true
                }
                null
            }
            Refresh.Unavailable -> null
        }
    }

    private sealed class Refresh {
        data class Ok(val idToken: String, val expiresAtMillis: Long) : Refresh()
        object Revoked : Refresh()
        object Unavailable : Refresh()
    }

    private fun refreshIdToken(apiKey: String, refreshToken: String): Refresh = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://securetoken.googleapis.com/v1/token?key=$apiKey"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=refresh_token&refresh_token=$refreshToken"))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) {
            val json = JSONObject(response.body())
            Refresh.Ok(json.getString("id_token"), System.currentTimeMillis() + json.optString("expires_in", "3600").toLong() * 1000L)
        } else {
            val code = errorCode(response.body())
            if (code in REVOKED_REFRESH_CODES) Refresh.Revoked else Refresh.Unavailable
        }
    } catch (e: Exception) {
        Refresh.Unavailable
    }

    // ---------------------------------------------------------------- 내부

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

    /** 로그인·재인증·비밀번호 변경 응답의 토큰으로 세션을 바꾼다(비밀번호·이메일 변경은 옛 갱신 토큰을 무효화한다). */
    private fun adoptSession(json: JSONObject, isAnonymous: Boolean, email: String? = json.optString("email").ifBlank { null }) {
        val uid = json.optString("localId").ifBlank { session?.uid } ?: error("사용자 정보를 받지 못했습니다.")
        val newSession = Session(
            uid = uid,
            email = email,
            idToken = json.getString("idToken"),
            refreshToken = json.optString("refreshToken").ifBlank { session?.refreshToken.orEmpty() },
            expiresAtMillis = System.currentTimeMillis() + json.optString("expiresIn", "3600").toLong() * 1000L,
            isAnonymous = isAnonymous
        )
        session = newSession
        persist(newSession)
    }

    /** Identity Toolkit 오류 — [code]는 `error.message`의 앞부분(예: "WEAK_PASSWORD : ..." → "WEAK_PASSWORD"). */
    class RestException(val code: String) : Exception(code)

    /** [post]가 던진(문장으로 바뀐) 예외에서 원래 REST 오류 코드를 꺼낸다. */
    private fun restCode(e: Throwable): String? = (e as? RestException)?.code ?: (e.cause as? RestException)?.code

    private fun post(endpoint: String, body: JSONObject, apiKey: String): JSONObject {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://identitytoolkit.googleapis.com/v1/$endpoint?key=$apiKey"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .header("X-Firebase-Locale", "ko")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw Exception(NETWORK_MESSAGE)
        }
        if (response.statusCode() !in 200..299) throw friendly(RestException(errorCode(response.body()) ?: "HTTP_${response.statusCode()}"))
        return JSONObject(response.body().ifBlank { "{}" })
    }

    private fun errorCode(body: String?): String? = runCatching {
        JSONObject(body.orEmpty()).getJSONObject("error").getString("message").substringBefore(" ").trim()
    }.getOrNull()

    const val WRONG_CREDENTIALS_MESSAGE = "아이디(또는 이메일)나 비밀번호가 올바르지 않습니다."
    private const val NETWORK_MESSAGE = "서버에 연결하지 못했습니다. 인터넷 연결 상태를 확인한 뒤 다시 시도해 주세요."
    private const val AUTO_LINK_BLOCKED_MESSAGE =
        "이 구글 계정은 아직 연결되지 않았습니다. 기존 계정으로 로그인한 뒤 설정 > 로그인 및 보안에서 직접 연결해 주세요."
    private val WRONG_CREDENTIAL_CODES = setOf("EMAIL_NOT_FOUND", "INVALID_PASSWORD", "INVALID_LOGIN_CREDENTIALS", "INVALID_EMAIL")
    private val SESSION_ENDED_CODES = setOf("TOKEN_EXPIRED", "INVALID_ID_TOKEN", "USER_NOT_FOUND", "USER_DISABLED", "CREDENTIAL_TOO_OLD_LOGIN_AGAIN")
    private val REVOKED_REFRESH_CODES = setOf("TOKEN_EXPIRED", "USER_DISABLED", "USER_NOT_FOUND", "INVALID_REFRESH_TOKEN", "PROJECT_NUMBER_MISMATCH")

    fun isWrongCredentials(e: Throwable?): Boolean = e?.message == WRONG_CREDENTIALS_MESSAGE

    /** REST 오류 코드를 사용자가 읽을 수 있는 문장으로(원문 코드는 화면에 괄호로만). */
    private fun friendly(e: Throwable): Exception {
        if (e is GoogleDesktopOAuth.CancelledException || e is SessionEndedException) return e as Exception
        if (e !is RestException) return (e as? Exception) ?: Exception(e.message)
        val message = when (e.code) {
            in WRONG_CREDENTIAL_CODES -> WRONG_CREDENTIALS_MESSAGE
            "EMAIL_EXISTS" -> "이미 사용 중인 아이디 또는 이메일입니다."
            "WEAK_PASSWORD" -> "비밀번호가 너무 단순합니다. 영문과 숫자를 섞어 ${AuthPolicy.NEW_PASSWORD_MIN}자 이상으로 정해 주세요."
            "USER_DISABLED" -> "사용이 제한된 계정입니다. 관리자에게 문의해 주세요."
            "TOO_MANY_ATTEMPTS_TRY_LATER" -> "시도가 너무 많아 잠시 막혔습니다. 몇 분 뒤 다시 시도해 주세요."
            "FEDERATED_USER_ID_ALREADY_LINKED" -> "이 구글 계정은 이미 다른 계정에 연결돼 있습니다."
            "CREDENTIAL_TOO_OLD_LOGIN_AGAIN" -> "보안을 위해 본인 확인이 다시 필요합니다. 다시 시도해 주세요."
            in SESSION_ENDED_CODES -> "보안을 위해 다시 로그인해야 합니다."
            "INVALID_IDP_RESPONSE" -> "구글 인증 응답이 올바르지 않습니다. 다시 시도해 주세요."
            "OPERATION_NOT_ALLOWED" -> "지금은 이 기능을 사용할 수 없습니다(서버 설정 확인 필요)."
            else -> "요청을 처리하지 못했습니다(${e.code}). 잠시 후 다시 시도해 주세요."
        }
        return Exception(message, e)
    }
}
