package com.phonelock.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.service.ADMIN_USERNAME
import com.phonelock.app.service.AccountSecurityClient
import com.phonelock.app.service.AccountSyncClient
import com.phonelock.app.service.AuthManager
import com.phonelock.app.service.ChatSyncClient
import com.phonelock.app.ui.components.SectionCard
import com.phonelock.app.ui.theme.Spacing
import com.phonelock.shared.auth.AuthPolicy
import com.phonelock.shared.auth.AuthPolicy.SecurityEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SecurityDialog { CHANGE_ID, EMAIL, CHANGE_PASSWORD, SET_PASSWORD, SET_PASSWORD_THEN_UNLINK, UNLINK_GOOGLE, LAST_METHOD, REVOKE, DELETE, GUEST_LOGOUT }

/**
 * 설정 > 프로필 > "로그인 및 보안"(140차 다중 로그인). 하나의 계정에 붙은 로그인 수단(아이디 · 이메일 · 비밀번호 ·
 * 구글)을 한눈에 보여 주고, 바꾸는 작업은 모두 **본인 확인(재인증)을 먼저** 거친다. 마지막 로그인 수단은 뗄 수
 * 없고, 비밀번호 변경·구글 연결 해제·"다른 기기 모두 로그아웃"은 다른 기기의 로그인을 끊는다.
 *
 * 흐름의 근거는 DECISIONS.md 140차, 판정 규칙은 [AuthPolicy], 서버 쪽은 [AccountSecurityClient].
 */
