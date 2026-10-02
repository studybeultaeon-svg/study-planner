package com.phonelock.desktop.ui

import com.phonelock.desktop.ui.components.LedgerAlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.monitor.AccountSecurityClient
import com.phonelock.desktop.monitor.AccountSyncClient
import com.phonelock.desktop.monitor.AuthManager
import com.phonelock.desktop.monitor.GoogleDesktopOAuth
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.theme.Spacing
import com.phonelock.shared.auth.AuthPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// 118차: 설정 > 프로필의 "아이디 변경"에서도 같은 검증 규칙을 써야 해서 파일 전용(private)에서 풀었다.
val CUSTOM_ID_REGEX = AuthPolicy.ID_PATTERN
private const val POLL_INTERVAL_MS = 7_000L
/** 폴링 몇 번마다 "다른 기기에서 모든 기기 로그아웃" 여부를 확인할지(7초 × 9 ≈ 1분). */
private const val REVOCATION_CHECK_EVERY = 9

/**
 * "Firebase 콘솔에서 수동으로 uid를 allowedUsers에 등록"하던 화이트리스트 방식을 앱 내부에서 완결되는
 * "가입 신청 → 관리자 승인" 플로우로 대체한 게이트 — 로그인/아이디설정/승인대기 단계를 거쳐야만
 * [content]를 렌더링한다. 안드로이드 쪽도 같은 Firebase 스키마로 동일한 플로우를 구현한다(병렬 세션).
 *
 * 로컬 캐싱(cachedApprovalStatus): 마지막으로 확인된 status가 "approved"였다면, 앱을 다시 켰을 때
 * 네트워크 응답이 오기 전에도 낙관적으로 content()를 먼저 보여주고 백그라운드에서 재확인한다 — 재확인
 * 결과가 approved가 아니면(예: 관리자가 승인취소) 그때 게이트 화면으로 전환한다.
 *
 * 140차(다중 로그인, 안드로이드판과 대칭): 로그인 화면이 "아이디 또는 이메일" 한 칸 + 구글 로그인 + 아이디/비밀번호
 * 찾기로 바뀌었다. 처음 보는 구글 계정은 "새 계정 / 기존 계정에 연결"을 고르게 하고(자동 가입·자동 연결 없음),
 * 다른 기기에서 "모든 기기 로그아웃"을 실행했거나 갱신 토큰이 무효가 되면 이 기기도 로그아웃한다.
 */
