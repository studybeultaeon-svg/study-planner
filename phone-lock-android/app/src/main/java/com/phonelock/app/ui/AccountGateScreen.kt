package com.phonelock.app.ui

import com.phonelock.app.ui.components.LedgerAlertDialog
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.google.firebase.auth.FirebaseAuth
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.service.AccountSecurityClient
import com.phonelock.app.service.AccountSyncClient
import com.phonelock.app.service.AuthManager
import com.phonelock.app.ui.theme.Spacing
import com.phonelock.shared.auth.AuthPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Firebase 콘솔에서 수동으로 whitelist(`allowedUsers`)를 등록하던 방식을 대체하는 "앱 내 가입 → 관리자
 * 승인" 게이트. [content]는 승인된 사용자에게만 보여준다.
 *
 * 오프라인 낙관적 표시: 마지막으로 승인 확인이 됐던 사용자([AppPreferences.cachedApprovalStatus] ==
 * "approved")는 네트워크 확인이 끝나기 전에도 즉시 content()를 보여주고, 백그라운드에서 실제 상태를
 * 재확인한다. 재확인이 "성공했는데 승인 상태가 아님"으로 나온 경우에만 게이트 화면으로 전환한다 — 단순
 * 네트워크 실패로 낙관적 표시를 취소하면 오프라인에서 앱을 못 여는 문제가 생기므로 그 경우는 무시한다.
 *
 * 121차: 위 "단순 네트워크 실패는 무시"가 실제로는 동작하지 않고 있었다.
 * [AccountSyncClient.fetchMyProfile]이 통신 실패까지 `success(null)`("프로필 없음")로 뭉개서 돌려줬기
 * 때문에, Wi-Fi가 잠깐 끊긴 것만으로 `onSuccess` 가지의 `else -> ID_SETUP`을 타고 가입 신청 화면으로
 * 떨어졌고 승인 캐시까지 지워졌다(사실상 강제 로그아웃). `getRaw`가 네트워크 오류를 예외로 던지도록
 * 고쳐서 두 경우가 갈라졌고, 여기서는 그 위에 ① 오프라인이면 재확인 자체를 시도하지 않고 ② 연결이
 * 복구되면([NetworkMonitor.isOnline] 변화) 자동으로 다시 확인하는 두 가지를 더했다. 어느 경로에서도
 * 실제 로그인 세션([AuthManager])은 건드리지 않는다 — 로그아웃은 토큰이 실제로 무효일 때뿐이다.
 *
 * 140차(다중 로그인): 로그인 화면이 "아이디 또는 이메일" 한 칸 + 구글 로그인 + 아이디/비밀번호 찾기로 바뀌었다.
 * 처음 보는 구글 계정은 [GateState.GOOGLE_CHOICE]에서 "새 계정 / 기존 계정에 연결"을 고르게 하고(자동 가입·자동
 * 연결 없음), 다른 기기에서 "모든 기기 로그아웃"을 실행했으면 이 기기도 로그아웃한다(로그아웃 판정은 네트워크
 * 실패가 아니라 서버가 실제로 돌려준 무효화 시각으로만 한다 — 121차 원칙 유지).
 */
