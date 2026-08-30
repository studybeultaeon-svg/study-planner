package com.phonelock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.service.AccountSyncClient
import com.phonelock.app.service.AuthManager
import com.phonelock.app.ui.theme.Spacing
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
    var loading by remember { mutableStateOf(false) }

    fun cachePermissions(profile: org.json.JSONObject?) {
        val p = AccountSyncClient.Permissions.fromProfile(profile)
        prefs.permRoutine = p.routine
        prefs.permStudy = p.study
        prefs.permManage = p.manage
        prefs.permSocial = p.social
    }

    suspend fun refreshFromServer() {
        val user = AuthManager.currentUser
        if (user == null) {
            state = GateState.LOGIN
            return
        }
        val result = AccountSyncClient.fetchMyProfile(repository.fbDatabaseUrl, repository.fbApiKey)
        result.onSuccess { profile ->
            val status = profile?.optString("status")
            profileStatus = status
            state = when (status) {
                "approved" -> {
                    prefs.cachedApprovalStatus = "approved"
                    cachePermissions(profile)
                    GateState.APPROVED
                }
                "pending" -> GateState.PENDING
                "rejected" -> GateState.ID_SETUP_REJECTED
                else -> GateState.ID_SETUP
            }
        }.onFailure {
            // 네트워크 오류 등 — 이미 낙관적으로 승인 화면을 보여주고 있었다면 그대로 유지한다.
            if (state != GateState.OPTIMISTIC_APPROVED) {
                state = GateState.LOGIN.takeIf { AuthManager.currentUser == null } ?: state
            }
        }
    }

    LaunchedEffect(Unit) {
        if (state == GateState.CHECKING) {
            refreshFromServer()
        }
    }

    // 낙관적 승인 표시 중에도 백그라운드로 실제 상태를 재확인한다.
    LaunchedEffect(state == GateState.OPTIMISTIC_APPROVED) {
        if (state == GateState.OPTIMISTIC_APPROVED) {
            val result = AccountSyncClient.fetchMyProfile(repository.fbDatabaseUrl, repository.fbApiKey)
            result.onSuccess { profile ->
                val status = profile?.optString("status")
                if (status == "approved") {
                    prefs.cachedApprovalStatus = "approved"
                    cachePermissions(profile)
                    state = GateState.APPROVED
                } else {
                    // 성공적으로 확인했는데 승인 상태가 아님 — 실제로 취소/거절된 것이므로 게이트로 전환.
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
        GateState.CHECKING -> LoadingScreen()
        GateState.LOGIN -> LoginScreen(
            loading = loading,
            errorMessage = errorMessage,
            onSignIn = { id, password ->
                scope.launch {
                    loading = true
                    errorMessage = null
                    val result = AuthManager.signIn(id, password)
                    result.onSuccess { state = GateState.CHECKING; scope.launch { refreshFromServer() } }
                    result.onFailure { e -> errorMessage = e.message ?: "로그인에 실패했습니다." }
                    loading = false
                }
            },
            onSignUp = { id, password ->
                scope.launch {
                    loading = true
                    errorMessage = null
                    val result = AuthManager.signUp(id, password)
                    result.onSuccess { state = GateState.CHECKING; scope.launch { refreshFromServer() } }
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
        GateState.ID_SETUP, GateState.ID_SETUP_REJECTED -> IdSetupScreen(
            isRejected = state == GateState.ID_SETUP_REJECTED,
            // 로그인 아이디가 있으면(익명/게스트가 아니면) 그 아이디를 그대로 가입 신청 아이디로 쓴다 —
            // 사용자가 아이디를 두 번 입력하지 않게 하기 위함(로그인용 아이디와 신청용 아이디를 통합).
            // 게스트는 애초에 로그인 아이디가 없으므로, 입력 자체를 안 시키고 무작위 아이디를 자동 발급한다.
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
                                        isGuest = true
                                    ).getOrThrow()
                                }
                        }
                        if (result.isSuccess || !isGuest) break
                        attempt++
                    }
                    result.onSuccess { refreshFromServer() }
                    result.onFailure { e -> errorMessage = e.message ?: "이미 사용 중인 아이디입니다." }
                    loading = false
                }
            },
            onBack = {
                AuthManager.signOut()
                errorMessage = null
                state = GateState.LOGIN
            }
        )
        GateState.PENDING -> PendingScreen(
            onLogout = {
                AuthManager.signOut()
                prefs.cachedApprovalStatus = null
                state = GateState.LOGIN
            }
        )
    }
}

private enum class GateState {
    CHECKING, OPTIMISTIC_APPROVED, LOGIN, ID_SETUP, ID_SETUP_REJECTED, PENDING, APPROVED
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

private val idPattern = Regex("^[A-Za-z0-9]{3,20}$")

/** 게스트 아이디 입력칸을 숨기기 위한 자리표시자 — 실제 제출값은 항상 [randomGuestId]로 새로 뽑는다. */
private const val GUEST_ID_PLACEHOLDER = "GUEST"

private fun randomGuestId(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return "GUEST" + (1..6).map { chars.random() }.joinToString("")
}

private enum class LoginMode { PICK, LOGIN, SIGN_UP }

@Composable
private fun LoginScreen(
    loading: Boolean,
    errorMessage: String?,
    onSignIn: (id: String, password: String) -> Unit,
    onSignUp: (id: String, password: String) -> Unit,
    onGuestSignIn: () -> Unit
) {
    var mode by remember { mutableStateOf(LoginMode.PICK) }
    var id by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }

    val idValid = idPattern.matches(id)
    val passwordValid = password.length in 6..50
    val canSignIn = idValid && passwordValid && !loading
    val canSignUp = canSignIn && password == passwordConfirm

    Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text("로그인이 필요합니다", style = MaterialTheme.typography.headlineSmall)
            Text(
                "관리자 승인을 받은 사용자만 앱을 사용할 수 있습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when (mode) {
                LoginMode.PICK -> {
                    Button(onClick = { mode = LoginMode.LOGIN }, modifier = Modifier.fillMaxWidth()) {
                        Text("로그인")
                    }
                    Button(onClick = { mode = LoginMode.SIGN_UP }, modifier = Modifier.fillMaxWidth()) {
                        Text("회원가입")
                    }
                    Button(onClick = onGuestSignIn, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text("게스트로 진행")
                    }
                }
                LoginMode.LOGIN, LoginMode.SIGN_UP -> {
                    OutlinedTextField(
                        value = id,
                        onValueChange = { id = it },
                        label = { Text("아이디 (영문/숫자 3~20자)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("비밀번호 (6자 이상)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (mode == LoginMode.SIGN_UP) {
                        OutlinedTextField(
                            value = passwordConfirm,
                            onValueChange = { passwordConfirm = it },
                            label = { Text("비밀번호 확인") },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { onSignUp(id, password) },
                            enabled = canSignUp,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("회원가입") }
                    } else {
                        Button(
                            onClick = { onSignIn(id, password) },
                            enabled = canSignIn,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("로그인") }
                    }
                    Button(onClick = { mode = LoginMode.PICK }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text("뒤로")
                    }
                }
            }

            if (loading) CircularProgressIndicator()
            errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
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
        androidx.compose.material3.OutlinedButton(
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