@Composable
fun AccountGate(repository: Repository, content: @Composable () -> Unit) {
    var signedIn by remember { mutableStateOf(AuthManager.isSignedIn) }
    // 서버에서 아직 한 번도 확인 못한 상태를 null로 구분해서, cachedApprovalStatus가 "approved"일 때만
    // 낙관적으로 먼저 보여줄지 판단한다.
    var serverStatus by remember { mutableStateOf<String?>(null) }
    var serverChecked by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    // 처음 보는 구글 계정으로 들어왔는지(프로필 없음 + 구글 전용 + 연결 기록 없음) — true면 "새 계정/기존 계정 연결"을 묻는다.
    var needsGoogleChoice by remember { mutableStateOf(false) }
    var pendingGoogle by remember { mutableStateOf<GoogleDesktopOAuth.GoogleIdentity?>(null) }
    var linkAfterLogin by remember { mutableStateOf<GoogleDesktopOAuth.GoogleIdentity?>(null) }
    var foundIdToShow by remember { mutableStateOf<String?>(null) }
    var newDevices by remember { mutableStateOf<List<AccountSecurityClient.Session>>(emptyList()) }

    val apiKey = repository.fbApiKey
    val databaseUrl = repository.fbDatabaseUrl
    val optimisticApproved = repository.cachedApprovalStatus == "approved"

    fun refreshProfile(onDone: (JSONObjectStatus) -> Unit) {
        Thread {
            val result = AccountSyncClient.fetchMyProfile(databaseUrl, apiKey)
            result.onSuccess { profile ->
                // 121차: AccountSyncClient.get()이 네트워크 오류를 예외로 던지게 바뀌면서, 여기로 들어왔다는
                // 것 자체가 "서버가 실제로 돌려준 값"임을 보장한다 — 그전까지는 통신 실패도 status=null로
                // 둘갑해 승인 캐시를 지우고 가입 신청 화면으로 떨어뜨렸다(사실상 강제 로그아웃).
                val status = profile?.optString("status", null)
                if (status == null) {
                    val methods = AuthManager.loginMethods()
                    val choseNew = methods.hasGoogle && AccountSecurityClient.readGoogleIdentitySub(databaseUrl, apiKey).getOrNull() != null
                    needsGoogleChoice = methods.hasGoogle && !methods.hasPassword && !choseNew
                }
                serverStatus = status
                serverChecked = true
                repository.cachedApprovalStatus = status
                if (status == "approved") {
                    val p = AccountSyncClient.Permissions.fromProfile(profile)
                    repository.permRoutine = p.routine
                    repository.permStudy = p.study
                    repository.permManage = p.manage
                    repository.permSocial = p.social
                    repository.permPlant = p.plant
                }
                onDone(JSONObjectStatus(profile, status))
            }.onFailure { e ->
                // 106차 버그 수정: 이미 승인된 상태를 알고 있었다면(캐시든, 이전 폴링에서의 실제 확인이든)
                // 네트워크 오류 하나로 그 상태를 지우지 않는다 — 예전엔 여기서 무조건 serverChecked=true로
                // 만들어서, 불안정한 네트워크에서 폴링이 한 번만 실패해도 "!serverChecked && optimisticApproved
                // -> content()" 낙관적 표시 조건을 벗어나 버렸고, serverStatus는 여전히 null이라 바로 아래
                // "가입 신청" 화면(UsernameStep)으로 떨어져서 마치 로그아웃된 것처럼 보였다. 실제 로그인
                // 세션(AuthManager)은 전혀 건드리지 않았는데도 화면만 로그아웃된 것처럼 보이는 버그였다.
                val hadKnownApproval = serverStatus == "approved" || optimisticApproved
                if (!hadKnownApproval) {
                    serverChecked = true
                }
                onDone(JSONObjectStatus(null, null, e.message))
            }
        }.start()
    }

    fun toLogin(message: String? = null) {
        AuthManager.signOut()
        repository.cachedApprovalStatus = null
        serverStatus = null
        needsGoogleChoice = false
        info = message
        signedIn = false
    }

    /** 어떤 방법으로든 로그인에 성공한 직후(백그라운드 스레드에서 부름) — 보안 기록·기기 목록·아이디 조회표, 기다리던 구글 연결. */
    fun afterSignIn(method: String, showFoundId: Boolean) {
        AccountSecurityClient.afterSignIn(databaseUrl, apiKey, method)
        val key = apiKey
        linkAfterLogin?.let { google ->
            if (key != null) {
                AuthManager.linkGoogle(google, key, databaseUrl)
                    .onSuccess {
                        AccountSecurityClient.appendEvent(databaseUrl, apiKey, AuthPolicy.SecurityEvent.GOOGLE_LINKED, "google")
                        info = "구글 계정을 이 계정에 연결했습니다. 다음부터 구글로도 로그인할 수 있습니다."
                    }
                    .onFailure { e -> info = "로그인은 됐지만 구글 연결에 실패했습니다: ${e.message} 설정 > 로그인 및 보안에서 다시 연결해 주세요." }
            }
            linkAfterLogin = null
        }
        if (showFoundId) {
            val profile = AccountSyncClient.fetchMyProfile(databaseUrl, apiKey).getOrNull()
            foundIdToShow = profile?.optString("customId")?.takeIf { it.isNotBlank() } ?: "(아이디 없음 — 이메일이나 구글로 로그인하는 계정)"
        }
        serverChecked = false
        signedIn = true
    }

    LaunchedEffect(signedIn) {
        if (!signedIn) {
            serverChecked = false
            serverStatus = null
            return@LaunchedEffect
        }
        var tick = 0
        while (true) {
            // 다른 기기에서 비밀번호를 바꾸는 등으로 갱신 토큰이 무효가 되면 AuthManager가 세션을 지운다.
            if (!AuthManager.isSignedIn) {
                toLogin(
                    if (AuthManager.consumeRevokedSignOut()) "로그인이 만료되었습니다(다른 기기에서 비밀번호나 이메일이 바뀌었거나 계정이 정지됨). 다시 로그인해 주세요." else null
                )
                break
            }
            if (tick % REVOCATION_CHECK_EVERY == 0) {
                val revoked = withContext(Dispatchers.IO) { AccountSecurityClient.isSessionRevoked(databaseUrl, apiKey) }
                if (revoked == true) {
                    toLogin("다른 기기에서 '모든 기기 로그아웃'을 실행해 이 기기도 로그아웃되었습니다. 다시 로그인해 주세요.")
                    break
                }
                if (tick == 0 && repository.cachedApprovalStatus == "approved") {
                    newDevices = withContext(Dispatchers.IO) {
                        AccountSecurityClient.pruneOldEvents(databaseUrl, apiKey)
                        AccountSecurityClient.detectNewDevices(databaseUrl, apiKey)
                    }
                }
            }
            refreshProfile { }
            tick++
            delay(POLL_INTERVAL_MS)
        }
    }

    when {
        !signedIn -> LoginStep(
            repository = repository,
            loading = loading,
            error = error,
            info = info,
            linkAfterLoginEmail = linkAfterLogin?.email,
            onCancelLinkAfterLogin = { linkAfterLogin = null },
            onLoadingChange = { loading = it },
            onErrorChange = { error = it; if (it != null) info = null },
            onSignedIn = { method, showFoundId -> error = null; info = null; afterSignIn(method, showFoundId) },
            onGoogleNewAccount = { google ->
                pendingGoogle = google
                needsGoogleChoice = true
                error = null
                info = null
                serverChecked = true
                serverStatus = null
                signedIn = true
            }
        )

        !serverChecked && optimisticApproved -> content()

        !serverChecked -> LoadingStep()

        serverStatus == "approved" -> content()

        serverStatus == null && needsGoogleChoice -> GoogleChoiceStep(
            googleEmail = pendingGoogle?.email ?: AuthManager.linkedGoogleEmail,
            loading = loading,
            error = error,
            onCreateNew = {
                val key = apiKey ?: return@GoogleChoiceStep
                loading = true
                error = null
                Thread {
                    // "새 계정"을 골랐다는 기록 = 이 구글 계정의 연결 기록. 구글 로그인 뒤 15분이 지났으면 규칙이 거절하므로
                    // 구글로 한 번 더 확인받고 다시 쓴다.
                    val google = pendingGoogle
                    var recorded = if (google != null) AccountSecurityClient.setGoogleIdentity(databaseUrl, apiKey, google.sub, google.email)
                    else Result.failure(IllegalStateException("구글 계정 정보를 다시 확인해야 합니다."))
                    if (recorded.isFailure) {
                        recorded = AuthManager.reauthenticateWithGoogle(key).mapCatching { g ->
                            AccountSecurityClient.setGoogleIdentity(databaseUrl, apiKey, g.sub, g.email).getOrThrow()
                        }
                    }
                    recorded.onSuccess {
                        AccountSecurityClient.afterSignIn(databaseUrl, apiKey, "google")
                        AccountSecurityClient.appendEvent(databaseUrl, apiKey, AuthPolicy.SecurityEvent.SIGN_UP, "google")
                        pendingGoogle = null
                        needsGoogleChoice = false
                    }.onFailure { e ->
                        if (e !is GoogleDesktopOAuth.CancelledException) error = e.message ?: "계정을 만들지 못했습니다."
                    }
                    loading = false
                }.start()
            },
            onLinkExisting = {
                val key = apiKey ?: return@GoogleChoiceStep
                loading = true
                Thread {
                    linkAfterLogin = pendingGoogle
                    pendingGoogle = null
                    AuthManager.discardNewGoogleAccount(key)
                    loading = false
                    error = null
                    toLogin(if (linkAfterLogin == null) "기존 계정으로 로그인한 뒤 설정 > 로그인 및 보안에서 구글 계정을 연결해 주세요." else null)
                }.start()
            },
            onCancel = {
                val key = apiKey ?: return@GoogleChoiceStep
                loading = true
                Thread {
                    pendingGoogle = null
                    AuthManager.discardNewGoogleAccount(key)
                    loading = false
                    error = null
                    toLogin()
                }.start()
            }
        )

        serverStatus == null || serverStatus == "rejected" -> UsernameStep(
            repository = repository,
            wasRejected = serverStatus == "rejected",
            onSubmitted = { serverStatus = "pending" },
            onBack = {
                val key = apiKey
                Thread {
                    // 구글로 방금 만든 계정이 가입 신청 전에 돌아가면 지운다(빈 계정이 남지 않게).
                    val methods = AuthManager.loginMethods()
                    if (key != null && methods.hasGoogle && !methods.hasPassword) AuthManager.discardNewGoogleAccount(key)
                    toLogin()
                }.start()
            }
        )

        serverStatus == "pending" -> PendingStep(onSignOut = { toLogin() })

        else -> LoadingStep()
    }

    foundIdToShow?.let { id ->
        LedgerAlertDialog(
            onDismissRequest = { foundIdToShow = null },
            title = { Text("아이디 찾기") },
            text = { Text("이 계정의 아이디는 $id 입니다.") },
            confirmButton = { Button(onClick = { foundIdToShow = null }) { Text("확인") } }
        )
    }

    if (newDevices.isNotEmpty() && signedIn && serverStatus == "approved") {
        NewDeviceAlert(sessions = newDevices, onDismiss = { newDevices = emptyList() })
    }
}