@Composable
fun AccountSecuritySection(prefs: AppPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dbUrl = prefs.fbDatabaseUrl
    val apiKey = prefs.fbApiKey

    var refreshTick by remember { mutableIntStateOf(0) }
    val user = AuthManager.currentUser
    var methods by remember(user?.uid) { mutableStateOf(AuthManager.loginMethods()) }
    var customId by remember { mutableStateOf<String?>(null) }
    var loginRecord by remember { mutableStateOf<AuthPolicy.LoginIdRecord?>(null) }
    var sessions by remember { mutableStateOf<List<AccountSecurityClient.Session>?>(null) }
    var events by remember { mutableStateOf<List<AccountSecurityClient.Event>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<SecurityDialog?>(null) }
    var pendingReauthAction by remember { mutableStateOf<(suspend () -> Unit)?>(null) }

    LaunchedEffect(refreshTick, user?.uid) {
        if (user == null) return@LaunchedEffect
        runCatching { user.reload().await() }
        methods = AuthManager.loginMethods()
        customId = AccountSecurityClient.resolveMyId(dbUrl, apiKey)
        loginRecord = customId?.let { AccountSecurityClient.lookupLoginId(dbUrl, AuthPolicy.loginIdKey(it)).getOrNull() }
            ?.takeIf { it.uid == user.uid }
        sessions = AccountSecurityClient.readSessions(dbUrl, apiKey).getOrNull()
        events = AccountSecurityClient.readRecentEvents(dbUrl, apiKey, limit = 15).getOrNull()
    }

    fun refresh() { refreshTick++ }

    /** 민감한 작업 — 최근(4분 안)에 본인 확인을 했으면 바로, 아니면 재인증 대화상자를 거친 뒤 실행한다. */
    fun withReauth(action: suspend () -> Unit) {
        if (!methods.canReauthenticate || AuthManager.isRecentlyReauthenticated) {
            scope.launch { action() }
        } else {
            pendingReauthAction = action
        }
    }

    fun runBusy(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = null
            try { block() } finally { busy = false; refresh() }
        }
    }

    val pendingEmail = (loginRecord?.pendingEmail ?: prefs.pendingEmailChange)
        ?.takeIf { !it.equals(user?.email, ignoreCase = true) }

    SectionCard("로그인 및 보안") {
        if (user == null) {
            Text("로그아웃되었습니다.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        if (methods.isAnonymous) {
            Text(
                "게스트로 로그인되어 있습니다. 게스트 계정은 로그아웃하거나 앱을 지우면 다시 들어올 수 없습니다. " +
                    "구글 계정을 연결해 두면 다른 기기에서도 같은 계정으로 들어올 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (AuthManager.isGoogleSignInAvailable) {
                Spacer(Modifier.height(Spacing.sm))
                Button(onClick = {
                    runBusy {
                        val google = AuthManager.pickGoogleAccount(context).getOrElse { e ->
                            if (e !is AuthManager.CancelledException) message = e.message
                            return@runBusy
                        }
                        AuthManager.linkGoogle(google, dbUrl, apiKey)
                            .onSuccess {
                                AccountSecurityClient.appendEvent(dbUrl, apiKey, SecurityEvent.GOOGLE_LINKED, "google")
                                message = "구글 계정을 연결했습니다. 이제 구글로 로그인할 수 있습니다."
                            }
                            .onFailure { e -> message = e.message }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("구글 계정 연결") }
            }
        } else {
            val isAdminAccount = customId.equals(ADMIN_USERNAME, ignoreCase = true)
            SecurityRow(
                label = "아이디",
                value = customId ?: "없음",
                action = if (customId != null && !isAdminAccount) "변경" else null,
                enabled = !busy
            ) { dialog = SecurityDialog.CHANGE_ID }
            SecurityRow(
                label = "이메일",
                value = if (methods.hasRealEmail) user.email.orEmpty() else "등록 안 됨",
                action = if (methods.hasRealEmail) "변경" else "등록",
                enabled = !busy && (customId != null || methods.hasRealEmail)
            ) { dialog = SecurityDialog.EMAIL }
            pendingEmail?.let { pending ->
                Text(
                    "인증 대기 중: $pending — 메일의 링크를 누른 뒤 '인증 확인'을 눌러 주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TextButton(enabled = !busy, onClick = {
                        runBusy {
                            AuthManager.reloadUser()
                                .onSuccess { u ->
                                    if (u.email.equals(pending, ignoreCase = true)) {
                                        customId?.let { AccountSecurityClient.healLoginId(dbUrl, apiKey, it) }
                                        prefs.pendingEmailChange = null
                                        AccountSecurityClient.appendEvent(dbUrl, apiKey, SecurityEvent.EMAIL_CHANGED)
                                        message = "이메일이 $pending(으)로 바뀌었습니다."
                                    } else {
                                        message = "아직 인증되지 않았습니다. 메일이 없으면 스팸함을 확인하거나 다시 보내기를 눌러 주세요."
                                    }
                                }
                                .onFailure { e ->
                                    if (e is AuthManager.SessionEndedException) {
                                        Toast.makeText(context, "이메일이 변경되어 보안을 위해 다시 로그인해야 합니다. 새 이메일이나 아이디로 로그인해 주세요.", Toast.LENGTH_LONG).show()
                                        AuthManager.signOut(context)
                                    } else message = e.message
                                }
                        }
                    }) { Text("인증 확인", maxLines = 1, softWrap = false) }
                    TextButton(enabled = !busy, onClick = {
                        val wait = AuthPolicy.EMAIL_RESEND_COOLDOWN_MS - (System.currentTimeMillis() - prefs.emailMailSentAtMillis)
                        if (wait > 0) { message = "${(wait + 999) / 1000}초 뒤에 다시 보낼 수 있습니다."; return@TextButton }
                        withReauth {
                            runBusy {
                                AuthManager.requestEmailChange(pending)
                                    .onSuccess { prefs.emailMailSentAtMillis = System.currentTimeMillis(); message = "인증 메일을 다시 보냈습니다." }
                                    .onFailure { e -> message = e.message }
                            }
                        }
                    }) { Text("다시 보내기", maxLines = 1, softWrap = false) }
                    TextButton(enabled = !busy, onClick = {
                        runBusy {
                            prefs.pendingEmailChange = null
                            loginRecord?.let { r -> AccountSecurityClient.writeLoginId(dbUrl, apiKey, AuthPolicy.loginIdKey(r.id), r.copy(pendingEmail = null)) }
                            message = "변경 요청을 취소했습니다. 이미 받은 인증 메일의 링크는 누르지 마세요."
                        }
                    }) { Text("취소", maxLines = 1, softWrap = false) }
                }
            }
            SecurityRow(
                label = "비밀번호",
                value = if (methods.hasPassword) "••••••••" else "설정 안 됨",
                action = if (methods.hasPassword) "변경" else if (methods.canSetPassword) "설정" else null,
                enabled = !busy
            ) { dialog = if (methods.hasPassword) SecurityDialog.CHANGE_PASSWORD else SecurityDialog.SET_PASSWORD }
            if (AuthManager.isGoogleSignInAvailable || methods.hasGoogle) {
                SecurityRow(
                    label = "구글",
                    value = if (methods.hasGoogle) "연결됨 · ${AuthManager.linkedGoogleEmail?.let { AuthPolicy.maskEmail(it) } ?: ""}" else "연결 안 됨",
                    action = if (methods.hasGoogle) "연결 해제" else if (AuthManager.isGoogleSignInAvailable) "연결" else null,
                    enabled = !busy
                ) {
                    if (methods.hasGoogle) {
                        dialog = if (methods.canUnlinkGoogle) SecurityDialog.UNLINK_GOOGLE else SecurityDialog.LAST_METHOD
                    } else {
                        withReauth {
                            runBusy {
                                val google = AuthManager.pickGoogleAccount(context).getOrElse { e ->
                                    if (e !is AuthManager.CancelledException) message = e.message
                                    return@runBusy
                                }
                                AuthManager.linkGoogle(google, dbUrl, apiKey)
                                    .onSuccess {
                                        AccountSecurityClient.appendEvent(dbUrl, apiKey, SecurityEvent.GOOGLE_LINKED, "google")
                                        message = "구글 계정을 연결했습니다. 이제 구글로도 로그인할 수 있습니다."
                                    }
                                    .onFailure { e -> message = e.message }
                            }
                        }
                    }
                }
            }
            if (!methods.hasRealEmail && !methods.hasGoogle) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "지금은 비밀번호를 잊으면 계정을 찾을 방법이 없습니다. 이메일을 등록하거나 구글 계정을 연결해 두세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (busy) {
            Spacer(Modifier.height(Spacing.sm))
            CircularProgressIndicator()
        }
        message?.let {
            Spacer(Modifier.height(Spacing.sm))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (user != null) {
        Spacer(Modifier.height(Spacing.md))
        SectionCard("로그인된 기기") {
            val list = sessions
            when {
                list == null -> Text("목록을 불러오지 못했습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.isEmpty() -> Text("기록된 기기가 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> list.take(8).forEach { s ->
                    val isThis = s.id == prefs.authInstallId
                    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                        Text(
                            (s.deviceName.ifBlank { platformLabel(s.platform) }) + if (isThis) " (이 기기)" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${platformLabel(s.platform)} · 로그인 ${formatTime(s.authTimeSec * 1000)} · 최근 접속 ${formatTime(s.lastSeenMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (methods.canReauthenticate) {
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(onClick = { dialog = SecurityDialog.REVOKE }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("다른 기기 모두 로그아웃")
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard("최근 보안 활동") {
            val list = events
            if (list.isNullOrEmpty()) {
                Text("기록이 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                list.forEach { e ->
                    val parts = listOfNotNull(
                        AuthPolicy.methodLabel(e.method).takeIf { it.isNotBlank() },
                        e.deviceName ?: e.platform?.let { platformLabel(it) }
                    )
                    Text(
                        "${formatTime(e.atMillis)} · ${e.type?.label ?: e.rawType}" + if (parts.isEmpty()) "" else " (${parts.joinToString(" · ")})",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (e.type == SecurityEvent.AUTO_LINK_BLOCKED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
            Text(
                "보안 기록은 90일 동안 보관합니다. 모르는 활동이 있으면 비밀번호를 바꾸고 다른 기기를 모두 로그아웃하세요.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard("계정") {
            Button(
                onClick = {
                    if (methods.usableCount == 0) {
                        dialog = SecurityDialog.GUEST_LOGOUT
                    } else {
                        scope.launch {
                            AccountSecurityClient.removeSession(dbUrl, apiKey, prefs.authInstallId)
                            AuthManager.signOut(context)
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("로그아웃") }
            Spacer(Modifier.height(Spacing.sm))
            Button(
                onClick = { dialog = SecurityDialog.DELETE },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) { Text("계정 삭제") }
        }
    }

    // ---------------------------------------------------------------- 대화상자

    pendingReauthAction?.let { action ->
        ReauthDialog(
            methods = methods,
            onVerified = { pendingReauthAction = null; scope.launch { action() } },
            // 본인 확인을 취소하면 그 작업을 기다리던 대화상자도 닫는다(진행 중 표시가 남지 않게).
            onDismiss = { pendingReauthAction = null; dialog = null }
        )
    }

    when (dialog) {
        SecurityDialog.CHANGE_ID -> ChangeIdDialog(
            currentId = customId.orEmpty(),
            onDismiss = { dialog = null },
            onSubmit = { newId, done ->
                withReauth {
                    runBusy {
                        val result = changeMyId(dbUrl, apiKey, customId.orEmpty(), newId)
                        result.onSuccess { id -> customId = id; message = "아이디가 $id(으)로 바뀌었습니다."; dialog = null }
                        done(result.exceptionOrNull()?.message)
                    }
                }
            }
        )
        SecurityDialog.EMAIL -> EmailChangeDialog(
            currentEmail = user?.email?.takeIf { methods.hasRealEmail },
            onDismiss = { dialog = null },
            onSubmit = { newEmail, done ->
                withReauth {
                    runBusy {
                        val result = requestEmailChange(prefs, dbUrl, apiKey, customId, newEmail)
                        result.onSuccess {
                            dialog = null
                            message = "$newEmail(으)로 인증 메일을 보냈습니다. 메일의 링크를 누르면 변경이 끝나고, 보안을 위해 모든 기기에서 " +
                                "다시 로그인하게 됩니다." + if (methods.hasRealEmail) " 이전 이메일로도 변경 알림이 갑니다." else ""
                        }
                        done(result.exceptionOrNull()?.message)
                    }
                }
            }
        )
        SecurityDialog.CHANGE_PASSWORD, SecurityDialog.SET_PASSWORD, SecurityDialog.SET_PASSWORD_THEN_UNLINK -> {
            val setting = dialog != SecurityDialog.CHANGE_PASSWORD
            val thenUnlink = dialog == SecurityDialog.SET_PASSWORD_THEN_UNLINK
            NewPasswordDialog(
                title = if (setting) "비밀번호 설정" else "비밀번호 변경",
                note = when {
                    thenUnlink -> "비밀번호를 정한 뒤 구글 연결을 해제합니다. 이후 ${user?.email.orEmpty()} 또는 아이디와 이 비밀번호로 로그인합니다."
                    setting -> "비밀번호를 정하면 ${user?.email.orEmpty()}${if (customId != null) " 또는 아이디" else ""}와 이 비밀번호로도 로그인할 수 있습니다."
                    else -> "비밀번호를 바꾸면 보안을 위해 다른 기기는 모두 로그아웃됩니다."
                },
                id = customId,
                onDismiss = { dialog = null },
                onSubmit = { pw, done ->
                    withReauth {
                        runBusy {
                            val result = if (setting) AuthManager.setPassword(pw) else AuthManager.changePassword(pw)
                            result.onSuccess {
                                AccountSecurityClient.appendEvent(dbUrl, apiKey, if (setting) SecurityEvent.PASSWORD_SET else SecurityEvent.PASSWORD_CHANGED)
                                if (!setting) AccountSecurityClient.revokeOtherSessions(dbUrl, apiKey)
                                message = if (setting) "비밀번호를 설정했습니다." else "비밀번호를 바꿨습니다. 다른 기기는 로그아웃됩니다."
                                dialog = null
                                if (thenUnlink) {
                                    AuthManager.unlinkGoogle(dbUrl, apiKey)
                                        .onSuccess {
                                            AccountSecurityClient.appendEvent(dbUrl, apiKey, SecurityEvent.GOOGLE_UNLINKED)
                                            AccountSecurityClient.revokeOtherSessions(dbUrl, apiKey)
                                            message = "비밀번호를 설정하고 구글 연결을 해제했습니다."
                                        }
                                        .onFailure { e -> message = "비밀번호는 설정했지만 구글 연결 해제에 실패했습니다: ${e.message}" }
                                }
                            }
                            done(result.exceptionOrNull()?.message)
                        }
                    }
                }
            )
        }
        SecurityDialog.LAST_METHOD -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("구글 연결을 해제할 수 없습니다") },
            text = {
                Text(
                    "지금은 구글이 이 계정의 유일한 로그인 수단입니다. 이대로 연결을 해제하면 계정에 들어올 수 없게 됩니다. " +
                        "먼저 비밀번호를 정하면 연결을 해제할 수 있습니다."
                )
            },
            confirmButton = { Button(onClick = { dialog = SecurityDialog.SET_PASSWORD_THEN_UNLINK }) { Text("비밀번호 설정 후 해제") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("취소") } }
        )
        SecurityDialog.UNLINK_GOOGLE -> ConfirmDialog(
            title = "구글 연결을 해제할까요?",
            text = "해제하면 구글로는 로그인할 수 없고, 보안을 위해 다른 기기는 모두 로그아웃됩니다. 이 기기는 로그인 상태가 유지됩니다.",
            confirm = "연결 해제",
            onDismiss = { dialog = null }
        ) {
            dialog = null
            withReauth {
                runBusy {
                    AuthManager.unlinkGoogle(dbUrl, apiKey)
                        .onSuccess {
                            AccountSecurityClient.appendEvent(dbUrl, apiKey, SecurityEvent.GOOGLE_UNLINKED)
                            AccountSecurityClient.revokeOtherSessions(dbUrl, apiKey)
                            message = "구글 연결을 해제했습니다."
                        }
                        .onFailure { e -> message = e.message }
                }
            }
        }
        SecurityDialog.REVOKE -> ConfirmDialog(
            title = "다른 기기를 모두 로그아웃할까요?",
            text = "이 기기를 뺀 모든 기기(폰·PC)의 로그인이 바로 끊깁니다. 다시 쓰려면 그 기기에서 다시 로그인해야 합니다.",
            confirm = "로그아웃",
            onDismiss = { dialog = null }
        ) {
            dialog = null
            withReauth {
                runBusy {
                    AccountSecurityClient.revokeOtherSessions(dbUrl, apiKey)
                        .onSuccess {
                            AccountSecurityClient.appendEvent(dbUrl, apiKey, SecurityEvent.SESSIONS_REVOKED)
                            sessions?.filter { it.id != prefs.authInstallId }?.forEach { AccountSecurityClient.removeSession(dbUrl, apiKey, it.id) }
                            message = "다른 기기를 모두 로그아웃했습니다."
                        }
                        .onFailure { e -> message = "로그아웃시키지 못했습니다: ${e.message}" }
                }
            }
        }
        SecurityDialog.GUEST_LOGOUT -> ConfirmDialog(
            title = "게스트 계정에서 로그아웃할까요?",
            text = "게스트 계정은 로그아웃하면 다시 들어올 수 없습니다. 기록을 잃지 않으려면 먼저 구글 계정을 연결하세요.",
            confirm = "그래도 로그아웃",
            onDismiss = { dialog = null }
        ) {
            dialog = null
            scope.launch { AuthManager.signOut(context) }
        }
        SecurityDialog.DELETE -> ConfirmDialog(
            title = "계정을 삭제할까요?",
            text = "루틴/캘린더/계산기/모임 기록이 이 기기에서 로그아웃되며, 서버의 계정 데이터도 삭제됩니다(되돌릴 수 없음). " +
                "사용하던 아이디는 이후 본인을 포함해 아무도 다시 쓸 수 없게 영구히 잠깁니다.",
            confirm = "삭제",
            danger = true,
            onDismiss = { dialog = null }
        ) {
            dialog = null
            withReauth {
                runBusy {
                    val steps = listOf<suspend () -> Result<Unit>>(
                        { AccountSyncClient.deleteMyData(dbUrl, apiKey) },
                        // 로그인 조회표·보안 기록은 남아도 해가 없다(규칙을 아직 게시하지 않았거나 이미 지워졌을 때) — 거절은 건너뛴다.
                        { customId?.let { AccountSecurityClient.deleteLoginId(dbUrl, apiKey, AuthPolicy.loginIdKey(it)).recover { e -> if (e !is AccountSecurityClient.DeniedException) throw e } } ?: Result.success(Unit) },
                        { AccountSecurityClient.deleteAllMeta(dbUrl, apiKey).recover { e -> if (e !is AccountSecurityClient.DeniedException) throw e } },
                        { AuthManager.deleteAccount() }
                    )
                    for (step in steps) {
                        val r = step()
                        if (r.isFailure) {
                            message = "삭제를 끝내지 못했습니다: ${r.exceptionOrNull()?.message} 잠시 후 다시 시도해 주세요."
                            return@runBusy
                        }
                    }
                    Toast.makeText(context, "계정이 삭제되었습니다.", Toast.LENGTH_LONG).show()
                }
            }
        }
        null -> Unit
    }
}

/** 아이디 변경(개인용) — 새 아이디 선점(usernames, 영구) → 아이디 조회표 등록 → 옛 아이디는 "은퇴"로 막아 둠 → 프로필·DM 라벨. */
private suspend fun changeMyId(dbUrl: String?, apiKey: String?, oldId: String, newIdRaw: String): Result<String> = runCatching {
    val user = AuthManager.currentUser ?: error("로그인이 필요합니다.")
    val newId = newIdRaw.trim().uppercase()
    require(AuthPolicy.ID_PATTERN.matches(newId)) { "아이디는 영문/숫자 3~20자여야 합니다." }
    check(!newId.equals(oldId, ignoreCase = true)) { "지금 아이디와 같습니다." }
    val email = user.email ?: error("로그인 이메일을 확인하지 못했습니다.")
    AccountSyncClient.claimUsername(dbUrl, apiKey, newId).getOrElse { throw IllegalStateException("이미 사용 중인 아이디입니다.") }
    val oldRecord = AccountSecurityClient.lookupLoginId(dbUrl, AuthPolicy.loginIdKey(oldId)).getOrNull()?.takeIf { it.uid == user.uid }
    AccountSecurityClient.writeLoginId(
        dbUrl, apiKey, AuthPolicy.loginIdKey(newId),
        AuthPolicy.LoginIdRecord(uid = user.uid, id = newId, email = email, pendingEmail = oldRecord?.pendingEmail)
    ).getOrElse { throw IllegalStateException("새 아이디를 로그인에 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.") }
    if (oldId.isNotBlank()) {
        AccountSecurityClient.writeLoginId(
            dbUrl, apiKey, AuthPolicy.loginIdKey(oldId),
            AuthPolicy.LoginIdRecord(uid = user.uid, id = oldId, email = email, retired = true)
        )
    }
    AccountSyncClient.updateCustomId(dbUrl, apiKey, newId).getOrThrow()
    // 상대들의 DM 목록에 박혀 있는 내 라벨도 새 아이디로 밀어준다(111차 이월 버그).
    ChatSyncClient.updateMyDmLabel(dbUrl, apiKey, newId)
    AccountSecurityClient.appendEvent(dbUrl, apiKey, SecurityEvent.ID_CHANGED)
    newId
}

/**
 * 이메일 등록·변경 요청 — 아이디 로그인이 끊기지 않도록 아이디 조회표에 "인증 대기 이메일"을 먼저 적어 두고
 * 인증 메일을 보낸다(링크를 누르는 순간 로그인 이메일이 바뀐다).
 */
private suspend fun requestEmailChange(prefs: AppPreferences, dbUrl: String?, apiKey: String?, id: String?, raw: String): Result<Unit> = runCatching {
    val email = AuthPolicy.normalizeEmail(raw)
    require(AuthPolicy.isValidEmail(email)) { "이메일 주소를 확인해 주세요." }
    check(!email.equals(AuthManager.currentUser?.email, ignoreCase = true)) { "지금 이메일과 같습니다." }
    if (id != null) {
        val record = AccountSecurityClient.healLoginId(dbUrl, apiKey, id).getOrNull()
            ?: error("아이디 로그인 정보를 준비하지 못했습니다. 잠시 후 다시 시도해 주세요.")
        AccountSecurityClient.writeLoginId(dbUrl, apiKey, AuthPolicy.loginIdKey(id), record.copy(pendingEmail = email))
            .getOrElse { throw IllegalStateException("아이디 로그인 정보를 준비하지 못했습니다. 잠시 후 다시 시도해 주세요.") }
    }
    AuthManager.requestEmailChange(email).getOrThrow()
    prefs.pendingEmailChange = email
    prefs.emailMailSentAtMillis = System.currentTimeMillis()
    AccountSecurityClient.appendEvent(dbUrl, apiKey, SecurityEvent.EMAIL_CHANGE_REQUESTED)
}

// ---------------------------------------------------------------- 작은 구성 요소

@Composable
private fun SecurityRow(label: String, value: String, action: String?, enabled: Boolean, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = onAction, enabled = enabled) { Text(action, maxLines = 1, softWrap = false) }
        }
    }
}

/** 본인 확인 — 비밀번호가 있으면 비밀번호로, 구글이 연결돼 있으면 구글로. */
@Composable
private fun ReauthDialog(methods: AuthPolicy.LoginMethods, onVerified: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text("본인 확인") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("계정을 보호하기 위해 한 번 더 확인합니다.", style = MaterialTheme.typography.bodyMedium)
                if (methods.hasPassword) {
                    OutlinedTextField(
                        value = password, onValueChange = { password = it; error = null }, label = { Text("현재 비밀번호") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()
                    )
                }
                if (methods.hasGoogle && AuthManager.isGoogleSignInAvailable) {
                    if (methods.hasPassword) HorizontalDivider()
                    OutlinedButton(onClick = {
                        scope.launch {
                            checking = true
                            AuthManager.reauthenticateWithGoogle(context)
                                .onSuccess { onVerified() }
                                .onFailure { e -> if (e !is AuthManager.CancelledException) error = e.message }
                            checking = false
                        }
                    }, enabled = !checking, modifier = Modifier.fillMaxWidth()) { Text("구글로 확인") }
                }
                if (checking) CircularProgressIndicator()
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (methods.hasPassword) {
                Button(enabled = !checking && password.isNotEmpty(), onClick = {
                    scope.launch {
                        checking = true
                        AuthManager.reauthenticateWithPassword(password)
                            .onSuccess { onVerified() }
                            .onFailure { e -> error = e.message }
                        checking = false
                    }
                }) { Text("확인") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !checking) { Text("취소") } }
    )
}

@Composable
private fun ChangeIdDialog(currentId: String, onDismiss: () -> Unit, onSubmit: (newId: String, done: (String?) -> Unit) -> Unit) {
    var newId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("아이디 변경") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("현재 아이디: $currentId", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "이전 아이디는 로그인에 쓸 수 없게 되고, 이후 본인을 포함해 아무도 다시 쓸 수 없게 영구히 잠깁니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = newId, onValueChange = { newId = it; error = null }, label = { Text("새 아이디 (영문/숫자 3~20자)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !working && AuthPolicy.ID_PATTERN.matches(newId.trim()), onClick = {
                working = true
                onSubmit(newId.trim()) { err -> working = false; error = err }
            }) { Text(if (working) "변경 중..." else "변경") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("취소") } }
    )
}

@Composable
private fun EmailChangeDialog(currentEmail: String?, onDismiss: () -> Unit, onSubmit: (email: String, done: (String?) -> Unit) -> Unit) {
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(if (currentEmail == null) "이메일 등록" else "이메일 변경") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                currentEmail?.let {
                    Text("현재 이메일", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "입력한 주소로 인증 메일을 보냅니다. 메일의 링크를 눌러야 등록이 끝나며, 그때부터 이메일로 로그인하고 " +
                        "비밀번호를 잊었을 때 재설정 메일을 받을 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = email, onValueChange = { email = it; error = null }, label = { Text("새 이메일") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !working && AuthPolicy.isValidEmail(email), onClick = {
                working = true
                onSubmit(email.trim()) { err -> working = false; error = err }
            }) { Text(if (working) "보내는 중..." else "인증 메일 보내기") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("취소") } }
    )
}

@Composable
private fun NewPasswordDialog(title: String, note: String, id: String?, onDismiss: () -> Unit, onSubmit: (password: String, done: (String?) -> Unit) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val problem = if (pw.isEmpty()) null else AuthPolicy.newPasswordProblem(pw, id)
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it; error = null }, label = { Text("새 비밀번호") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), isError = problem != null,
                    supportingText = { Text(problem ?: "영문+숫자 ${AuthPolicy.NEW_PASSWORD_MIN}자 이상") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it; error = null }, label = { Text("새 비밀번호 확인") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), isError = confirm.isNotEmpty() && confirm != pw,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = !working && pw.isNotEmpty() && problem == null && pw == confirm, onClick = {
                working = true
                onSubmit(pw) { err -> working = false; error = err }
            }) { Text(if (working) "저장 중..." else "저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("취소") } }
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

private fun platformLabel(platform: String): String = when (platform) {
    "android" -> "안드로이드"
    "desktop" -> "PC"
    else -> platform
}

private fun formatTime(millis: Long): String =
    if (millis <= 0) "-" else SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(millis))
