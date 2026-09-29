package com.phonelock.app.service

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.phonelock.app.BuildConfig
import com.phonelock.shared.auth.AuthPolicy
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

private const val TAG = "AuthManager"

/**
 * 로그인·인증 수단 관리(140차 다중 로그인). **계정(User)은 Firebase uid 하나**이고, 아이디/이메일 비밀번호와
 * 구글 계정은 그 uid에 붙은 로그인 수단이다 — 모델 전체 설명은 [AuthPolicy]와 DECISIONS.md 140차.
 *
 * - 아이디는 72차부터 가짜 이메일(`{아이디}@phonelockapp.local`)로 Firebase에 넣어 왔다. 실제 이메일을 등록하면
 *   Firebase 로그인 이메일이 그 이메일로 바뀌고, 아이디 로그인은 `loginIds`에서 로그인 이메일을 찾아 이어 간다
 *   ([AccountSecurityClient.lookupLoginId]).
 * - 구글 로그인은 **이메일이 같다는 이유로 기존 계정에 자동 연결하지 않는다.** 처음 보는 구글 계정이면
 *   [GoogleSignInOutcome.NewAccount]를 돌려주고 화면이 "새 계정 / 기존 계정에 연결"을 묻는다. Firebase가
 *   (콘솔 설정이 잘못돼) 이메일로 자동 연결해 버린 경우는 [signInWithGoogle]이 되돌린다.
 * - 원문 예외는 로그에만 남기고([friendly]) 토큰·비밀번호·인증 코드는 어디에도 기록하지 않는다.
 */
object AuthManager {

    /** 사용자가 구글 계정 선택 창을 닫았을 때 — 화면은 오류로 표시하지 않는다. */
    class CancelledException : Exception()

    val currentUser: FirebaseUser? get() = FirebaseAuth.getInstance().currentUser

    /**
     * 가짜 이메일에서 되돌린 아이디 — **계정을 방금 만든 직후(가입 신청 화면)에만** 믿을 수 있다. 140차부터는 아이디를
     * 바꿔도 로그인 이메일을 바꾸지 않고, 실제 이메일을 등록하면 가짜 이메일이 사라지므로 화면에 보여줄 아이디는
     * 프로필의 customId를 쓸 것.
     */
    val currentLoginId: String? get() = AuthPolicy.idFromSyntheticEmail(currentUser?.email)

    /** google-services.json에 웹 클라이언트 ID가 있을 때(= 콘솔에서 Google 로그인을 켰을 때)만 true. */
    val isGoogleSignInAvailable: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /** 이 계정에 붙은 로그인 수단 — 마지막 수단 보호·재인증 방법 선택의 기준. */
    fun loginMethods(user: FirebaseUser? = currentUser): AuthPolicy.LoginMethods = AuthPolicy.LoginMethods(
        hasPassword = user?.providerData?.any { it.providerId == EmailAuthProvider.PROVIDER_ID } == true,
        hasGoogle = user?.providerData?.any { it.providerId == GoogleAuthProvider.PROVIDER_ID } == true,
        isAnonymous = user?.isAnonymous == true,
        loginEmail = user?.email?.takeIf { it.isNotBlank() }
    )

    /** 연결된 구글 계정의 이메일. 없으면 null. */
    val linkedGoogleEmail: String?
        get() = currentUser?.providerData?.firstOrNull { it.providerId == GoogleAuthProvider.PROVIDER_ID }?.email

    // ---------------------------------------------------------------- 로그인 / 가입

    /** 로그인 결과 — [method]는 보안 기록용("id"/"email"), [loginIdKey]는 아이디로 들어왔을 때만. */
    data class SignInResult(val user: FirebaseUser, val method: String, val loginIdKey: String?)