private data class JSONObjectStatus(val profile: org.json.JSONObject?, val status: String?, val error: String? = null)

@Composable
private fun GateScaffold(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 144차: 첫 화면은 브랜드부터(안드로이드판과 같음) — 큰 워드마크 + 작은 라벨, 그 아래 단계 제목(왼쪽 정렬 편집형 머리).
            Column(Modifier.fillMaxWidth()) {
                // 149차: 앱 이름을 "갓생살기종합세트"로 되돌리며 147차 전 워드마크(작은 라벨 + 큰 "갓생")로.
                com.phonelock.desktop.ui.components.Overline("갓생살기종합세트")
                Text("갓생", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(Spacing.md))
                Text(title, style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.height(Spacing.md))
            content()
        }
    }
}

@Composable
private fun LoadingStep() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

private enum class LoginMode { LOGIN, SIGN_UP }

/**
 * 로그인 화면 — "아이디 또는 이메일" 한 칸 + 비밀번호, 구글 로그인, 아이디/비밀번호 찾기, 회원가입/게스트.
 * 같은 기기에서 연속으로 틀리면 잠시 기다리게 한다([AuthPolicy.loginCooldownMs]).
 */
@Composable
private fun LoginStep(
    repository: Repository,
    loading: Boolean,
    error: String?,
    info: String?,
    linkAfterLoginEmail: String?,
    onCancelLinkAfterLogin: () -> Unit,
    onLoadingChange: (Boolean) -> Unit,
    onErrorChange: (String?) -> Unit,
    onSignedIn: (method: String, showFoundId: Boolean) -> Unit,
    onGoogleNewAccount: (GoogleDesktopOAuth.GoogleIdentity) -> Unit
) {
    var mode by remember { mutableStateOf(LoginMode.LOGIN) }
    var identifier by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var showFindId by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }

    val canSignIn = identifier.isNotBlank() && password.length in AuthPolicy.LOGIN_PASSWORD_LENGTH && !loading
    val newPasswordProblem = if (password.isEmpty()) null else AuthPolicy.newPasswordProblem(password, id)
    val canSignUp = CUSTOM_ID_REGEX.matches(id) && password.isNotEmpty() && newPasswordProblem == null && password == passwordConfirm && !loading

    fun requireApiKey(): String? {
        val apiKey = repository.fbApiKey
        if (apiKey.isNullOrBlank()) {
            onErrorChange("Firebase 설정이 비어있습니다.")
            return null
        }
        return apiKey
    }

    fun signIn(input: String, pw: String, showFoundId: Boolean = false, onDone: () -> Unit = {}) {
        val apiKey = requireApiKey() ?: return
        val cooldown = AuthPolicy.loginCooldownMs(AccountSecurityClient.loginFailureCount) -
            (System.currentTimeMillis() - AccountSecurityClient.loginFailureAtMillis)
        if (cooldown > 0) {
            onErrorChange("로그인 시도가 많아 잠시 막았습니다. ${(cooldown + 999) / 1000}초 뒤에 다시 시도해 주세요. 비밀번호가 기억나지 않으면 '비밀번호 찾기'를 이용하세요.")
            return
        }
        onLoadingChange(true)
        onErrorChange(null)
        Thread {
            val result = AuthManager.signInWithIdentifier(repository.fbDatabaseUrl, input, pw, apiKey)
            result.onSuccess { r -> onDone(); onSignedIn(r.method, showFoundId) }
            result.onFailure { e ->
                if (AuthManager.isWrongCredentials(e)) {
                    AccountSecurityClient.loginFailureCount = AccountSecurityClient.loginFailureCount + 1
                    AccountSecurityClient.loginFailureAtMillis = System.currentTimeMillis()
                }
                onErrorChange(e.message ?: "로그인에 실패했습니다.")
            }
            onLoadingChange(false)
        }.start()
    }

    fun googleSignIn(showFoundId: Boolean = false, onDone: () -> Unit = {}) {
        val apiKey = requireApiKey() ?: return
        onLoadingChange(true)
        onErrorChange(null)
        Thread {
            val result = AuthManager.signInWithGoogle(apiKey, repository.fbDatabaseUrl)
            result.onSuccess { outcome ->
                onDone()
                when (outcome) {
                    AuthManager.GoogleSignInOutcome.Existing -> onSignedIn("google", showFoundId)
                    // 아이디 찾기에서 처음 보는 구글 계정이면 새 계정을 만들지 않고 지운 뒤 알려 준다.
                    is AuthManager.GoogleSignInOutcome.NewAccount -> if (showFoundId) {
                        AuthManager.discardNewGoogleAccount(apiKey)
                        onErrorChange("이 구글 계정에 연결된 계정이 없습니다. 이메일로 확인하거나 비밀번호 찾기를 이용해 주세요.")
                    } else {
                        onGoogleNewAccount(outcome.google)
                    }
                }
            }
            result.onFailure { e -> if (e !is GoogleDesktopOAuth.CancelledException) onErrorChange(e.message ?: "구글 로그인에 실패했습니다.") }
            onLoadingChange(false)
        }.start()
    }

    GateScaffold(if (mode == LoginMode.LOGIN) "로그인" else "회원가입") {
        Text(
            "이 앱은 가입 신청 후 관리자 승인이 필요합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        info?.let {
            Spacer(Modifier.height(Spacing.sm))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        }
        linkAfterLoginEmail?.let { email ->
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "로그인하면 구글 계정(${AuthPolicy.maskEmail(email)})이 이 계정에 연결됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            TextButton(onClick = onCancelLinkAfterLogin, enabled = !loading) { Text("구글 연결 취소") }
        }
        Spacer(Modifier.height(Spacing.md))

        when (mode) {
            LoginMode.LOGIN -> {
                OutlinedTextField(
                    value = identifier,
                    onValueChange = { identifier = it; onErrorChange(null) },
                    label = { Text("아이디 또는 이메일") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; onErrorChange(null) },
                    label = { Text("비밀번호") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.md))
                Button(onClick = { signIn(identifier, password) }, enabled = canSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loading) "처리 중..." else "로그인")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { showFindId = true }, enabled = !loading) { Text("아이디 찾기", maxLines = 1, softWrap = false) }
                    TextButton(onClick = { showReset = true }, enabled = !loading) { Text("비밀번호 찾기", maxLines = 1, softWrap = false) }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text("또는", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.sm))
                    HorizontalDivider(Modifier.weight(1f))
                }
                if (AuthManager.isGoogleSignInAvailable) {
                    OutlinedButton(onClick = { googleSignIn() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text("구글로 로그인 (브라우저가 열립니다)")
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
                OutlinedButton(onClick = { mode = LoginMode.SIGN_UP; password = ""; onErrorChange(null) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    Text("회원가입")
                }
                Spacer(Modifier.height(Spacing.sm))
                TextButton(
                    onClick = {
                        val apiKey = requireApiKey() ?: return@TextButton
                        onLoadingChange(true)
                        onErrorChange(null)
                        Thread {
                            val result = AuthManager.signInGuest(apiKey)
                            onLoadingChange(false)
                            result.onSuccess { onSignedIn("guest", false) }
                            result.onFailure { e -> onErrorChange(e.message ?: "게스트 로그인 실패") }
                        }.start()
                    },
                    enabled = !loading
                ) { Text("게스트로 진행") }
            }
            LoginMode.SIGN_UP -> {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it; onErrorChange(null) },
                    label = { Text("아이디 (영문/숫자 3~20자)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; onErrorChange(null) },
                    label = { Text("비밀번호") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text(newPasswordProblem ?: "영문+숫자 ${AuthPolicy.NEW_PASSWORD_MIN}자 이상") },
                    isError = newPasswordProblem != null,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = passwordConfirm,
                    onValueChange = { passwordConfirm = it; onErrorChange(null) },
                    label = { Text("비밀번호 확인") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = passwordConfirm.isNotEmpty() && passwordConfirm != password,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "가입한 뒤 설정 > 로그인 및 보안에서 이메일을 등록하거나 구글 계정을 연결해 두면 비밀번호를 잊어도 계정을 찾을 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.md))
                Button(
                    onClick = {
                        val apiKey = requireApiKey() ?: return@Button
                        onLoadingChange(true)
                        onErrorChange(null)
                        Thread {
                            val result = AuthManager.signUp(id, password, apiKey)
                            result.onSuccess { onSignedIn("id", false) }
                            result.onFailure { e -> onErrorChange(e.message ?: "요청이 실패했습니다.") }
                            onLoadingChange(false)
                        }.start()
                    },
                    enabled = canSignUp,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (loading) "처리 중..." else "회원가입") }
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(onClick = { mode = LoginMode.LOGIN; password = ""; passwordConfirm = ""; onErrorChange(null) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    Text("뒤로")
                }
            }
        }
        if (loading) {
            Spacer(Modifier.height(Spacing.sm))
            CircularProgressIndicator()
        }
        error?.let { msg ->
            Spacer(Modifier.height(Spacing.sm))
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
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

/** 아이디 찾기 — 본인 확인(구글 또는 이메일+비밀번호)을 먼저 하고 나서 아이디를 보여준다. 확인에 성공하면 그대로 로그인된다. */
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
            Column(Modifier.widthIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("본인 확인을 하면 아이디를 알려 드리고 바로 로그인합니다.", style = MaterialTheme.typography.bodyMedium)
                if (AuthManager.isGoogleSignInAvailable) {
                    Text("연결된 구글 계정으로 확인", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = onGoogle, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("구글로 확인") }
                    HorizontalDivider()
                }
                Text("등록한 이메일로 확인", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("이메일") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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

/** 비밀번호 찾기 — 등록된 실제 이메일로 재설정 링크(1회용·1시간). 계정이 있든 없든 **같은 안내**를 보여 준다. */
@Composable
private fun PasswordResetDialog(repository: Repository, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }

    LedgerAlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("비밀번호 찾기") },
        text = {
            Column(Modifier.widthIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("아이디나 이메일을 넣으면 등록된 이메일로 재설정 링크를 보냅니다.", style = MaterialTheme.typography.bodyMedium)
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
                        val apiKey = repository.fbApiKey ?: return@Button
                        val wait = AuthPolicy.EMAIL_RESEND_COOLDOWN_MS - (System.currentTimeMillis() - AccountSecurityClient.emailMailSentAtMillis)
                        if (wait > 0) {
                            message = "방금 요청했습니다. ${(wait + 999) / 1000}초 뒤에 다시 시도해 주세요."
                            return@Button
                        }
                        sending = true
                        Thread {
                            val target: String? = when (val ident = AuthPolicy.parseIdentifier(input)) {
                                is AuthPolicy.LoginIdentifier.Email -> ident.email
                                is AuthPolicy.LoginIdentifier.Id -> AccountSecurityClient.lookupLoginId(repository.fbDatabaseUrl, ident.key)
                                    .getOrNull()?.takeIf { !it.retired }?.email?.takeIf { !AuthPolicy.isSyntheticEmail(it) }
                                AuthPolicy.LoginIdentifier.Invalid -> null
                            }
                            val result = if (target != null) AuthManager.sendPasswordReset(target, apiKey) else Result.success(Unit)
                            sending = false
                            result.onSuccess {
                                AccountSecurityClient.emailMailSentAtMillis = System.currentTimeMillis()
                                sent = true
                                message = "등록된 이메일이 있으면 링크를 보냈습니다. 안 오면 스팸함을 보세요. " +
                                    "이메일이 없는 계정은 구글로 로그인해 설정에서 바꾸세요."
                            }
                            result.onFailure { e -> message = e.message }
                        }.start()
                    }
                ) { Text("재설정 메일 보내기") }
            } else {
                Button(onClick = onDismiss) { Text("확인") }
            }
        },
        dismissButton = { if (!sent) TextButton(onClick = onDismiss, enabled = !sending) { Text("취소") } }
    )
}

/** 처음 보는 구글 계정 — 자동으로 가입시키지도, 이메일이 같다고 기존 계정에 붙이지도 않고 묻는다. */
@Composable
private fun GoogleChoiceStep(
    googleEmail: String?,
    loading: Boolean,
    error: String?,
    onCreateNew: () -> Unit,
    onLinkExisting: () -> Unit,
    onCancel: () -> Unit
) {
    GateScaffold("처음 보는 구글 계정입니다") {
        Text(
            "${googleEmail?.let { AuthPolicy.maskEmail(it) } ?: "이"} 구글 계정에 연결된 계정이 없습니다.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.md))
        Button(onClick = onCreateNew, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("새 계정으로 가입 신청") }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedButton(onClick = onLinkExisting, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("기존 계정에 연결하기") }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "이미 아이디가 있다면 '기존 계정에 연결하기'를 누르세요. 기존 계정으로 로그인하면 이 구글 계정이 연결됩니다. " +
                "이메일이 같아도 자동으로 연결하지 않습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        TextButton(onClick = onCancel, enabled = !loading) { Text("취소") }
        if (loading) CircularProgressIndicator()
        error?.let {
            Spacer(Modifier.height(Spacing.sm))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
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

private fun randomGuestId(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return "GUEST" + (1..6).map { chars.random() }.joinToString("")
}

@Composable
private fun UsernameStep(repository: Repository, wasRejected: Boolean, onSubmitted: () -> Unit, onBack: () -> Unit) {
    // 로그인 아이디가 있으면(게스트가 아니면) 그 아이디를 그대로 가입 신청 아이디로 쓴다 — 사용자가
    // 아이디를 두 번 입력하지 않게 하기 위함(로그인용 아이디와 신청용 아이디를 통합). 게스트는 애초에
    // 로그인 아이디가 없으므로, 입력 자체를 안 시키고 무작위 아이디를 자동 발급한다. 구글로 가입한 계정은
    // 로그인 아이디가 없으므로 아이디를 직접 정한다.
    val isGuest = AuthManager.isAnonymous
    val presetId = AuthManager.currentLoginId ?: "GUEST".takeIf { isGuest }
    var customId by remember { mutableStateOf(presetId ?: "") }
    var nickname by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    GateScaffold("가입 신청") {
        if (wasRejected) {
            Text(
                "이전 신청이 거절되었습니다. 다른 정보로 다시 신청해주세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(Spacing.sm))
        }
        SectionCard("아이디 / 닉네임") {
            if (presetId == null) {
                OutlinedTextField(
                    value = customId,
                    onValueChange = { customId = it },
                    label = { Text("아이디 (영문+숫자 3~20자)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
            }
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("닉네임 (1~20자)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Button(
            onClick = {
                val trimmedNickname = nickname.trim()
                if (!isGuest && !CUSTOM_ID_REGEX.matches(customId.trim().uppercase())) {
                    error = "아이디는 영문+숫자 3~20자여야 합니다."
                    return@Button
                }
                if (trimmedNickname.isEmpty() || trimmedNickname.length > 20) {
                    error = "닉네임은 1~20자여야 합니다."
                    return@Button
                }
                val databaseUrl = repository.fbDatabaseUrl
                val apiKey = repository.fbApiKey
                loading = true
                error = null
                Thread {
                    // 게스트는 화면에 아이디 입력칸이 없어 충돌 시 사용자가 다시 고를 방법이 없으므로,
                    // 무작위 아이디를 몇 번 다시 뽑아서 조용히 재시도한다(충돌 확률은 매우 낮음).
                    var claimResult: Result<Unit> = Result.failure(IllegalStateException("가입 신청에 실패했습니다."))
                    var attempt = 0
                    val maxAttempts = if (isGuest) 5 else 1
                    while (attempt < maxAttempts) {
                        val effectiveId = if (isGuest) randomGuestId() else customId.trim().uppercase()
                        claimResult = if (wasRejected) {
                            AccountSyncClient.resubmit(databaseUrl, apiKey, effectiveId, trimmedNickname)
                        } else {
                            AccountSyncClient.claimUsername(databaseUrl, apiKey, effectiveId).mapCatching {
                                AccountSyncClient.submitProfile(databaseUrl, apiKey, effectiveId, trimmedNickname, isGuest).getOrThrow()
                            }
                        }
                        if (claimResult.isSuccess) {
                            // 아이디 로그인 조회표(loginIds)에 이 아이디를 올린다 — 게스트는 로그인 이메일이 없어 건너뛴다.
                            if (!isGuest) AccountSecurityClient.healLoginId(databaseUrl, apiKey, effectiveId)
                            break
                        }
                        if (!isGuest) break
                        attempt++
                    }
                    loading = false
                    claimResult.onSuccess { onSubmitted() }
                    claimResult.onFailure { e ->
                        error = if (e.message?.contains("이미 사용 중") == true) "이미 사용 중인 아이디입니다." else (e.message ?: "가입 신청 실패")
                    }
                }.start()
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (loading) "처리 중..." else "다음") }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedButton(onClick = onBack, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            Text("이전으로")
        }
        error?.let { msg ->
            Spacer(Modifier.height(Spacing.sm))
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PendingStep(onSignOut: () -> Unit) {
    GateScaffold("승인 대기 중") {
        Text(
            "관리자 승인을 기다리는 중입니다. 승인되면 자동으로 앱이 열립니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
        }
        Spacer(Modifier.height(Spacing.lg))
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("로그아웃 (다른 계정으로 시도)") }
    }
}