@Composable
fun AccountGate(repository: PhoneLockRepository, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context) }

    var state by remember {
        mutableStateOf(
            if (AuthManager.currentUser != null && prefs.cachedApprovalStatus == "approved") {
                GateState.OPTIMISTIC_APPROVED
            } else {
                GateState.CHECKING
            }
        )
    }
    var profileStatus by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    // 처음 보는 구글 계정으로 로그인했을 때 받은 자격 증명 — "기존 계정에 연결"을 고르면 그 계정에 로그인한 뒤 연결한다.
    var pendingGoogle by remember { mutableStateOf<AuthManager.GoogleCredential?>(null) }
    var linkAfterLogin by remember { mutableStateOf<AuthManager.GoogleCredential?>(null) }
    var foundIdToShow by remember { mutableStateOf<String?>(null) }
    var newDevices by remember { mutableStateOf<List<AccountSecurityClient.Session>>(emptyList()) }

    val dbUrl = repository.fbDatabaseUrl
    val apiKey = repository.fbApiKey

    fun cachePermissions(profile: org.json.JSONObject?) {
        val p = AccountSyncClient.Permissions.fromProfile(profile)
        prefs.permRoutine = p.routine
        prefs.permStudy = p.study
        prefs.permManage = p.manage
        prefs.permSocial = p.social
        prefs.permPlant = p.plant
    }

    /** 다른 기기에서 "모든 기기 로그아웃"을 실행했으면 이 기기를 로그아웃시킨다. 확인하지 못하면 아무것도 안 한다. */
    suspend fun signOutIfRevoked(): Boolean {
        if (AccountSecurityClient.isSessionRevoked(dbUrl, apiKey) != true) return false
        AuthManager.signOut(context)
        prefs.cachedApprovalStatus = null
        infoMessage = "다른 기기에서 '모든 기기 로그아웃'을 실행해 이 기기도 로그아웃되었습니다. 다시 로그인해 주세요."
        state = GateState.LOGIN
        return true
    }

    suspend fun refreshFromServer() {
        val user = AuthManager.currentUser
        if (user == null) {
            state = GateState.LOGIN
            return
        }
        if (signOutIfRevoked()) return
        val result = AccountSyncClient.fetchMyProfile(repository.fbDatabaseUrl, repository.fbApiKey)
        result.onSuccess { profile ->
            val status = profile?.optString("status")
            profileStatus = status
            state = when (status) {
                "approved" -> {
                    prefs.cachedApprovalStatus = "approved"
                    cachePermissions(profile)
                    // 과거 버그로 잘못 저장된 isGuest 값을 실제 로그인 방식 기준으로 자동 교정(하위호환).
                    val actualIsGuest = AuthManager.currentUser?.isAnonymous == true
                    if (profile?.optBoolean("isGuest", false) != actualIsGuest) {
                        scope.launch {
                            AccountSyncClient.fixGuestFlagIfNeeded(repository.fbDatabaseUrl, repository.fbApiKey, actualIsGuest)
                        }
                    }
                    GateState.APPROVED
                }
                "pending" -> GateState.PENDING
                "rejected" -> GateState.ID_SETUP_REJECTED
                // 프로필이 없는 구글 전용 계정 = 방금 처음 보는 구글 계정으로 들어왔다. "새 계정"을 고른 적이 있으면
                // 연결 기록(identities/google)이 있으므로 곧장 가입 신청으로, 없으면 먼저 묻는다.
                else -> {
                    val methods = AuthManager.loginMethods()
                    val choseNew = methods.hasGoogle && AccountSecurityClient.readGoogleIdentitySub(dbUrl, apiKey).getOrNull() != null
                    if (methods.hasGoogle && !methods.hasPassword && !choseNew) GateState.GOOGLE_CHOICE else GateState.ID_SETUP
                }
            }
        }.onFailure {
            // 네트워크 오류 등 — 인증 세션은 전혀 건드리지 않는다(121차). 로그인 자체가 풀렸을 때만
            // 로그인 화면으로 돌리고, 이전에 승인이 확인됐던 사용자라면 확인을 못 했어도 그냥 앱을 쓰게 둔다
            // (연결이 돌아오면 아래 LaunchedEffect(online)이 자동으로 다시 확인한다).
            state = when {
                AuthManager.currentUser == null -> GateState.LOGIN
                state == GateState.CHECKING && prefs.cachedApprovalStatus == "approved" -> GateState.OPTIMISTIC_APPROVED
                else -> state
            }
        }
    }

    /** 어떤 방법으로든 로그인에 성공한 직후 — 보안 기록·기기 목록·아이디 조회표를 맞추고, 기다리던 구글 연결을 마친다. */
    fun continueAfterSignIn(method: String, foundIdDialog: Boolean = false) {
        scope.launch {
            loading = true
            AccountSecurityClient.afterSignIn(context, dbUrl, apiKey, method, id = null)
            linkAfterLogin?.let { google ->
                AuthManager.linkGoogle(google, dbUrl, apiKey)
                    .onSuccess {
                        AccountSecurityClient.appendEvent(dbUrl, apiKey, AuthPolicy.SecurityEvent.GOOGLE_LINKED, "google")
                        infoMessage = "구글 계정을 이 계정에 연결했습니다. 다음부터 구글로도 로그인할 수 있습니다."
                    }
                    .onFailure { e -> infoMessage = "로그인은 됐지만 구글 연결에 실패했습니다: ${e.message} 설정 > 로그인 및 보안에서 다시 연결해 주세요." }
                linkAfterLogin = null
            }
            if (foundIdDialog) {
                val profile = AccountSyncClient.fetchMyProfile(dbUrl, apiKey).getOrNull()
                foundIdToShow = profile?.optString("customId")?.takeIf { it.isNotBlank() } ?: "(아이디 없음 — 이메일이나 구글로 로그인하는 계정)"
            }
            loading = false
            state = GateState.CHECKING
            refreshFromServer()
        }
    }

    // 로그아웃·계정 삭제·다른 기기에서의 비밀번호 변경(갱신 토큰 무효화)으로 로그인이 풀리면 곧바로 로그인 화면으로.
    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            if (auth.currentUser == null && state != GateState.LOGIN) {
                prefs.cachedApprovalStatus = null
                state = GateState.LOGIN
            }
        }
        FirebaseAuth.getInstance().addAuthStateListener(listener)
        onDispose { FirebaseAuth.getInstance().removeAuthStateListener(listener) }
    }

    // 121차(사용자 요청 "Wi-Fi가 끊겼다고 로그아웃되지 않게") — 확인이 아직 안 끝난 상태(CHECKING)로
    // 멈춰 있으면 연결이 있는 동안 주기적으로 다시 시도한다. NetworkMonitor.isOnline은 Compose 상태라
    // 인터넷이 돌아오는 순간 이 이펙트가 통째로 다시 돌면서 재동기화가 바로 걸린다.
    val online = com.phonelock.app.service.NetworkMonitor.isOnline
    LaunchedEffect(online, state == GateState.CHECKING) {
        if (!online) return@LaunchedEffect
        while (isActive && state == GateState.CHECKING) {
            refreshFromServer()
            if (state != GateState.CHECKING) break
            delay(RECHECK_RETRY_MS)
        }
    }

    // 낙관적 승인 표시 중에도 백그라운드로 실제 상태를 재확인한다 — 오프라인이면 시도 자체를 건너뛰고,
    // 연결이 복구되면(online 변화) 그때 다시 돌면서 확인한다.
    LaunchedEffect(state == GateState.OPTIMISTIC_APPROVED, online) {
        if (state == GateState.OPTIMISTIC_APPROVED && online) {
            if (signOutIfRevoked()) return@LaunchedEffect
            val result = AccountSyncClient.fetchMyProfile(repository.fbDatabaseUrl, repository.fbApiKey)
            result.onSuccess { profile ->
                val status = profile?.optString("status")
                if (status == "approved") {
                    prefs.cachedApprovalStatus = "approved"
                    cachePermissions(profile)
                    state = GateState.APPROVED
                } else {
                    // 성공적으로 확인했는데 승인 상태가 아님 — 실제로 취소/거절된 것이므로 게이트로 전환.
                    // (121차에 AccountSyncClient.getRaw가 네트워크 오류를 예외로 던지게 바뀌면서, 여기 onSuccess는
                    //  서버가 실제로 돌려준 값이라는 것이 보장된다 — 그전까지는 단순 통신 실패도 여기로 들어왔다.)
                    profileStatus = status
                    prefs.cachedApprovalStatus = null
                    state = when (status) {
                        "pending" -> GateState.PENDING
                        "rejected" -> GateState.ID_SETUP_REJECTED
                        else -> GateState.ID_SETUP
                    }
                }
            }
            // onFailure: 네트워크 문제일 뿐이므로 낙관적 표시를 그대로 유지한다.
        }
    }

    // 앱을 쓰는 동안: 이 기기의 "최근 접속"을 갱신하고 새 기기 로그인을 알린다(시작 시 1회), 이후 5분마다 무효화 확인.
    val inApp = state == GateState.APPROVED || state == GateState.OPTIMISTIC_APPROVED
    LaunchedEffect(inApp, online) {
        if (!inApp || !online) return@LaunchedEffect
        newDevices = AccountSecurityClient.detectNewDevices(context, dbUrl, apiKey)
        AccountSecurityClient.pruneOldEvents(dbUrl, apiKey)
        while (isActive) {
            delay(REVOCATION_CHECK_MS)
            if (signOutIfRevoked()) break
        }
    }

    // 대기 화면 폴링(7초 간격) — profileStatus가 pending인 동안만 돈다.
    LaunchedEffect(state) {
        if (state == GateState.PENDING) {
            while (isActive) {
                delay(7_000)
                if (state != GateState.PENDING) break
                refreshFromServer()
            }
        }
    }

    when (state) {
        GateState.OPTIMISTIC_APPROVED, GateState.APPROVED -> content()
        GateState.CHECKING -> LoadingScreen(
            message = if (online) null else "인터넷 연결을 기다리는 중입니다… 연결이 돌아오면 자동으로 다시 확인합니다."
        )
        GateState.LOGIN -> LoginScreen(
            repository = repository,
            loading = loading,
            errorMessage = errorMessage,
            infoMessage = infoMessage,
            linkAfterLoginEmail = linkAfterLogin?.email,
            onCancelLinkAfterLogin = { linkAfterLogin = null },
            onLoadingChange = { loading = it },
            onError = { errorMessage = it; if (it != null) infoMessage = null },
            onSignedIn = { method, showFoundId ->
                errorMessage = null
                infoMessage = null
                continueAfterSignIn(method, showFoundId)
            },
            onGoogleNewAccount = { google ->
                pendingGoogle = google
                errorMessage = null
                infoMessage = null
                state = GateState.GOOGLE_CHOICE
            },
            onSignUp = { id, password ->
                scope.launch {
                    loading = true
                    errorMessage = null
                    val result = AuthManager.signUp(id, password)
                    result.onSuccess {
                        AccountSecurityClient.afterSignIn(context, dbUrl, apiKey, "id", id = null)
                        state = GateState.CHECKING
                        scope.launch { refreshFromServer() }
                    }
                    result.onFailure { e -> errorMessage = e.message ?: "회원가입에 실패했습니다." }
                    loading = false
                }
            },
            onGuestSignIn = {
                scope.launch {
                    loading = true
                    errorMessage = null
                    val result = AuthManager.signInGuest()
                    result.onSuccess { state = GateState.CHECKING; scope.launch { refreshFromServer() } }
                    result.onFailure { e -> errorMessage = e.message ?: "게스트 로그인에 실패했습니다." }
                    loading = false
                }
            }
        )
        GateState.GOOGLE_CHOICE -> GoogleChoiceScreen(
            googleEmail = pendingGoogle?.email ?: AuthManager.linkedGoogleEmail,
            loading = loading,
            errorMessage = errorMessage,
            onCreateNew = {
                scope.launch {
                    loading = true
                    errorMessage = null
                    // "새 계정"을 골랐다는 기록 = 이 구글 계정의 연결 기록. 구글 로그인 뒤 15분이 지났으면 규칙이
                    // 거절하므로 구글로 한 번 더 확인받고 다시 쓴다.
                    val sub = pendingGoogle?.sub
                    var recorded = if (sub != null) {
                        AccountSecurityClient.setGoogleIdentity(dbUrl, apiKey, sub, pendingGoogle?.email)
                    } else Result.failure(IllegalStateException("구글 계정 정보를 다시 확인해야 합니다."))
                    if (recorded.isFailure) {
                        recorded = AuthManager.reauthenticateWithGoogle(context).mapCatching { g ->
                            AccountSecurityClient.setGoogleIdentity(dbUrl, apiKey, g.sub, g.email).getOrThrow()
                        }
                    }
                    recorded.onSuccess {
                        AccountSecurityClient.afterSignIn(context, dbUrl, apiKey, "google", id = null)
                        AccountSecurityClient.appendEvent(dbUrl, apiKey, AuthPolicy.SecurityEvent.SIGN_UP, "google")
                        pendingGoogle = null
                        state = GateState.ID_SETUP
                    }.onFailure { e ->
                        if (e !is AuthManager.CancelledException) errorMessage = e.message ?: "계정을 만들지 못했습니다."
                    }
                    loading = false
                }
            },
            onLinkExisting = {
                scope.launch {
                    loading = true
                    linkAfterLogin = pendingGoogle
                    pendingGoogle = null
                    AuthManager.discardNewGoogleAccount(context)
                    loading = false
                    errorMessage = null
                    infoMessage = if (linkAfterLogin == null) {
                        "기존 계정으로 로그인한 뒤 설정 > 로그인 및 보안에서 구글 계정을 연결해 주세요."
                    } else null
                    state = GateState.LOGIN
                }
            },
            onCancel = {
                scope.launch {
                    loading = true
                    pendingGoogle = null
                    AuthManager.discardNewGoogleAccount(context)
                    loading = false
                    errorMessage = null
                    state = GateState.LOGIN
                }
            }
        )
        GateState.ID_SETUP, GateState.ID_SETUP_REJECTED -> IdSetupScreen(
            isRejected = state == GateState.ID_SETUP_REJECTED,
            // 로그인 아이디가 있으면(익명/게스트가 아니면) 그 아이디를 그대로 가입 신청 아이디로 쓴다 —
            // 사용자가 아이디를 두 번 입력하지 않게 하기 위함(로그인용 아이디와 신청용 아이디를 통합).
            // 게스트는 애초에 로그인 아이디가 없으므로, 입력 자체를 안 시키고 무작위 아이디를 자동 발급한다.
            // 구글로 가입한 계정은 로그인 아이디가 없으므로 아이디를 직접 정한다(나중에 비밀번호를 정하면 아이디로도 로그인).
            presetId = AuthManager.currentLoginId ?: GUEST_ID_PLACEHOLDER.takeIf { AuthManager.currentUser?.isAnonymous == true },
            loading = loading,
            errorMessage = errorMessage,
            onSubmit = { customId, nickname ->
                scope.launch {
                    loading = true
                    errorMessage = null
                    val isRejected = state == GateState.ID_SETUP_REJECTED
                    val isGuest = AuthManager.currentUser?.isAnonymous == true
                    var result: Result<Unit> = Result.failure(IllegalStateException("가입 신청에 실패했습니다."))
                    // 게스트는 화면에 아이디 입력칸이 없어 충돌 시 사용자가 다시 고를 방법이 없으므로,
                    // 무작위 아이디를 몇 번 다시 뽑아서 조용히 재시도한다(충돌 확률은 매우 낮음).
                    var attempt = 0
                    val maxAttempts = if (isGuest) 5 else 1
                    while (attempt < maxAttempts) {
                        val effectiveId = if (isGuest) randomGuestId() else customId
                        result = if (isRejected) {
                            AccountSyncClient.resubmit(repository.fbDatabaseUrl, repository.fbApiKey, effectiveId, nickname)
                        } else {
                            AccountSyncClient.claimUsername(repository.fbDatabaseUrl, repository.fbApiKey, effectiveId)
                                .mapCatching {
                                    AccountSyncClient.submitProfile(
                                        repository.fbDatabaseUrl, repository.fbApiKey, effectiveId, nickname,
                                        isGuest = isGuest
                                    ).getOrThrow()
                                }
                        }
                        if (result.isSuccess) {
                            // 아이디 로그인 조회표(loginIds)에 이 아이디를 올린다 — 게스트는 로그인 이메일이 없어 건너뛴다.
                            if (!isGuest) AccountSecurityClient.healLoginId(dbUrl, apiKey, effectiveId.uppercase())
                            break
                        }
                        if (!isGuest) break
                        attempt++
                    }
                    result.onSuccess { refreshFromServer() }
                    result.onFailure { e -> errorMessage = e.message ?: "이미 사용 중인 아이디입니다." }
                    loading = false
                }
            },
            onBack = {
                scope.launch {
                    val methods = AuthManager.loginMethods()
                    // 구글로 방금 만든 계정이 가입 신청 전에 돌아가면 지운다(빈 계정이 남지 않게).
                    if (methods.hasGoogle && !methods.hasPassword) AuthManager.discardNewGoogleAccount(context) else AuthManager.signOut(context)
                    errorMessage = null
                    state = GateState.LOGIN
                }
            }
        )
        GateState.PENDING -> PendingScreen(
            onLogout = {
                scope.launch {
                    AuthManager.signOut(context)
                    prefs.cachedApprovalStatus = null
                    state = GateState.LOGIN
                }
            }
        )
    }

    foundIdToShow?.let { id ->
        LedgerAlertDialog(
            onDismissRequest = { foundIdToShow = null },
            title = { Text("아이디 찾기") },
            text = { Text("이 계정의 아이디는 $id 입니다.") },
            confirmButton = { Button(onClick = { foundIdToShow = null }) { Text("확인") } }
        )
    }

    if (newDevices.isNotEmpty() && (state == GateState.APPROVED || state == GateState.OPTIMISTIC_APPROVED)) {
        NewDeviceAlert(sessions = newDevices, onDismiss = { newDevices = emptyList() })
    }
}