    /**
     * "아이디 또는 이메일" 한 칸으로 로그인한다. 아이디면 `loginIds`에서 로그인 이메일을 찾고(없으면 옛 방식의 가짜
     * 이메일), 이메일이면 그대로 쓴다. 계정이 없을 때와 비밀번호가 틀렸을 때 **같은 문장**을 돌려준다.
     */
    suspend fun signInWithIdentifier(databaseUrl: String?, input: String, password: String): Result<SignInResult> {
        return when (val ident = AuthPolicy.parseIdentifier(input)) {
            is AuthPolicy.LoginIdentifier.Email -> signInWithEmail(ident.email, password).map { SignInResult(it, "email", null) }
            is AuthPolicy.LoginIdentifier.Id -> {
                val lookup = AccountSecurityClient.lookupLoginId(databaseUrl, ident.key)
                if (lookup.isFailure) return Result.failure(Exception(NETWORK_MESSAGE, lookup.exceptionOrNull()))
                val record = lookup.getOrNull()
                val candidates = when {
                    record == null -> listOf(AuthPolicy.syntheticEmail(ident.id))
                    else -> record.emailCandidates()
                }
                if (candidates.isEmpty()) return Result.failure(Exception(WRONG_CREDENTIALS_MESSAGE))
                var last: Result<FirebaseUser> = Result.failure(Exception(WRONG_CREDENTIALS_MESSAGE))
                for (email in candidates) {
                    last = signInWithEmail(email, password)
                    if (last.isSuccess || !isWrongCredentials(last.exceptionOrNull())) break
                }
                last.map { SignInResult(it, "id", ident.key) }
            }
            AuthPolicy.LoginIdentifier.Invalid -> Result.failure(Exception("아이디(영문/숫자 3~20자) 또는 이메일 주소를 입력해 주세요."))
        }
    }

