package com.phonelock.desktop.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.monitor.AccountSyncClient
import com.phonelock.desktop.monitor.AuthManager
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.theme.Spacing
import kotlinx.coroutines.delay

private val CUSTOM_ID_REGEX = Regex("^[A-Za-z0-9]{3,20}$")
private const val POLL_INTERVAL_MS = 7_000L

/**
 * "Firebase 콘솔에서 수동으로 uid를 allowedUsers에 등록"하던 화이트리스트 방식을 앱 내부에서 완결되는
 * "가입 신청 → 관리자 승인" 플로우로 대체한 게이트 — 로그인/아이디설정/승인대기 단계를 거쳐야만
 * [content]를 렌더링한다. 안드로이드 쪽도 같은 Firebase 스키마로 동일한 플로우를 구현한다(병렬 세션).
 *
 * 로컬 캐싱(cachedApprovalStatus): 마지막으로 확인된 status가 "approved"였다면, 앱을 다시 켰을 때
 * 네트워크 응답이 오기 전에도 낙관적으로 content()를 먼저 보여주고 백그라운드에서 재확인한다 — 재확인
 * 결과가 approved가 아니면(예: 관리자가 승인취소) 그때 게이트 화면으로 전환한다.
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

    val apiKey = repository.fbApiKey
    val databaseUrl = repository.fbDatabaseUrl
    val optimisticApproved = repository.cachedApprovalStatus == "approved"

    fun refreshProfile(onDone: (JSONObjectStatus) -> Unit) {
        Thread {
            val result = AccountSyncClient.fetchMyProfile(databaseUrl, apiKey)
            result.onSuccess { profile ->
                val status = profile?.optString("status", null)
                serverStatus = status
                serverChecked = true
                repository.cachedApprovalStatus = status
                if (status == "approved") {
                    val p = AccountSyncClient.Permissions.fromProfile(profile)
                    repository.permRoutine = p.routine
                    repository.permStudy = p.study
                    repository.permManage = p.manage
                    repository.permSocial = p.social
                }
                onDone(JSONObjectStatus(profile, status))
            }.onFailure { e ->
                serverChecked = true
                onDone(JSONObjectStatus(null, null, e.message))
            }
        }.start()
    }

    LaunchedEffect(signedIn) {
        if (!signedIn) {
            serverChecked = false
            serverStatus = null
            return@LaunchedEffect
        }
        while (true) {
            refreshProfile { }
            delay(POLL_INTERVAL_MS)
        }
    }

    when {
        !signedIn -> LoginStep(
            repository = repository,
            loading = loading,
            error = error,
            onLoadingChange = { loading = it },
            onErrorChange = { error = it },
            onSignedIn = { signedIn = true }
        )

        !serverChecked && optimisticApproved -> content()

        !serverChecked -> LoadingStep()

        serverStatus == "approved" -> content()

        serverStatus == null || serverStatus == "rejected" -> UsernameStep(
            repository = repository,
            wasRejected = serverStatus == "rejected",
            onSubmitted = { serverStatus = "pending" },
            onBack = {
                AuthManager.signOut()
                repository.cachedApprovalStatus = null
                signedIn = false
            }
        )

        serverStatus == "pending" -> PendingStep(
            onSignOut = {
                AuthManager.signOut()
                repository.cachedApprovalStatus = null
                signedIn = false
            }
        )

        else -> LoadingStep()
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
            Text(title, style = MaterialTheme.typography.headlineSmall)
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

private val LOGIN_ID_REGEX = Regex("^[A-Za-z0-9]{3,20}$")

private enum class LoginMode { PICK, LOGIN, SIGN_UP }

@Composable
private fun LoginStep(
    repository: Repository,
    loading: Boolean,
    error: String?,
    onLoadingChange: (Boolean) -> Unit,
    onErrorChange: (String?) -> Unit,
    onSignedIn: () -> Unit
) {
    var mode by remember { mutableStateOf(LoginMode.PICK) }
    var id by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }

    val idValid = LOGIN_ID_REGEX.matches(id)
    val passwordValid = password.length in 6..50
    val canSignIn = idValid && passwordValid && !loading
    val canSignUp = canSignIn && password == passwordConfirm

    fun requireApiKey(): String? {
        val apiKey = repository.fbApiKey
        if (apiKey.isNullOrBlank()) {
            onErrorChange("Firebase 설정이 비어있습니다.")
            return null
        }
        return apiKey
    }

    GateScaffold("갓생살기종합세트 로그인") {
        Text(
            "이 앱은 가입 신청 후 관리자 승인이 필요합니다. 로그인하거나 게스트로 진행해 가입을 신청하세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.md))

        when (mode) {
            LoginMode.PICK -> {
                Button(onClick = { mode = LoginMode.LOGIN }, modifier = Modifier.fillMaxWidth()) { Text("로그인") }
                Spacer(Modifier.height(Spacing.sm))
                Button(onClick = { mode = LoginMode.SIGN_UP }, modifier = Modifier.fillMaxWidth()) { Text("회원가입") }
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(
                    onClick = {
                        val apiKey = requireApiKey() ?: return@OutlinedButton
                        onLoadingChange(true)
                        onErrorChange(null)
                        Thread {
                            val result = AuthManager.signInGuest(apiKey)
                            onLoadingChange(false)
                            result.onSuccess { onSignedIn() }
                            result.onFailure { e -> onErrorChange(e.message ?: "게스트 로그인 실패") }
                        }.start()
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (loading) "처리 중..." else "게스트로 진행") }
            }
            LoginMode.LOGIN, LoginMode.SIGN_UP -> {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("아이디 (영문/숫자 3~20자)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("비밀번호 (6자 이상)") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (mode == LoginMode.SIGN_UP) {
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedTextField(
                        value = passwordConfirm,
                        onValueChange = { passwordConfirm = it },
                        label = { Text("비밀번호 확인") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(Spacing.md))
                Button(
                    onClick = {
                        val apiKey = requireApiKey() ?: return@Button
                        onLoadingChange(true)
                        onErrorChange(null)
                        Thread {
                            val result = if (mode == LoginMode.SIGN_UP) {
                                AuthManager.signUp(id, password, apiKey)
                            } else {
                                AuthManager.signIn(id, password, apiKey)
                            }
                            onLoadingChange(false)
                            result.onSuccess { onSignedIn() }
                            result.onFailure { e -> onErrorChange(e.message ?: "요청이 실패했습니다.") }
                        }.start()
                    },
                    enabled = if (mode == LoginMode.SIGN_UP) canSignUp else canSignIn,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (loading) "처리 중..." else if (mode == LoginMode.SIGN_UP) "회원가입" else "로그인") }
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(onClick = { mode = LoginMode.PICK }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    Text("뒤로")
                }
            }
        }
        error?.let { msg ->
            Spacer(Modifier.height(Spacing.sm))
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun randomGuestId(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return "GUEST" + (1..6).map { chars.random() }.joinToString("")
}

@Composable
private fun UsernameStep(repository: Repository, wasRejected: Boolean, onSubmitted: () -> Unit, onBack: () -> Unit) {
    // 로그인 아이디가 있으면(게스트가 아니면) 그 아이디를 그대로 가입 신청 아이디로 쓴다 — 사용자가
    // 아이디를 두 번 입력하지 않게 하기 위함(로그인용 아이디와 신청용 아이디를 통합). 게스트는 애초에
    // 로그인 아이디가 없으므로, 입력 자체를 안 시키고 무작위 아이디를 자동 발급한다.
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
                        if (claimResult.isSuccess || !isGuest) break
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