private enum class GateState {
    CHECKING, OPTIMISTIC_APPROVED, LOGIN, GOOGLE_CHOICE, ID_SETUP, ID_SETUP_REJECTED, PENDING, APPROVED
}

/** 재시도 간격 — 인터넷이 연결된 채로 서버만 응답하지 않는 경우를 위한 백업 주기
 *  (대부분은 NetworkMonitor.isOnline 변화로 그보다 먼저 반응한다). */
private const val RECHECK_RETRY_MS = 5_000L

/** 앱을 쓰는 동안 "다른 기기에서 모든 기기 로그아웃"을 확인하는 주기. */
private const val REVOCATION_CHECK_MS = 5 * 60 * 1000L

@Composable
private fun LoadingScreen(message: String? = null) {
    Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            CircularProgressIndicator()
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// 118차: 설정 > 프로필의 "아이디 변경"에서도 같은 검증 규칙을 써야 해서 파일 전용(private)에서 풀었다.
val idPattern = AuthPolicy.ID_PATTERN

/** 게스트 아이디 입력칸을 숨기기 위한 자리표시자 — 실제 제출값은 항상 [randomGuestId]로 새로 뽑는다. */
private const val GUEST_ID_PLACEHOLDER = "GUEST"

private fun randomGuestId(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return "GUEST" + (1..6).map { chars.random() }.joinToString("")
}

private enum class LoginMode { LOGIN, SIGN_UP }

/**
 * 로그인 화면 — "아이디 또는 이메일" 한 칸 + 비밀번호, 구글 로그인, 아이디/비밀번호 찾기, 회원가입/게스트.
 * 같은 기기에서 연속으로 틀리면 잠시 기다리게 한다([AuthPolicy.loginCooldownMs]).
 */
@Composable
private fun LoginScreen(
    repository: PhoneLockRepository,
    loading: Boolean,
    errorMessage: String?,
    infoMessage: String?,
    linkAfterLoginEmail: String?,
    onCancelLinkAfterLogin: () -> Unit,
    onLoadingChange: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onSignedIn: (method: String, showFoundId: Boolean) -> Unit,
    onGoogleNewAccount: (AuthManager.GoogleCredential) -> Unit,
    onSignUp: (id: String, password: String) -> Unit,
    onGuestSignIn: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context) }
    var mode by remember { mutableStateOf(LoginMode.LOGIN) }
    var identifier by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var showFindId by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }

    val canSignIn = identifier.isNotBlank() && password.length in AuthPolicy.LOGIN_PASSWORD_LENGTH && !loading
    val newPasswordProblem = if (password.isEmpty()) null else AuthPolicy.newPasswordProblem(password, id)
    val canSignUp = idPattern.matches(id) && password.isNotEmpty() && newPasswordProblem == null && password == passwordConfirm && !loading

    fun signIn(input: String, pw: String, showFoundId: Boolean = false, onDone: () -> Unit = {}) {
        val cooldown = AuthPolicy.loginCooldownMs(prefs.loginFailureCount) - (System.currentTimeMillis() - prefs.loginFailureAtMillis)
        if (cooldown > 0) {
            onError("로그인 시도가 많아 잠시 막았습니다. ${(cooldown + 999) / 1000}초 뒤에 다시 시도해 주세요. 비밀번호가 기억나지 않으면 '비밀번호 찾기'를 이용하세요.")
            return
        }
        scope.launch {
            onLoadingChange(true)
            onError(null)
            val result = AuthManager.signInWithIdentifier(repository.fbDatabaseUrl, input, pw)
            onLoadingChange(false)
            result.onSuccess { r -> onDone(); onSignedIn(r.method, showFoundId) }
            result.onFailure { e ->
                if (AuthManager.isWrongCredentials(e)) {
                    prefs.loginFailureCount = prefs.loginFailureCount + 1
                    prefs.loginFailureAtMillis = System.currentTimeMillis()
                }
                onError(e.message ?: "로그인에 실패했습니다.")
            }
        }
    }

    fun googleSignIn(showFoundId: Boolean = false, onDone: () -> Unit = {}) {
        scope.launch {
            onLoadingChange(true)
            onError(null)
            val result = AuthManager.signInWithGoogle(context, repository.fbDatabaseUrl, repository.fbApiKey)
            onLoadingChange(false)
            result.onSuccess { outcome ->
                onDone()
                when (outcome) {
                    is AuthManager.GoogleSignInOutcome.Existing -> onSignedIn("google", showFoundId)
                    // 아이디 찾기에서 처음 보는 구글 계정이면 새 계정을 만들지 않고 지운 뒤 알려 준다.
                    is AuthManager.GoogleSignInOutcome.NewAccount -> if (showFoundId) {
                        AuthManager.discardNewGoogleAccount(context)
                        onError("이 구글 계정에 연결된 계정이 없습니다. 이메일로 확인하거나 비밀번호 찾기를 이용해 주세요.")
                    } else {
                        onGoogleNewAccount(outcome.google)
                    }
                }
            }
            result.onFailure { e -> if (e !is AuthManager.CancelledException) onError(e.message ?: "구글 로그인에 실패했습니다.") }
        }
    }

    Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // 144차: 첫 화면은 브랜드부터 — 큰 워드마크 + 작은 라벨, 그 아래 로그인/회원가입 제목(왼쪽 정렬 편집형 머리).
            Column(Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
                // 147차: 앱 이름이 "갓생키트"가 되면서 워드마크 하나로(작은 라벨에 같은 이름을 두 번 쓰지 않는다).
                Text("갓생키트", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
                Spacer(Modifier.height(Spacing.md))
                Text(if (mode == LoginMode.LOGIN) "로그인" else "회원가입", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "관리자 승인을 받은 사용자만 앱을 사용할 수 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            infoMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
            }
            linkAfterLoginEmail?.let { email ->
                Text(
                    "로그인하면 구글 계정(${AuthPolicy.maskEmail(email)})이 이 계정에 연결됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = onCancelLinkAfterLogin, enabled = !loading) { Text("구글 연결 취소") }
            }

            when (mode) {
                LoginMode.LOGIN -> {
                    OutlinedTextField(
                        value = identifier,
                        onValueChange = { identifier = it; onError(null) },
                        label = { Text("아이디 또는 이메일") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; onError(null) },
                        label = { Text("비밀번호") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { signIn(identifier, password) }, enabled = canSignIn, modifier = Modifier.fillMaxWidth()) {
                        Text("로그인")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { showFindId = true }, enabled = !loading) { Text("아이디 찾기", maxLines = 1, softWrap = false) }
                        TextButton(onClick = { showReset = true }, enabled = !loading) { Text("비밀번호 찾기", maxLines = 1, softWrap = false) }
                    }
                    OrDivider()
                    if (AuthManager.isGoogleSignInAvailable) {
                        OutlinedButton(onClick = { googleSignIn() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                            Text("구글로 로그인")
                        }
                    }
                    OutlinedButton(onClick = { mode = LoginMode.SIGN_UP; password = ""; onError(null) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text("회원가입")
                    }
                    TextButton(onClick = onGuestSignIn, enabled = !loading) { Text("게스트로 진행") }
                }
                LoginMode.SIGN_UP -> {
                    OutlinedTextField(
                        value = id,
                        onValueChange = { id = it; onError(null) },
                        label = { Text("아이디 (영문/숫자 3~20자)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; onError(null) },
                        label = { Text("비밀번호") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text(newPasswordProblem ?: "영문+숫자 ${AuthPolicy.NEW_PASSWORD_MIN}자 이상") },
                        isError = newPasswordProblem != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = passwordConfirm,
                        onValueChange = { passwordConfirm = it; onError(null) },
                        label = { Text("비밀번호 확인") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = passwordConfirm.isNotEmpty() && passwordConfirm != password,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "가입한 뒤 설정 > 로그인 및 보안에서 이메일을 등록하거나 구글 계정을 연결해 두면 비밀번호를 잊어도 계정을 찾을 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { onSignUp(id, password) }, enabled = canSignUp, modifier = Modifier.fillMaxWidth()) {
                        Text("회원가입")
                    }
                    OutlinedButton(onClick = { mode = LoginMode.LOGIN; password = ""; passwordConfirm = ""; onError(null) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text("뒤로")
                    }
                }
            }

            if (loading) CircularProgressIndicator()
            errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }

    if (showFindId) {
        FindIdDialog(
            loading = loading,
            onGoogle = { googleSignIn(showFoundId = true) { showFindId = false } },
            onEmail = { email, pw -> signIn(email, pw, showFoundId = true) { showFindId = false } },
            onForgotPassword = { showFindId = false; showReset = true },
            onDismiss = { showFindId = false }
        )
    }
    if (showReset) {
        PasswordResetDialog(repository = repository, onDismiss = { showReset = false })
    }
}

@Composable
private fun OrDivider() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text("또는", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.sm))
        HorizontalDivider(Modifier.weight(1f))
    }
}

/**
 * 아이디 찾기 — 본인 확인을 먼저 하고 나서 아이디를 보여준다(계정 존재 여부를 미리 알려 주지 않는다).
 * 구글로 확인하거나, 등록한 이메일과 비밀번호로 확인한다. 확인에 성공하면 그대로 로그인된다.
 */
@Composable
private fun FindIdDialog(
    loading: Boolean,
    onGoogle: () -> Unit,
    onEmail: (email: String, password: String) -> Unit,
    onForgotPassword: () -> Unit,
    onDismiss: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    LedgerAlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("아이디 찾기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("본인 확인을 하면 아이디를 알려 드리고 바로 로그인합니다.", style = MaterialTheme.typography.bodyMedium)
                if (AuthManager.isGoogleSignInAvailable) {
                    Text("연결된 구글 계정으로 확인", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = onGoogle, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("구글로 확인") }
                    HorizontalDivider()
                }
                Text("등록한 이메일로 확인", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, label = { Text("이메일") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("비밀번호") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { onEmail(email, password) },
                    enabled = !loading && AuthPolicy.isValidEmail(email) && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("이메일로 확인") }
                TextButton(onClick = onForgotPassword, enabled = !loading) { Text("비밀번호도 기억나지 않아요") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text("닫기") } }
    )
}

/**
 * 비밀번호 찾기 — 등록된 실제 이메일로 재설정 링크를 보낸다(링크는 1회용·1시간 만료, Firebase가 서버에서 검증).
 * 계정이 있든 없든, 이메일이 등록돼 있든 아니든 **같은 안내**를 보여 준다.
 */
@Composable
private fun PasswordResetDialog(repository: PhoneLockRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }

    LedgerAlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("비밀번호 찾기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "아이디나 이메일을 넣으면 등록된 이메일로 재설정 링크를 보냅니다.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = input, onValueChange = { input = it; message = null }, label = { Text("아이디 또는 이메일") },
                    singleLine = true, enabled = !sent, modifier = Modifier.fillMaxWidth()
                )
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (sending) CircularProgressIndicator()
            }
        },
        confirmButton = {
            if (!sent) {
                Button(
                    enabled = !sending && AuthPolicy.parseIdentifier(input) != AuthPolicy.LoginIdentifier.Invalid,
                    onClick = {
                        val wait = AuthPolicy.EMAIL_RESEND_COOLDOWN_MS - (System.currentTimeMillis() - prefs.emailMailSentAtMillis)
                        if (wait > 0) {
                            message = "방금 요청했습니다. ${(wait + 999) / 1000}초 뒤에 다시 시도해 주세요."
                            return@Button
                        }
                        scope.launch {
                            sending = true
                            val target: String? = when (val ident = AuthPolicy.parseIdentifier(input)) {
                                is AuthPolicy.LoginIdentifier.Email -> ident.email
                                is AuthPolicy.LoginIdentifier.Id -> AccountSecurityClient.lookupLoginId(repository.fbDatabaseUrl, ident.key)
                                    .getOrNull()?.takeIf { !it.retired }?.email?.takeIf { !AuthPolicy.isSyntheticEmail(it) }
                                AuthPolicy.LoginIdentifier.Invalid -> null
                            }
                            val result = if (target != null) AuthManager.sendPasswordReset(target) else Result.success(Unit)
                            sending = false
                            result.onSuccess {
                                prefs.emailMailSentAtMillis = System.currentTimeMillis()
                                sent = true
                                message = "등록된 이메일이 있으면 링크를 보냈습니다. 안 오면 스팸함을 보세요. " +
                                    "이메일이 없는 계정은 구글로 로그인해 설정에서 바꾸세요."
                            }
                            result.onFailure { e -> message = e.message }
                        }
                    }
                ) { Text("재설정 메일 보내기") }
            } else {
                Button(onClick = onDismiss) { Text("확인") }
            }
        },
        dismissButton = { if (!sent) TextButton(onClick = onDismiss, enabled = !sending) { Text("취소") } }
    )
}