    private suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser> = try {
        val user = FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password).await().user
            ?: error("로그인은 성공했지만 사용자 정보를 가져오지 못했습니다.")
        Result.success(user)
    } catch (e: Exception) {
        Result.failure(friendly(e))
    }

    /** 아이디 계정 만들기(가짜 이메일). 아이디 중복은 Firebase 이메일 유일성으로 여기서 걸린다. */
    suspend fun signUp(id: String, password: String): Result<FirebaseUser> = try {
        val user = FirebaseAuth.getInstance()
            .createUserWithEmailAndPassword(AuthPolicy.syntheticEmail(id), password).await().user
            ?: error("회원가입은 성공했지만 사용자 정보를 가져오지 못했습니다.")
        Result.success(user)
    } catch (e: Exception) {
        Result.failure(friendly(e))
    }

    /** 게스트(익명) 로그인 — `currentUser?.isAnonymous`로 게스트 여부를 판별한다. */
    suspend fun signInGuest(): Result<FirebaseUser> = try {
        val user = FirebaseAuth.getInstance().signInAnonymously().await().user
            ?: error("게스트 로그인은 성공했지만 사용자 정보를 가져오지 못했습니다.")
        Result.success(user)
    } catch (e: Exception) {
        Result.failure(friendly(e))
    }

    // ---------------------------------------------------------------- 구글

    /** 구글 계정 선택 창에서 받은 자격 증명 — [sub]는 구글 계정 고유 ID(이메일이 바뀌어도 그대로). */
    class GoogleCredential(val credential: AuthCredential, val sub: String, val email: String?)

    sealed class GoogleSignInOutcome {
        /** 이미 이 앱 계정에 연결된 구글 계정 — 로그인 완료. */
        data class Existing(val user: FirebaseUser) : GoogleSignInOutcome()

        /**
         * 처음 보는 구글 계정 — Firebase가 방금 새 계정을 만들었다. 화면은 "새 계정 만들기 / 기존 계정에 연결 / 취소"를
         * 묻고, 새 계정이 아니면 [discardNewGoogleAccount]로 지운다(자동 가입 방지).
         */
        data class NewAccount(val user: FirebaseUser, val google: GoogleCredential) : GoogleSignInOutcome()
    }

    /**
     * 구글로 로그인한다. 이미 연결된 계정이면 [GoogleSignInOutcome.Existing], 처음이면 [GoogleSignInOutcome.NewAccount].
     *
     * 자동 연결 차단: Firebase 콘솔이 "이메일당 계정 하나" 모드로 남아 있으면, 구글 이메일과 같은 **인증된** 이메일을
     * 쓰는 기존 계정에 Firebase가 구글을 저절로 붙인다. 앱이 직접 연결할 때는 항상 먼저
     * `authMeta/{uid}/identities/google`에 구글 ID를 적어 두므로, 기록이 없는데 구글이 붙어 있으면 자동 연결로 보고
     * 떼어낸 뒤 로그아웃한다(기록을 확인하지 못하면 안전하게 거절한다).
     */
    suspend fun signInWithGoogle(activityContext: Context, databaseUrl: String?, apiKey: String?): Result<GoogleSignInOutcome> {
        return try {
            val google = requestGoogleCredential(activityContext)
            val result = FirebaseAuth.getInstance().signInWithCredential(google.credential).await()
            val user = result.user ?: error("로그인은 성공했지만 사용자 정보를 가져오지 못했습니다.")
            if (result.additionalUserInfo?.isNewUser == true) {
                return Result.success(GoogleSignInOutcome.NewAccount(user, google))
            }
            val recorded = AccountSecurityClient.readGoogleIdentitySub(databaseUrl, apiKey)
            if (recorded.isFailure) {
                signOut(activityContext)
                return Result.failure(Exception("연결 정보를 확인하지 못했습니다. 인터넷 연결을 확인한 뒤 다시 시도해 주세요."))
            }
            if (recorded.getOrNull() != google.sub) {
                Log.w(TAG, "google provider present without app link record — treating as automatic linking")
                AccountSecurityClient.appendEvent(databaseUrl, apiKey, AuthPolicy.SecurityEvent.AUTO_LINK_BLOCKED, method = "google")
                runCatching { user.unlink(GoogleAuthProvider.PROVIDER_ID).await() }
                signOut(activityContext)
                return Result.failure(Exception(AUTO_LINK_BLOCKED_MESSAGE))
            }
            Result.success(GoogleSignInOutcome.Existing(user))
        } catch (e: Exception) {
            Result.failure(friendly(e))
        }
    }

    /**
     * 방금 구글로 새로 만들어진 계정을 지운다("새 계정 만들기"를 고르지 않았을 때). 안전장치: 구글만 붙어 있고
     * 만든 지 30분 안 된 계정만 지운다 — 실수로 기존 계정을 지우지 않게.
     */
    suspend fun discardNewGoogleAccount(context: Context) {
        val user = currentUser
        val created = user?.metadata?.creationTimestamp ?: 0L
        val methods = loginMethods(user)
        if (user != null && methods.hasGoogle && !methods.hasPassword &&
            System.currentTimeMillis() - created < 30 * 60 * 1000L
        ) {
            runCatching { user.delete().await() }.onFailure { Log.w(TAG, "discard new google account failed", it) }
        }
        signOut(context)
    }

    /**
     * 로그인한 계정에 구글을 연결한다. 호출 전에 재인증을 마칠 것([reauthenticateWithPassword] 등). 연결 기록을
     * **먼저** 남긴다 — 기록 없이 연결되면 다음 구글 로그인이 자동 연결로 오인돼 거절되기 때문.
     */
    suspend fun linkGoogle(google: GoogleCredential, databaseUrl: String?, apiKey: String?): Result<Unit> {
        val user = currentUser ?: return Result.failure(IllegalStateException("로그인이 필요합니다."))
        if (loginMethods(user).hasGoogle) return Result.failure(IllegalStateException("이미 구글 계정이 연결돼 있습니다."))
        val record = AccountSecurityClient.setGoogleIdentity(databaseUrl, apiKey, google.sub, google.email)
        if (record.isFailure) return Result.failure(record.exceptionOrNull()!!)
        return try {
            user.linkWithCredential(google.credential).await()
            Result.success(Unit)
        } catch (e: Exception) {
            AccountSecurityClient.clearGoogleIdentity(databaseUrl, apiKey)
            Result.failure(friendly(e))
        }
    }

    /** 구글 계정 선택 창을 띄워 연결할 구글 계정을 받는다(연결 전 확인용). */
    suspend fun pickGoogleAccount(activityContext: Context): Result<GoogleCredential> = try {
        Result.success(requestGoogleCredential(activityContext))
    } catch (e: Exception) {
        Result.failure(friendly(e))
    }

    /**
     * 구글 연결을 해제한다. **마지막 로그인 수단이면 거절**한다 — 화면이 먼저 비밀번호를 정하게 해야 한다.
     * 호출 전에 재인증을 마칠 것.
     */
    suspend fun unlinkGoogle(databaseUrl: String?, apiKey: String?): Result<Unit> {
        val user = currentUser ?: return Result.failure(IllegalStateException("로그인이 필요합니다."))
        if (!loginMethods(user).canUnlinkGoogle) {
            return Result.failure(IllegalStateException("구글이 유일한 로그인 수단이라 연결을 해제할 수 없습니다. 먼저 비밀번호를 설정해 주세요."))
        }
        return try {
            user.unlink(GoogleAuthProvider.PROVIDER_ID).await()
            AccountSecurityClient.clearGoogleIdentity(databaseUrl, apiKey)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendly(e))
        }
    }

    // ---------------------------------------------------------------- 재인증

    /** 마지막으로 이 앱에서 본인 확인(재인증)에 성공한 시각 — [AuthPolicy.REAUTH_VALID_MS] 동안 다시 묻지 않는다. */
    @Volatile
    private var lastReauthAtMillis = 0L

    val isRecentlyReauthenticated: Boolean
        get() = System.currentTimeMillis() - lastReauthAtMillis < AuthPolicy.REAUTH_VALID_MS

    /** 방금 로그인했으면 그 자체가 본인 확인이다 — 로그인 직후 곧바로 이어지는 연결 작업에서 다시 묻지 않게. */
    fun markFreshlyAuthenticated() {
        lastReauthAtMillis = System.currentTimeMillis()
    }

    suspend fun reauthenticateWithPassword(password: String): Result<Unit> {
        val user = currentUser ?: return Result.failure(IllegalStateException("로그인이 필요합니다."))
        val email = user.email ?: return Result.failure(IllegalStateException("비밀번호로 확인할 수 없는 계정입니다."))
        return try {
            user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
            user.getIdToken(true).await()
            markFreshlyAuthenticated()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendly(e, passwordCheck = true))
        }
    }

    /** 구글로 본인 확인 — 이 계정에 연결된 구글 계정과 같은 계정을 골라야 한다. 확인에 쓴 구글 계정 정보를 돌려준다. */
    suspend fun reauthenticateWithGoogle(activityContext: Context): Result<GoogleCredential> {
        val user = currentUser ?: return Result.failure(IllegalStateException("로그인이 필요합니다."))
        return try {
            val google = requestGoogleCredential(activityContext)
            user.reauthenticate(google.credential).await()
            user.getIdToken(true).await()
            markFreshlyAuthenticated()
            Result.success(google)
        } catch (e: Exception) {
            Result.failure(friendly(e))
        }
    }

    /** 현재 로그인 세션이 시작된(또는 마지막으로 재인증한) 시각, 초 단위 — DB 규칙의 `auth.token.auth_time`과 같다. */
    suspend fun authTimeSec(forceRefresh: Boolean = false): Long? =
        runCatching { currentUser?.getIdToken(forceRefresh)?.await()?.authTimestamp }.getOrNull()?.takeIf { it > 0 }

    // ---------------------------------------------------------------- 비밀번호 / 이메일

    /** 비밀번호 변경(호출 전 재인증). Firebase가 다른 기기의 로그인 갱신 토큰을 무효화한다. */
    suspend fun changePassword(newPassword: String): Result<Unit> {
        val user = currentUser ?: return Result.failure(IllegalStateException("로그인이 필요합니다."))
        return try {
            user.updatePassword(newPassword).await()
            user.getIdToken(true).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendly(e))
        }
    }

    /** 구글로만 가입한 계정에 비밀번호를 붙인다 — 이후 로그인 이메일(구글 이메일)과 이 비밀번호로도 로그인된다. */
    suspend fun setPassword(newPassword: String): Result<Unit> {
        val user = currentUser ?: return Result.failure(IllegalStateException("로그인이 필요합니다."))
        val email = user.email ?: return Result.failure(IllegalStateException("로그인 이메일이 없는 계정입니다."))
        return try {
            user.linkWithCredential(EmailAuthProvider.getCredential(email, newPassword)).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendly(e))
        }
    }

    /**
     * 로그인 이메일을 [newEmail]로 바꾸는 인증 메일을 보낸다(호출 전 재인증). 링크를 눌러야 바뀌며, 바뀌는 순간
     * Firebase가 모든 기기의 로그인을 끊고 이전 이메일(실제 이메일이었다면)로 "이메일이 변경됨 · 되돌리기" 메일을 보낸다.
     */
    suspend fun requestEmailChange(newEmail: String): Result<Unit> {
        val user = currentUser ?: return Result.failure(IllegalStateException("로그인이 필요합니다."))
        return try {
            FirebaseAuth.getInstance().setLanguageCode("ko")
            user.verifyBeforeUpdateEmail(AuthPolicy.normalizeEmail(newEmail)).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendly(e))
        }
    }

    /**
     * 비밀번호 재설정 메일. 계정이 없거나 이메일이 달라도 **성공으로 돌려준다**(계정 존재 여부를 알려 주지 않기 위해).
     * 네트워크 오류·요청 과다만 실패로 돌린다.
     */
    suspend fun sendPasswordReset(email: String): Result<Unit> {
        return try {
            FirebaseAuth.getInstance().setLanguageCode("ko")
            FirebaseAuth.getInstance().sendPasswordResetEmail(AuthPolicy.normalizeEmail(email)).await()
            Result.success(Unit)
        } catch (e: FirebaseAuthException) {
            when (e.errorCode) {
                "ERROR_USER_NOT_FOUND", "ERROR_INVALID_EMAIL", "ERROR_USER_DISABLED" -> Result.success(Unit)
                else -> Result.failure(friendly(e))
            }
        } catch (e: Exception) {
            Result.failure(friendly(e))
        }
    }

    /**
     * 서버의 최신 계정 상태를 다시 읽는다(이메일 인증 확인용). 링크를 눌러 이메일이 바뀌었으면 이 기기의 로그인도
     * 끊겨 [SessionEndedException]이 난다 — 화면은 다시 로그인하게 안내한다.
     */
    suspend fun reloadUser(): Result<FirebaseUser> {
        val user = currentUser ?: return Result.failure(SessionEndedException())
        return try {
            user.reload().await()
            Result.success(currentUser ?: throw SessionEndedException())
        } catch (e: FirebaseAuthException) {
            if (e.errorCode in SESSION_ENDED_CODES) Result.failure(SessionEndedException()) else Result.failure(friendly(e))
        } catch (e: SessionEndedException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(friendly(e))
        }
    }

    /** 로그인 세션이 서버에서 끝났다(이메일·비밀번호 변경, 계정 삭제 등) — 다시 로그인해야 한다. */
    class SessionEndedException : Exception("보안을 위해 다시 로그인해야 합니다.")

    // ---------------------------------------------------------------- 로그아웃 / 삭제

    fun signOut() {
        FirebaseAuth.getInstance().signOut()
    }

    /** 로그아웃 + 구글 계정 선택 상태도 지워 다음 구글 로그인 때 계정을 다시 고르게 한다. */
    suspend fun signOut(context: Context) {
        FirebaseAuth.getInstance().signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /** Firebase 계정 자체를 삭제한다 — 호출 전 재인증하고 DB 데이터부터 지울 것(삭제 후엔 이 uid로 인증할 수 없다). */
    suspend fun deleteAccount(): Result<Unit> {
        val user = currentUser ?: return Result.failure(IllegalStateException("로그인이 필요합니다."))
        return try {
            user.delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(friendly(e))
        }
    }

    // ---------------------------------------------------------------- 내부

    /**
     * 구글 계정 선택 창을 띄워 ID 토큰을 받는다. 요청마다 새 nonce를 넣고, 돌아온 토큰의 nonce가 같은지 확인한다
     * (재전송 방지 — 토큰 서명·발급자·대상(aud)·만료는 Firebase가 서버에서 다시 검증한다).
     */
    private suspend fun requestGoogleCredential(activityContext: Context): GoogleCredential {
        check(isGoogleSignInAvailable) { "구글 로그인이 아직 준비되지 않았습니다." }
        val nonce = randomNonce()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).setNonce(nonce).build())
            .build()
        val credential = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            error("구글 계정 정보를 받지 못했습니다. 다시 시도해 주세요.")
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val claims = decodeJwtPayload(idToken) ?: error("구글 계정 정보를 확인하지 못했습니다. 다시 시도해 주세요.")
        if (claims.optString("nonce") != nonce) error("구글 인증 응답이 올바르지 않습니다. 다시 시도해 주세요.")
        val sub = claims.optString("sub").takeIf { it.isNotBlank() } ?: error("구글 계정 정보를 확인하지 못했습니다.")
        return GoogleCredential(
            credential = GoogleAuthProvider.getCredential(idToken, null),
            sub = sub,
            email = claims.optString("email").takeIf { it.isNotBlank() }
        )
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun decodeJwtPayload(jwt: String): JSONObject? = runCatching {
        JSONObject(String(Base64.getUrlDecoder().decode(jwt.split('.')[1]), Charsets.UTF_8))
    }.getOrNull()

    const val WRONG_CREDENTIALS_MESSAGE = "아이디(또는 이메일)나 비밀번호가 올바르지 않습니다."
    private const val NETWORK_MESSAGE = "서버에 연결하지 못했습니다. 인터넷 연결 상태를 확인한 뒤 다시 시도해 주세요."
    private const val AUTO_LINK_BLOCKED_MESSAGE =
        "이 구글 계정은 아직 연결되지 않았습니다. 기존 계정으로 로그인한 뒤 설정 > 로그인 및 보안에서 직접 연결해 주세요."
    private val SESSION_ENDED_CODES = setOf("ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN", "ERROR_USER_NOT_FOUND", "ERROR_USER_DISABLED")
    private val WRONG_CREDENTIAL_CODES = setOf("ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_EMAIL")

    fun isWrongCredentials(e: Throwable?): Boolean =
        e?.message == WRONG_CREDENTIALS_MESSAGE || (e?.cause as? FirebaseAuthException)?.errorCode in WRONG_CREDENTIAL_CODES

    /** Firebase/Credential Manager 예외를 사용자가 읽을 수 있는 문장으로 바꾼다(원문은 로그에만 남긴다). */
    private fun friendly(e: Exception, passwordCheck: Boolean = false): Exception {
        if (e is GetCredentialCancellationException) return CancelledException()
        if (e is IllegalStateException || e is SessionEndedException) return e
        Log.w(TAG, "auth failure: ${(e as? FirebaseAuthException)?.errorCode ?: e.javaClass.simpleName}")
        val message = when (e) {
            is NoCredentialException -> "이 기기에서 쓸 수 있는 구글 계정이 없습니다. 기기 설정에서 구글 계정을 추가한 뒤 다시 시도해 주세요."
            is GetCredentialException -> "구글 계정 선택 창을 열지 못했습니다. 잠시 후 다시 시도해 주세요."
            is FirebaseNetworkException -> NETWORK_MESSAGE
            is FirebaseAuthException -> when (e.errorCode) {
                in WRONG_CREDENTIAL_CODES -> if (passwordCheck) "비밀번호가 올바르지 않습니다." else WRONG_CREDENTIALS_MESSAGE
                "ERROR_EMAIL_ALREADY_IN_USE" -> "이미 사용 중인 아이디 또는 이메일입니다."
                "ERROR_WEAK_PASSWORD" -> "비밀번호가 너무 단순합니다. 영문과 숫자를 섞어 ${AuthPolicy.NEW_PASSWORD_MIN}자 이상으로 정해 주세요."
                "ERROR_USER_DISABLED" -> "사용이 제한된 계정입니다. 관리자에게 문의해 주세요."
                "ERROR_TOO_MANY_REQUESTS" -> "시도가 너무 많아 잠시 막혔습니다. 몇 분 뒤 다시 시도해 주세요."
                "ERROR_REQUIRES_RECENT_LOGIN" -> "보안을 위해 본인 확인이 다시 필요합니다. 다시 시도해 주세요."
                "ERROR_CREDENTIAL_ALREADY_IN_USE" -> "이 구글 계정은 이미 다른 계정에 연결돼 있습니다."
                "ERROR_PROVIDER_ALREADY_LINKED" -> "이미 연결돼 있는 로그인 수단입니다."
                "ERROR_USER_MISMATCH" -> "지금 로그인한 계정에 연결된 구글 계정과 다른 계정을 골랐습니다. 같은 계정을 골라 주세요."
                "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> AUTO_LINK_BLOCKED_MESSAGE
                "ERROR_NO_SUCH_PROVIDER" -> "연결돼 있지 않은 로그인 수단입니다."
                in SESSION_ENDED_CODES -> "보안을 위해 다시 로그인해야 합니다."
                "ERROR_OPERATION_NOT_ALLOWED" -> "지금은 이 기능을 사용할 수 없습니다(서버 설정 확인 필요)."
                else -> "요청을 처리하지 못했습니다(${e.errorCode}). 잠시 후 다시 시도해 주세요."
            }
            else -> e.message?.takeIf { it.isNotBlank() && e is IllegalArgumentException } ?: "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
        }
        return Exception(message, e)
    }
}