/**
 * 처음 보는 구글 계정으로 로그인했을 때 — 자동으로 가입시키지도, 이메일이 같다고 기존 계정에 붙이지도 않고 묻는다.
 * "기존 계정에 연결"은 기존 계정의 아이디/이메일+비밀번호 로그인(본인 확인)을 거쳐야 연결된다.
 */
@Composable
private fun GoogleChoiceScreen(
    googleEmail: String?,
    loading: Boolean,
    errorMessage: String?,
    onCreateNew: () -> Unit,
    onLinkExisting: () -> Unit,
    onCancel: () -> Unit
) {
    Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text("처음 보는 구글 계정입니다", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "${googleEmail?.let { AuthPolicy.maskEmail(it) } ?: "이"} 구글 계정에 연결된 계정이 없습니다.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Button(onClick = onCreateNew, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("새 계정으로 가입 신청") }
            OutlinedButton(onClick = onLinkExisting, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("기존 계정에 연결하기") }
            Text(
                "이미 아이디가 있다면 '기존 계정에 연결하기'를 누르세요. 기존 계정으로 로그인하면 이 구글 계정이 연결됩니다. " +
                    "이메일이 같아도 자동으로 연결하지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            TextButton(onClick = onCancel, enabled = !loading) { Text("취소") }
            if (loading) CircularProgressIndicator()
            errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}

/** 이 기기가 처음 보는 기기에서 로그인이 있었다는 알림 — 본인이 아니면 할 일을 함께 안내한다. */
@Composable
private fun NewDeviceAlert(sessions: List<AccountSecurityClient.Session>, onDismiss: () -> Unit) {
    val formatter = remember { java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.KOREA) }
    LedgerAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 기기에서 로그인") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                sessions.take(3).forEach { s ->
                    Column {
                        Text(s.deviceName.ifBlank { s.platform }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(
                            "${if (s.platform == "desktop") "PC" else "안드로이드"} · ${formatter.format(java.util.Date(s.authTimeSec * 1000))} 로그인",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "본인이 아니면 비밀번호를 바꾸고 다른 기기를 모두 로그아웃하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("확인") } }
    )
}

@Composable
private fun IdSetupScreen(
    isRejected: Boolean,
    presetId: String?,
    loading: Boolean,
    errorMessage: String?,
    onSubmit: (customId: String, nickname: String) -> Unit,
    onBack: () -> Unit
) {
    var customId by remember { mutableStateOf(presetId ?: "") }
    var nickname by remember { mutableStateOf("") }
    val idValid = idPattern.matches(customId)
    val nicknameValid = nickname.length in 1..20
    val canSubmit = idValid && nicknameValid && !loading

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text("가입 신청", style = MaterialTheme.typography.headlineSmall)
        if (isRejected) {
            Text(
                "이전 신청이 거절되었습니다. 다른 정보로 다시 신청해주세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (presetId == null) {
            OutlinedTextField(
                value = customId,
                onValueChange = { customId = it },
                label = { Text("아이디 (영문/숫자 3~20자)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it },
            label = { Text("닉네임 (1~20자)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onSubmit(customId, nickname) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("다음")
        }
        OutlinedButton(
            onClick = onBack,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("이전으로")
        }
        if (loading) CircularProgressIndicator()
        errorMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PendingScreen(onLogout: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            CircularProgressIndicator()
            Text("관리자 승인을 기다리는 중입니다", style = MaterialTheme.typography.titleMedium)
            Text(
                "승인되면 자동으로 화면이 전환됩니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onLogout) { Text("로그아웃") }
        }
    }
}
