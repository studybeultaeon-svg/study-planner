package com.phonelock.app.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalContext
import com.phonelock.app.R
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.*
import com.phonelock.app.data.PreMigrationBackup
import com.phonelock.app.routine.RoutineAlarmScheduler
import com.phonelock.app.routine.StudyAlertChecker
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.service.AccessibilityServiceChecker
import com.phonelock.app.service.ADMIN_USERNAME
import com.phonelock.app.service.AccountSyncClient
import com.phonelock.app.service.AuthManager
import com.phonelock.app.service.BackgroundMediaGuard
import com.phonelock.app.service.PhoneLockDeviceAdminReceiver
import com.phonelock.app.ui.components.SectionCard
import com.phonelock.app.ui.components.ToggleRow
import com.phonelock.app.ui.components.isTabletWidth
import com.phonelock.app.ui.theme.Spacing
import com.phonelock.app.widget.RoutineWidgetProvider
import kotlinx.coroutines.launch
import org.json.JSONObject

// 106차: PermissionOnboardingScreen.kt(같은 패키지)도 재사용하므로 private에서 internal로 완화.
internal fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

internal fun isDeviceAdminActive(context: android.content.Context): Boolean {
    val dpm = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return dpm.isAdminActive(PhoneLockDeviceAdminReceiver.componentName(context))
}

internal fun canScheduleExactAlarms(context: android.content.Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true
    val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    return am.canScheduleExactAlarms()
}

/** 동기화 상태 배지용 — "N분/시간/일" 형태의 짧은 상대시간(82차, §10①). */
private fun syncElapsedLabel(atMillis: Long): String {
    val elapsedMs = (System.currentTimeMillis() - atMillis).coerceAtLeast(0L)
    val minutes = elapsedMs / 60_000L
    return when {
        minutes < 1 -> "방금"
        minutes < 60 -> "${minutes}분"
        minutes < 60 * 24 -> "${minutes / 60}시간"
        else -> "${minutes / (60 * 24)}일"
    }
}

/** 118차: 데스크탑판과 대칭되는 9개 카테고리 — 기존 settingsSubTab(0~4, TabRow) 5분류를 성격별로
 *  더 잘게 나눴다. 자세한 매핑 이유는 데스크탑 SettingsScreen.kt 주석 참고. */
private enum class SettingsCategory(val label: String, val emoji: String) {
    PROFILE("프로필", "👤"),
    DISPLAY("화면", "🎨"),
    RULES("규칙", "🗂️"),
    STUDY("공부", "📘"),
    ROUTINE("루틴", "📋"),
    SOCIAL("모임", "👥"),
    DATA("데이터", "💾"),
    SYSTEM("시스템", "⚙️"),
    ADMIN("관리자 패널", "🛡️")
}

/**
 * 설정 화면 — 118차부터 탭이 아니라 홈(식물) 화면 우상단 버튼으로 들어오는 전용 화면이 되었다.
 * 태블릿(가로 600dp 이상)은 데스크탑판과 같은 좌측 카테고리 목록 + 우측 세부 설정 구조, 폰은 기본
 * 화면에 선택된 카테고리의 세부 설정만 보여주고 좌상단 ☰ 버튼으로 카테고리 드로어를 연다 — 카테고리를
 * 고르면 드로어가 자동으로 닫힌다. 기존 SectionCard들은 로직 변경 없이 카테고리별로 재배치만 했다 —
 * 아이디 변경(프로필 카테고리)만 이번에 신규 추가.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    repository: PhoneLockRepository,
    onNavigateToStudyLockApps: () -> Unit = {},
    onThemeChange: (String) -> Unit = {},
    onClose: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context) }

    var themeMode by remember { mutableStateOf(prefs.themeMode) }
    var customBgText by remember { mutableStateOf(prefs.customThemeBackground) }
    var customAccentText by remember { mutableStateOf(prefs.customThemeAccent) }
    var showBgPalette by remember { mutableStateOf(false) }
    var showAccentPalette by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember { mutableStateOf(AccessibilityServiceChecker.isEnabled(context)) }
    var deviceAdminActive by remember { mutableStateOf(isDeviceAdminActive(context)) }
    var batteryOptIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var exactAlarmGranted by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    var mediaAccessGranted by remember { mutableStateOf(BackgroundMediaGuard.hasSessionAccess(context)) }
    // 106차: 개별 권한 카드 여러 개 대신 "권한 설정 가이드" 진입점 하나로 통합 — 최초 실행 때 본 것과
    // 같은 화면을 여기서 다시 연다.
    var showPermissionGuide by remember { mutableStateOf(false) }
    var blockReels by remember { mutableStateOf(prefs.blockReels) }
    var blockShorts by remember { mutableStateOf(prefs.blockShorts) }
    var routineStreakNotifyEnabled by remember { mutableStateOf(prefs.routineStreakNotifyEnabled) }
    // 공부 알림(122차) — 값은 전부 AppPreferences(기기 로컬)에 즉시 저장된다.
    var studyAlertEnabled by remember { mutableStateOf(prefs.studyAlertEnabled) }
    var studyAlertVibrate by remember { mutableStateOf(prefs.studyAlertVibrate) }
    var studyAlertNotStarted by remember { mutableStateOf(prefs.studyAlertNotStartedEnabled) }
    var studyAlertPace by remember { mutableStateOf(prefs.studyAlertPaceEnabled) }
    var studyAlertSchedule by remember { mutableStateOf(prefs.studyAlertScheduleEnabled) }
    var studyAlertStartHour by remember { mutableStateOf(prefs.studyAlertStartHour) }
    var studyAlertEndHour by remember { mutableStateOf(prefs.studyAlertEndHour) }
    var studyAlertTestResult by remember { mutableStateOf<String?>(null) }
    var defaultMultiPassEnabled by remember { mutableStateOf(prefs.defaultMultiPassEnabled) }
    var defaultPassCount by remember { mutableStateOf(prefs.defaultPassCount) }
    var defaultPassIntervals by remember {
        mutableStateOf(com.phonelock.shared.calc.PassSchedule.parsePassIntervals(prefs.defaultPassIntervalsCsv, prefs.defaultPassCount))
    }
    // 90차: 타이머 탭에서 옮겨온 "공부 잠금 허용 사이트"(공부 서브탭) — 저장 위치는 그대로다.
    var studyAllowedSites by remember { mutableStateOf(prefs.studyLockAllowedSites.toList()) }
    var dailyResetHourText by remember { mutableStateOf(prefs.dailyResetHour.toString()) }
    // 85차: 설정 화면 진입 시 다른 기기에서 바꾼 다회독 기본값/일일 초기화 시각을 받아와 로컬 상태를 갱신.
    LaunchedEffect(Unit) {
        repository.syncSettingsFromFirebase()
        defaultMultiPassEnabled = prefs.defaultMultiPassEnabled
        defaultPassCount = prefs.defaultPassCount
        defaultPassIntervals = com.phonelock.shared.calc.PassSchedule.parsePassIntervals(prefs.defaultPassIntervalsCsv, prefs.defaultPassCount)
        dailyResetHourText = prefs.dailyResetHour.toString()
    }
    var loginId by remember { mutableStateOf(AuthManager.currentLoginId) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    val autoBackups = remember { PreMigrationBackup.listBackups(context) }
    var groupRestoreResult by remember { mutableStateOf<String?>(null) }
    var showRoutineRestoreConfirmDialog by remember { mutableStateOf(false) }
    var pendingRoutineRestoreUri by remember { mutableStateOf<Uri?>(null) }

    // 아이디 변경(118차 신규)
    var currentCustomId by remember { mutableStateOf(AuthManager.currentLoginId ?: "") }
    var newIdText by remember { mutableStateOf("") }
    var idCurrentPasswordText by remember { mutableStateOf("") }
    var idSaving by remember { mutableStateOf(false) }
    var idMessage by remember { mutableStateOf<String?>(null) }

    var isAdmin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isAdmin = AccountSyncClient.isAdmin(prefs.fbDatabaseUrl, prefs.fbApiKey)
    }

    val visibleCategories = remember(isAdmin) {
        listOfNotNull(
            SettingsCategory.PROFILE,
            SettingsCategory.DISPLAY,
            SettingsCategory.RULES.takeIf { prefs.permManage },
            SettingsCategory.STUDY.takeIf { prefs.permStudy },
            SettingsCategory.ROUTINE.takeIf { prefs.permRoutine },
            SettingsCategory.SOCIAL.takeIf { prefs.permSocial },
            SettingsCategory.DATA,
            SettingsCategory.SYSTEM,
            SettingsCategory.ADMIN.takeIf { isAdmin }
        )
    }
    var category by remember { mutableStateOf(SettingsCategory.PROFILE) }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = repository.exportBackupJson()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                Toast.makeText(context, "백업 완료", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val routineBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = repository.exportRoutinesBackupJson()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                Toast.makeText(context, "루틴 내보내기 완료", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val routineRestoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingRoutineRestoreUri = uri
            showRoutineRestoreConfirmDialog = true
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            showRestoreConfirmDialog = true
        }
    }

    if (showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = { Text("복원 확인") },
            text = { Text("복원하면 현재 차단 규칙이 백업 파일 내용으로 대체됩니다. 계속할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingRestoreUri
                    showRestoreConfirmDialog = false
                    if (uri != null) {
                        scope.launch {
                            val text = context.contentResolver.openInputStream(uri)
                                ?.bufferedReader()?.use { it.readText() }
                            if (text != null) {
                                repository.importBackupJson(text)
                                Toast.makeText(context, "복원 완료", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }) { Text("복원") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmDialog = false }) { Text("취소") }
            }
        )
    }

    if (showRoutineRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRoutineRestoreConfirmDialog = false },
            title = { Text("루틴 복원 확인") },
            text = { Text("복원하면 현재 루틴/체크 기록이 파일 내용으로 대체됩니다. 계속할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingRoutineRestoreUri
                    showRoutineRestoreConfirmDialog = false
                    if (uri != null) {
                        scope.launch {
                            val text = context.contentResolver.openInputStream(uri)
                                ?.bufferedReader()?.use { it.readText() }
                            if (text != null) {
                                repository.importRoutinesBackupJson(text)
                                Toast.makeText(context, "루틴 복원 완료", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }) { Text("복원") }
            },
            dismissButton = {
                TextButton(onClick = { showRoutineRestoreConfirmDialog = false }) { Text("취소") }
            }
        )
    }

    if (showPermissionGuide) {
        PermissionOnboardingScreen(repository = repository, onDone = {
            showPermissionGuide = false
            accessibilityEnabled = AccessibilityServiceChecker.isEnabled(context)
            deviceAdminActive = isDeviceAdminActive(context)
            batteryOptIgnored = isIgnoringBatteryOptimizations(context)
            exactAlarmGranted = canScheduleExactAlarms(context)
            mediaAccessGranted = BackgroundMediaGuard.hasSessionAccess(context)
        })
        return
    }

    // 카테고리별 세부 설정 — 태블릿의 우측 패널과 폰의 드로어 본문이 같은 람다를 재사용한다
    // (MainActivity.kt의 navHostContent와 같은 패턴).
    val detailContent: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(Spacing.md)) {
            when (category) {
                SettingsCategory.PROFILE -> {
                    SectionCard("닉네임 설정") {
                        var nickname by remember { mutableStateOf("") }
                        var nicknameSaving by remember { mutableStateOf(false) }
                        var nicknameSaveResult by remember { mutableStateOf<String?>(null) }
                        LaunchedEffect(Unit) {
                            val profile = AccountSyncClient.fetchMyProfile(prefs.fbDatabaseUrl, prefs.fbApiKey).getOrNull()
                            nickname = profile?.optString("nickname", "") ?: ""
                        }
                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { nickname = it },
                            label = { Text("닉네임 (1~20자)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Button(
                            onClick = {
                                val trimmed = nickname.trim()
                                if (trimmed.isEmpty() || trimmed.length > 20) {
                                    nicknameSaveResult = "닉네임은 1~20자여야 합니다."
                                    return@Button
                                }
                                nicknameSaving = true
                                nicknameSaveResult = null
                                scope.launch {
                                    val result = AccountSyncClient.updateNickname(prefs.fbDatabaseUrl, prefs.fbApiKey, trimmed)
                                    nicknameSaving = false
                                    nicknameSaveResult = if (result.isSuccess) "저장했습니다" else "저장 실패: ${result.exceptionOrNull()?.message ?: "알 수 없는 오류"}"
                                }
                            },
                            enabled = !nicknameSaving,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (nicknameSaving) "저장 중..." else "저장") }
                        nicknameSaveResult?.let {
                            Spacer(Modifier.height(Spacing.sm))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("프로필 사진 선택") {
                        var selectedAvatar by remember { mutableStateOf<String?>(null) }
                        var avatarSaving by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            val profile = AccountSyncClient.fetchMyProfile(prefs.fbDatabaseUrl, prefs.fbApiKey).getOrNull()
                            selectedAvatar = profile?.optString("profileImage", "")?.takeIf { it.isNotBlank() }
                        }
                        Text(
                            "동물 프로필 사진 중 하나를 골라보세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            com.phonelock.app.ui.components.AvatarCatalog.PRESETS.forEach { (id, emoji) ->
                                val selected = selectedAvatar == id
                                Surface(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clickable(enabled = !avatarSaving) {
                                            selectedAvatar = id
                                            avatarSaving = true
                                            scope.launch {
                                                AccountSyncClient.updateProfileImage(prefs.fbDatabaseUrl, prefs.fbApiKey, id)
                                                avatarSaving = false
                                            }
                                        },
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Text(emoji, style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    if (loginId != null) {
                        SectionCard("아이디 변경") {
                            val isAdminAccount = currentCustomId.equals(ADMIN_USERNAME, ignoreCase = true)
                            Text("현재 아이디: ${currentCustomId.ifBlank { "-" }}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(Spacing.sm))
                            if (isAdminAccount) {
                                Text(
                                    "관리자 계정은 아이디를 바꿀 수 없습니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    "이전 아이디는 이후 본인을 포함해 아무도 다시 쓸 수 없게 영구히 잠기며, 다른 사람이 " +
                                        "검색하면 옛 아이디로도 여전히 본인이 나옵니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(Spacing.sm))
                                OutlinedTextField(
                                    value = newIdText,
                                    onValueChange = { newIdText = it; idMessage = null },
                                    label = { Text("새 아이디 (영문/숫자 3~20자)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(Spacing.sm))
                                OutlinedTextField(
                                    value = idCurrentPasswordText,
                                    onValueChange = { idCurrentPasswordText = it; idMessage = null },
                                    label = { Text("현재 비밀번호 확인") },
                                    singleLine = true,
                                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(Spacing.sm))
                                val idValid = idPattern.matches(newIdText.trim().uppercase()) && idCurrentPasswordText.isNotBlank()
                                Button(
                                    enabled = idValid && !idSaving,
                                    onClick = {
                                        idSaving = true
                                        idMessage = null
                                        val loginIdForVerify = currentCustomId
                                        val newId = newIdText.trim()
                                        val passwordForVerify = idCurrentPasswordText
                                        scope.launch {
                                            // 본인 확인 — 현재 아이디+비밀번호로 다시 로그인해본다.
                                            val verify = AuthManager.signIn(loginIdForVerify, passwordForVerify)
                                            if (verify.isFailure) {
                                                idSaving = false
                                                idMessage = "비밀번호가 올바르지 않습니다."
                                                return@launch
                                            }
                                            val claimResult = AccountSyncClient.claimUsername(prefs.fbDatabaseUrl, prefs.fbApiKey, newId)
                                            if (claimResult.isFailure) {
                                                idSaving = false
                                                idMessage = claimResult.exceptionOrNull()?.message ?: "이미 사용 중인 아이디입니다."
                                                return@launch
                                            }
                                            val emailResult = AuthManager.changeCustomId(newId)
                                            if (emailResult.isFailure) {
                                                idSaving = false
                                                idMessage = emailResult.exceptionOrNull()?.message ?: "변경 실패"
                                                return@launch
                                            }
                                            val profileResult = AccountSyncClient.updateCustomId(prefs.fbDatabaseUrl, prefs.fbApiKey, newId)
                                            idSaving = false
                                            profileResult.onSuccess {
                                                currentCustomId = newId.uppercase()
                                                loginId = AuthManager.currentLoginId
                                                newIdText = ""
                                                idCurrentPasswordText = ""
                                                idMessage = "아이디가 ${newId.uppercase()}(으)로 변경되었습니다."
                                            }
                                            profileResult.onFailure { e ->
                                                idMessage = e.message ?: "프로필 갱신 실패 — 로그인 아이디는 이미 바뀌었으니 다시 로그인해 재시도하세요."
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (idSaving) "변경 중..." else "아이디 변경") }
                                idMessage?.let { msg ->
                                    Spacer(Modifier.height(Spacing.xs))
                                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(Spacing.md))

                        SectionCard("비밀번호 변경") {
                            var newPassword by remember { mutableStateOf("") }
                            var newPasswordConfirm by remember { mutableStateOf("") }
                            var pwSaving by remember { mutableStateOf(false) }
                            var pwResult by remember { mutableStateOf<String?>(null) }
                            val pwValid = newPassword.length in 6..50 && newPassword == newPasswordConfirm

                            OutlinedTextField(
                                value = newPassword,
                                onValueChange = { newPassword = it; pwResult = null },
                                label = { Text("새 비밀번호 (6자 이상)") },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            OutlinedTextField(
                                value = newPasswordConfirm,
                                onValueChange = { newPasswordConfirm = it; pwResult = null },
                                label = { Text("새 비밀번호 확인") },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Button(
                                onClick = {
                                    scope.launch {
                                        pwSaving = true
                                        pwResult = null
                                        val result = AuthManager.changePassword(newPassword)
                                        pwSaving = false
                                        result.onSuccess {
                                            pwResult = "변경되었습니다."
                                            newPassword = ""
                                            newPasswordConfirm = ""
                                        }
                                        result.onFailure { e ->
                                            pwResult = e.message ?: "변경 실패 — 오래 전에 로그인했다면 로그아웃 후 다시 로그인해서 시도해주세요."
                                        }
                                    }
                                },
                                enabled = pwValid && !pwSaving,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (pwSaving) "변경 중..." else "비밀번호 변경") }
                            pwResult?.let {
                                Spacer(Modifier.height(Spacing.sm))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(Spacing.md))
                    }

                    // 98차(사용자 요청): 온라인/오프라인 모드 — 네트워크가 실제로 끊기면 자동으로 오프라인
                    // 전환되지만(NetworkMonitor), 필요하면 연결돼 있어도 수동으로 강제 오프라인 가능.
                    // 106차 후속: 이 수동 전환 UI는 게스트(익명 로그인) 전용 기능이므로 게스트에게만 노출한다.
                    if (AuthManager.currentUser?.isAnonymous == true) {
                        SectionCard("온라인 / 오프라인 모드") {
                            var offlineOverride by remember { mutableStateOf(prefs.offlineModeOverride) }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("오프라인 모드로 강제 전환", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "켜면 인터넷이 연결돼 있어도 동기화/로그인/모임 등 네트워크 기능을 쓰지 않고 이 " +
                                            "기기에서만 로컬로 사용합니다. 꺼둬도 실제로 인터넷이 끊기면 자동으로 오프라인 " +
                                            "처리됩니다.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                androidx.compose.material3.Switch(
                                    checked = offlineOverride,
                                    onCheckedChange = { offlineOverride = it; prefs.offlineModeOverride = it }
                                )
                            }
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                if (com.phonelock.app.service.NetworkMonitor.isOnline) "현재 인터넷 연결됨" else "현재 인터넷 연결 안 됨",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (com.phonelock.app.service.NetworkMonitor.isOnline) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(Spacing.md))
                    }

                    SectionCard("계정 동기화 (로그인 필수)") {
                        Text(
                            "동기화(실행 전 대기 단계/잠깐 풀기/일일사용량/캘린더/계산기/루틴)는 이제 로그인이 있어야만 " +
                                "작동합니다. 같은 계정으로 로그인한 기기끼리 자동으로 연결됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        if (loginId != null) {
                            Text("로그인됨: $loginId", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(Spacing.sm))
                            Button(
                                onClick = {
                                    AuthManager.signOut()
                                    loginId = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("로그아웃") }
                        } else {
                            Text(
                                AuthManager.currentUser?.let { "게스트로 로그인되어 있습니다." }
                                    ?: "로그아웃되었습니다. 앱을 다시 시작해서 로그인해주세요.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (AuthManager.currentUser != null) {
                            Spacer(Modifier.height(Spacing.sm))
                            var showDeleteConfirm by remember { mutableStateOf(false) }
                            var deleteError by remember { mutableStateOf<String?>(null) }
                            var deleting by remember { mutableStateOf(false) }
                            Button(
                                onClick = { showDeleteConfirm = true },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("계정 삭제") }
                            deleteError?.let { msg ->
                                Spacer(Modifier.height(Spacing.xs))
                                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            if (showDeleteConfirm) {
                                AlertDialog(
                                    onDismissRequest = { if (!deleting) showDeleteConfirm = false },
                                    title = { Text("계정을 삭제할까요?") },
                                    text = {
                                        Text(
                                            "루틴/캘린더/계산기/모임 기록이 이 기기에서 로그아웃되며, 서버의 계정 데이터도 " +
                                                "삭제됩니다(되돌릴 수 없음). 사용하던 아이디는 이후 본인을 포함해 아무도 다시 " +
                                                "쓸 수 없게 영구히 잠깁니다."
                                        )
                                    },
                                    confirmButton = {
                                        Button(
                                            enabled = !deleting,
                                            onClick = {
                                                scope.launch {
                                                    deleting = true
                                                    deleteError = null
                                                    val delResult = AccountSyncClient.deleteMyData(prefs.fbDatabaseUrl, prefs.fbApiKey)
                                                    val authResult = AuthManager.deleteAccount()
                                                    deleting = false
                                                    if (authResult.isSuccess) {
                                                        showDeleteConfirm = false
                                                        loginId = null
                                                        android.widget.Toast.makeText(
                                                            context, "계정이 삭제되었습니다. 앱을 다시 시작해주세요.", android.widget.Toast.LENGTH_LONG
                                                        ).show()
                                                    } else {
                                                        deleteError = delResult.exceptionOrNull()?.message
                                                            ?: authResult.exceptionOrNull()?.message
                                                            ?: "삭제 실패 — 오래 전에 로그인했다면 로그아웃 후 다시 로그인해서 시도해주세요."
                                                    }
                                                }
                                            }
                                        ) { Text(if (deleting) "삭제 중..." else "삭제") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDeleteConfirm = false }, enabled = !deleting) { Text("취소") }
                                    }
                                )
                            }
                        }
                    }
                }

                SettingsCategory.DISPLAY -> {
                    SectionCard("테마") {
                        Text(
                            "앱 전체 배경/포인트 색과 차단/실행 전 대기 화면 강조색, 홈 화면 위젯 색까지 함께 바뀝니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            com.phonelock.app.ui.theme.THEME_DISPLAY_NAMES.forEach { (mode, label) ->
                                FilterChip(
                                    selected = themeMode == mode,
                                    onClick = {
                                        themeMode = mode; prefs.themeMode = mode
                                        onThemeChange(mode); RoutineWidgetProvider.updateAll(context)
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                        if (themeMode == com.phonelock.app.ui.theme.ThemeMode.CUSTOM) {
                            Spacer(Modifier.height(Spacing.sm))
                            val bgPreview = com.phonelock.app.ui.theme.parseHexColor(customBgText)
                            val accentPreview = com.phonelock.app.ui.theme.parseHexColor(customAccentText)
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = customBgText,
                                    onValueChange = { text ->
                                        customBgText = text
                                        if (com.phonelock.app.ui.theme.parseHexColor(text) != null) {
                                            prefs.customThemeBackground = text.trim()
                                            onThemeChange(themeMode); RoutineWidgetProvider.updateAll(context)
                                        }
                                    },
                                    label = { Text("배경색") },
                                    placeholder = { Text("#FAFBF6") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Box(
                                    Modifier.size(36.dp)
                                        .background(bgPreview ?: Color.Gray, MaterialTheme.shapes.small)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                                        .clickable { showBgPalette = true }
                                )
                            }
                            Spacer(Modifier.height(Spacing.sm))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = customAccentText,
                                    onValueChange = { text ->
                                        customAccentText = text
                                        if (com.phonelock.app.ui.theme.parseHexColor(text) != null) {
                                            prefs.customThemeAccent = text.trim()
                                            onThemeChange(themeMode); RoutineWidgetProvider.updateAll(context)
                                        }
                                    },
                                    label = { Text("포인트색") },
                                    placeholder = { Text("#8BC34A") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Box(
                                    Modifier.size(36.dp)
                                        .background(accentPreview ?: Color.Gray, MaterialTheme.shapes.small)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                                        .clickable { showAccentPalette = true }
                                )
                            }
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "직접 입력하거나, 오른쪽 색상 상자를 눌러 팔레트에서 고를 수 있습니다. 배경 밝기로 라이트/다크를 자동 판정하고, 나머지 색은 두 색을 섞어 자동으로 맞춥니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (showBgPalette) {
                                com.phonelock.app.ui.components.ColorPaletteDialog(
                                    title = "배경색 고르기",
                                    currentHex = customBgText,
                                    onSelect = { hex ->
                                        customBgText = hex
                                        prefs.customThemeBackground = hex
                                        onThemeChange(themeMode); RoutineWidgetProvider.updateAll(context)
                                    },
                                    onDismiss = { showBgPalette = false }
                                )
                            }
                            if (showAccentPalette) {
                                com.phonelock.app.ui.components.ColorPaletteDialog(
                                    title = "포인트색 고르기",
                                    currentHex = customAccentText,
                                    onSelect = { hex ->
                                        customAccentText = hex
                                        prefs.customThemeAccent = hex
                                        onThemeChange(themeMode); RoutineWidgetProvider.updateAll(context)
                                    },
                                    onDismiss = { showAccentPalette = false }
                                )
                            }
                        }
                    }
                }

                SettingsCategory.RULES -> {
                    SectionCard("일일 사용 한도 초기화 시각") {
                        OutlinedTextField(
                            value = dailyResetHourText,
                            onValueChange = { text ->
                                dailyResetHourText = text
                                text.toIntOrNull()?.let { if (it in 0..23) { prefs.dailyResetHour = it; repository.pushSettingsToFirebase() } }
                            },
                            label = { Text("초기화 시각 (0~23시)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "이 시각이 되면 차단 규칙별 오늘 사용 시간이 초기화됩니다. (캘린더/공부기록의 \"오늘\" 판정도 이 시각을 기준으로 함께 바뀝니다.)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("릴스/쇼츠 차단") {
                        ToggleRow(
                            title = "인스타 차단",
                            checked = blockReels,
                            onCheckedChange = { checked ->
                                blockReels = checked
                                prefs.blockReels = checked
                            }
                        )
                        ToggleRow(
                            title = "쇼츠 차단 (유튜브)",
                            checked = blockShorts,
                            onCheckedChange = { checked ->
                                blockShorts = checked
                                prefs.blockShorts = checked
                            }
                        )
                        Text(
                            "릴스/쇼츠 화면만 감지해서 차단합니다 (베스트 에포트 기능).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))

                    if (autoBackups.isNotEmpty()) {
                        SectionCard("⚠ 차단 규칙 데이터 복구") {
                            Text(
                                "앱 업데이트로 로컬 데이터가 초기화됐을 때 자동으로 만들어진 백업이 있습니다. 차단 규칙(차단 " +
                                    "대상 앱/사이트 목록)은 동기화되지 않는 데이터라 지워졌다면 이 백업에서만 복구할 수 " +
                                    "있습니다. 차단 규칙이 이미 정상적으로 보이면 누르지 마세요(같은 차단 규칙이 중복으로 추가됩니다).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            val latest = autoBackups.first()
                            Text("가장 최근 백업: ${latest.name}", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(Spacing.sm))
                            Button(onClick = {
                                scope.launch {
                                    val json = runCatching { JSONObject(latest.readText()) }.getOrNull()
                                    if (json == null) {
                                        groupRestoreResult = "백업 파일을 읽지 못했습니다."
                                    } else {
                                        val count = repository.restoreGroupsFromBackup(json)
                                        groupRestoreResult = "차단 규칙 ${count}개 복구 완료. 앱을 재시작해주세요."
                                    }
                                }
                            }) { Text("이 백업에서 차단 규칙 복구") }
                            groupRestoreResult?.let {
                                Spacer(Modifier.height(Spacing.sm))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                SettingsCategory.STUDY -> {
                    SectionCard("캘린더 복습 기본값") {
                        ToggleRow(
                            title = "새 일정을 복습으로 시작",
                            checked = defaultMultiPassEnabled,
                            onCheckedChange = { checked ->
                                defaultMultiPassEnabled = checked
                                prefs.defaultMultiPassEnabled = checked
                                repository.pushSettingsToFirebase()
                            }
                        )
                        Text(
                            "켜두면 캘린더에 새로 추가하는 일정이 완료(O) 시 다음 복습을 자동 생성하는 상태로 시작됩니다. 이미 만든 일정에는 영향 없고, 각 일정에서 개별적으로 다시 켜고 끌 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "계산기 업무와 연결하지 않고 캘린더에서 직접 추가하는 일정에 적용되는 기본 복습 횟수/간격입니다 " +
                                "(계산기 업무는 업무별로 각 업무 입력 카드에서 따로 설정).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        com.phonelock.app.ui.components.NumberStepperField(
                            label = "기본 복습 횟수",
                            value = defaultPassCount.toString(),
                            onValueChange = { text ->
                                val newCount = (text.toIntOrNull() ?: defaultPassCount)
                                    .coerceIn(com.phonelock.shared.calc.PassSchedule.MIN_PASS_COUNT, com.phonelock.shared.calc.PassSchedule.MAX_PASS_COUNT)
                                defaultPassCount = newCount
                                prefs.defaultPassCount = newCount
                                defaultPassIntervals = com.phonelock.shared.calc.PassSchedule.defaultPassIntervals(newCount)
                                prefs.defaultPassIntervalsCsv = defaultPassIntervals.joinToString(",")
                                repository.pushSettingsToFirebase()
                            },
                            min = com.phonelock.shared.calc.PassSchedule.MIN_PASS_COUNT,
                            max = com.phonelock.shared.calc.PassSchedule.MAX_PASS_COUNT,
                            modifier = Modifier.width(160.dp)
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text("복습별 간격(일)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            defaultPassIntervals.forEachIndexed { i, days ->
                                com.phonelock.app.ui.components.NumberStepperField(
                                    label = "${i + 1}→${i + 2}회 복습",
                                    value = days.toString(),
                                    onValueChange = { text ->
                                        val newDays = (text.toIntOrNull() ?: days).coerceIn(1, 90)
                                        val updated = defaultPassIntervals.toMutableList().also { it[i] = newDays }
                                        defaultPassIntervals = updated
                                        prefs.defaultPassIntervalsCsv = updated.joinToString(",")
                                        repository.pushSettingsToFirebase()
                                    },
                                    min = 1,
                                    max = 90,
                                    modifier = Modifier.width(140.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    // 공부 알림(122차, 사용자 요청) — 캘린더/계산기/일정표 데이터를 보고 "계획보다 늦어질 때만"
                    // 알린다. 설정값은 전부 이 기기 로컬(SharedPreferences)이라 통신이 끊겨도 초기화되지 않는다.
                    SectionCard("🔔 공부 알림") {
                        Text(
                            "캘린더·일정표에 예정된 공부와 실제 진행 상황을 비교해서, 계획보다 늦어질 때만 알림을 보냅니다. " +
                                "일정한 간격으로 무조건 보내지 않으며 같은 종류의 알림은 하루에 한 번만 옵니다. " +
                                "이 설정은 기기별로 저장되고 동기화하지 않으므로 인터넷이 끊겨도 초기화되지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        ToggleRow(
                            title = "공부 알림 받기",
                            checked = studyAlertEnabled,
                            onCheckedChange = { checked ->
                                studyAlertEnabled = checked
                                prefs.studyAlertEnabled = checked
                                if (checked) {
                                    RoutineAlarmScheduler.scheduleStudyAlertCheck(context)
                                } else {
                                    RoutineAlarmScheduler.cancelStudyAlertCheck(context)
                                }
                            }
                        )
                        if (studyAlertEnabled) {
                            ToggleRow(
                                title = "진동",
                                description = "끄면 소리·진동 없이 알림만 표시됩니다.",
                                checked = studyAlertVibrate,
                                onCheckedChange = { checked ->
                                    studyAlertVibrate = checked
                                    prefs.studyAlertVibrate = checked
                                }
                            )
                            ToggleRow(
                                title = "공부 미실행 알림",
                                description = "오늘 예정된 공부가 있는데 아직 아무것도 하지 않았을 때.",
                                checked = studyAlertNotStarted,
                                onCheckedChange = { checked ->
                                    studyAlertNotStarted = checked
                                    prefs.studyAlertNotStartedEnabled = checked
                                }
                            )
                            ToggleRow(
                                title = "학습 페이스 지연 알림",
                                description = "진행량이 계획(기간 경과율)보다 뒤처졌을 때.",
                                checked = studyAlertPace,
                                onCheckedChange = { checked ->
                                    studyAlertPace = checked
                                    prefs.studyAlertPaceEnabled = checked
                                }
                            )
                            ToggleRow(
                                title = "일정 지연 알림",
                                description = "마감이 지났거나, 지금 페이스면 목표 일정을 못 맞출 때.",
                                checked = studyAlertSchedule,
                                onCheckedChange = { checked ->
                                    studyAlertSchedule = checked
                                    prefs.studyAlertScheduleEnabled = checked
                                }
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Text("알림 가능 시간대", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(Spacing.xs))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                com.phonelock.app.ui.components.NumberStepperField(
                                    label = "시작(시)",
                                    value = studyAlertStartHour.toString(),
                                    onValueChange = { text ->
                                        val hour = (text.toIntOrNull() ?: studyAlertStartHour).coerceIn(0, 23)
                                        studyAlertStartHour = hour
                                        prefs.studyAlertStartHour = hour
                                    },
                                    min = 0,
                                    max = 23,
                                    modifier = Modifier.width(140.dp)
                                )
                                com.phonelock.app.ui.components.NumberStepperField(
                                    label = "종료(시)",
                                    value = studyAlertEndHour.toString(),
                                    onValueChange = { text ->
                                        val hour = (text.toIntOrNull() ?: studyAlertEndHour).coerceIn(0, 23)
                                        studyAlertEndHour = hour
                                        prefs.studyAlertEndHour = hour
                                    },
                                    min = 0,
                                    max = 23,
                                    modifier = Modifier.width(140.dp)
                                )
                            }
                            Text(
                                "이 시간대 밖에서는 알림을 보내지 않습니다(시작이 종료보다 늦으면 자정을 넘기는 구간으로 봅니다).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Button(
                                onClick = {
                                    scope.launch {
                                        studyAlertTestResult = StudyAlertChecker.checkAndNotify(context, force = true)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("지금 한 번 확인") }
                            Text(
                                "지금 조건을 검사해서 보낼 알림이 있으면 바로 보냅니다(시간대·하루 1회 제한은 무시).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            studyAlertTestResult?.let {
                                Spacer(Modifier.height(Spacing.xs))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    // 90차(사용자 요청): 타이머 탭 안에 있던 "공부 잠금 허용 앱/사이트"를 여기로 옮겼다 —
                    // 매번 보는 화면이 아니라 한 번 정해두는 설정이라 설정 탭이 제자리다.
                    AllowedAppsCollapsibleSection(onOpenFullScreen = onNavigateToStudyLockApps)
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("🌐 공부 잠금 허용 사이트") {
                        Text(
                            "허용된 앱(브라우저)이 열려 있어도 여기 등록 안 된 사이트는 따로 차단됩니다. 이 기기에만 " +
                                "적용되며, 데스크탑에는 데스크탑 앱 설정에서 따로 등록해야 합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        LockListEditor(
                            items = studyAllowedSites,
                            placeholder = "예: google.com",
                            onAdd = { name -> studyAllowedSites = studyAllowedSites + name; prefs.studyLockAllowedSites = studyAllowedSites.toSet() },
                            onRemove = { idx -> studyAllowedSites = studyAllowedSites.toMutableList().apply { removeAt(idx) }; prefs.studyLockAllowedSites = studyAllowedSites.toSet() }
                        )
                    }
                }

                SettingsCategory.ROUTINE -> {
                    SectionCard("루틴 연속 기록 알림") {
                        ToggleRow(
                            title = "연속 기록 알림 받기",
                            checked = routineStreakNotifyEnabled,
                            onCheckedChange = { checked ->
                                routineStreakNotifyEnabled = checked
                                prefs.routineStreakNotifyEnabled = checked
                                if (checked) {
                                    RoutineAlarmScheduler.scheduleStreakCheck(context)
                                } else {
                                    RoutineAlarmScheduler.cancelStreakCheck(context)
                                }
                            }
                        )
                        Text(
                            "하루 중 랜덤한 시각에 어제 루틴 연속 기록 상태를 알려줍니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("루틴 내보내기 · 가져오기") {
                        Text(
                            "루틴 목록과 체크 기록을 파일로 저장하거나 불러옵니다. 루틴은 이미 Firebase로 기기 간 자동 " +
                                "동기화되지만, 기기 초기화 전 별도 백업을 남기거나 다른 계정으로 옮길 때 씁니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Button(
                            onClick = { routineBackupLauncher.launch("phone_lock_routines.json") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("루틴 내보내기")
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        Button(
                            onClick = { routineRestoreLauncher.launch(arrayOf("application/json")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("루틴 파일에서 가져오기")
                        }
                    }
                }

                SettingsCategory.SOCIAL -> {
                    SectionCard("모임 공유 설정") {
                        Text(
                            "모임마다 공개할 내 정보(루틴/공부/연속 기록/오늘 일정/공부중 여부/작동 중인 차단 규칙)를 " +
                                "다르게 정할 수 있어, 여기가 아니라 각 모임 화면의 ⚙ 공유 설정에서 모임별로 관리합니다. " +
                                "특정 멤버에게만 내 정보를 숨기거나 특정 멤버의 정보를 안 보이게 하는 것도 그 " +
                                "멤버의 상세 화면에서 따로 설정할 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "깨우기 메시지(음성/텍스트) 수신 설정도 모임마다 다르게 정할 수 있어 여기가 아니라 각 모임 " +
                            "화면의 ⚙ 깨우기 메시지 설정에서 관리합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SettingsCategory.DATA -> {
                    SectionCard("백업 · 복원") {
                        Button(
                            onClick = { backupLauncher.launch("phone_lock_backup.json") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("클라우드로 백업")
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        Button(
                            onClick = { restoreLauncher.launch(arrayOf("application/json")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("백업 파일에서 복원")
                        }
                        Text(
                            "저장 위치 선택 창에서 구글 드라이브 등 클라우드 폴더를 직접 고를 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("자동 백업 (Firebase)") {
                        var cloudBackupEnabled by remember { mutableStateOf(prefs.cloudBackupEnabled) }
                        ToggleRow(
                            title = "매일 자동으로 클라우드에 백업",
                            checked = cloudBackupEnabled,
                            onCheckedChange = { checked -> cloudBackupEnabled = checked; prefs.cloudBackupEnabled = checked }
                        )
                        Text(
                            "로그인이 필요하며, Firebase 콘솔에서 Storage를 먼저 활성화해야 동작합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (prefs.lastCloudBackupResult.isNotBlank()) {
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "마지막 결과(${prefs.lastCloudBackupDate}): ${prefs.lastCloudBackupResult}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (prefs.lastCloudBackupResult.startsWith("성공")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        OutlinedButton(onClick = {
                            scope.launch {
                                val json = repository.exportBackupJson()
                                val result = com.phonelock.app.service.CloudBackupClient.uploadBackup(prefs.fbDatabaseUrl, json)
                                Toast.makeText(
                                    context,
                                    if (result.isSuccess) "백업 업로드 완료" else "백업 실패: ${result.exceptionOrNull()?.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }) { Text("지금 클라우드에 백업") }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("오래된 사용 기록 정리") {
                        var lastResult by remember { mutableStateOf<Int?>(null) }
                        Text(
                            "12개월 이상 지난 사용시간/재확인 통과 횟수/공부 기록을 영구 삭제합니다(되돌리기 없음). " +
                                "캘린더 일정과 연속 기록 계산에는 영향을 주지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Button(
                            onClick = { scope.launch { lastResult = repository.pruneOldStats(12) } },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("🧹 12개월 이상 지난 기록 정리") }
                        lastResult?.let {
                            Spacer(Modifier.height(Spacing.xs))
                            Text("$it 건 삭제됨", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                SettingsCategory.SYSTEM -> {
                    SectionCard("표시 / 진단") {
                        Text("글자 크기", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(Spacing.xs))
                        var fontScale by remember { mutableStateOf(prefs.fontScale) }
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            listOf(0.85f to "작게", 1.0f to "기본", 1.15f to "크게", 1.3f to "아주 크게").forEach { (scale, label) ->
                                FilterChip(
                                    selected = fontScale == scale,
                                    onClick = { fontScale = scale; prefs.fontScale = scale },
                                    label = { Text(label) }
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.md))
                        Text("동기화 상태", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(Spacing.xs))
                        val lastSyncAt = prefs.lastSyncSuccessAtMillis
                        val failCount = prefs.lastSyncFailCount
                        val syncStatusText = when {
                            lastSyncAt <= 0L -> "아직 동기화 기록 없음"
                            failCount > 0 -> "마지막 성공: ${syncElapsedLabel(lastSyncAt)} 전 · 이후 실패 ${failCount}회"
                            else -> "마지막 성공: ${syncElapsedLabel(lastSyncAt)} 전 · 정상"
                        }
                        Text(
                            syncStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (failCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        var showDebugLog by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { showDebugLog = true }) { Text("디버그 로그 보기") }
                        if (showDebugLog) {
                            com.phonelock.app.ui.components.DebugLogDialog(onDismiss = { showDebugLog = false })
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("권한 설정") {
                        val allGranted = accessibilityEnabled && batteryOptIgnored && exactAlarmGranted && isNotificationGranted(context) && mediaAccessGranted
                        Text(
                            if (allGranted) "모든 필수 권한이 설정되어 있습니다." else "일부 권한이 아직 설정되지 않았습니다.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (allGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Text(
                            "알림 / 접근성 서비스 / 백그라운드 실행 보호 / 정확한 알람 / 알림 접근(잠긴 앱 백그라운드 재생 차단) / 삭제 방지를 한 화면에서 확인하고 설정할 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Button(onClick = { showPermissionGuide = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("권한 설정 가이드 열기")
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    run {
                        val crashLogFile = java.io.File(context.filesDir, "crash_log.txt")
                        if (crashLogFile.exists()) {
                            SectionCard("⚠ 마지막 강제종료 로그") {
                                Text(
                                    "앱이 예기치 않게 꺼진 기록이 있습니다. 공유하면 원인을 정확히 찾는 데 도움이 됩니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(Spacing.sm))
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                    Button(onClick = {
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, crashLogFile.readText().takeLast(4000))
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "로그 공유"))
                                    }) { Text("공유") }
                                    OutlinedButton(onClick = { crashLogFile.delete() }) { Text("지우기") }
                                }
                            }
                            Spacer(Modifier.height(Spacing.md))
                        }
                    }

                    SectionCard("업데이트") {
                        var checking by remember { mutableStateOf(false) }
                        var apkUrl by remember { mutableStateOf(repository.pendingUpdateApkUrl()) }
                        var lastOutcome by remember { mutableStateOf<PhoneLockRepository.UpdateCheckOutcome?>(null) }
                        Text(
                            "현재 버전: ${repository.currentVersionCode()} · 초기화 시각이 지나면 하루 1회 자동으로도 확인합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Button(
                            enabled = !checking,
                            onClick = {
                                checking = true
                                scope.launch {
                                    val outcome = repository.checkForUpdateNow()
                                    lastOutcome = outcome
                                    apkUrl = (outcome as? PhoneLockRepository.UpdateCheckOutcome.Available)?.apkUrl
                                    checking = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (checking) "확인 중..." else "지금 확인") }
                        apkUrl?.let { url ->
                            Spacer(Modifier.height(Spacing.sm))
                            UpdateBanner(url)
                        }
                        if (!checking) {
                            when (val outcome = lastOutcome) {
                                is PhoneLockRepository.UpdateCheckOutcome.UpToDate -> {
                                    Spacer(Modifier.height(Spacing.xs))
                                    Text("최신 버전입니다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                is PhoneLockRepository.UpdateCheckOutcome.Failed -> {
                                    Spacer(Modifier.height(Spacing.xs))
                                    Text(
                                        "확인 실패: ${outcome.reason} — 잠시 후 다시 시도해주세요",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                else -> {}
                            }
                        }
                    }
                }

                SettingsCategory.ADMIN -> {
                    SectionCard("관리자 패널") {
                        var pendingUsers by remember { mutableStateOf<List<AccountSyncClient.PendingUser>>(emptyList()) }
                        var approvedUsers by remember { mutableStateOf<List<AccountSyncClient.ApprovedUser>>(emptyList()) }
                        val pendingSelection = remember { mutableStateMapOf<String, AccountSyncClient.Permissions>() }

                        suspend fun refreshAdminLists() {
                            pendingUsers = AccountSyncClient.listPendingUsers(prefs.fbDatabaseUrl, prefs.fbApiKey).getOrDefault(emptyList())
                            approvedUsers = AccountSyncClient.listApprovedUsers(prefs.fbDatabaseUrl, prefs.fbApiKey).getOrDefault(emptyList())
                        }

                        LaunchedEffect(Unit) { refreshAdminLists() }

                        Button(
                            onClick = { scope.launch { refreshAdminLists() } },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("새로고침") }
                        Spacer(Modifier.height(Spacing.md))

                        Text("가입 승인 대기", style = MaterialTheme.typography.titleSmall)
                        if (pendingUsers.isEmpty()) {
                            Text("없음", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            pendingUsers.forEach { user ->
                                Spacer(Modifier.height(Spacing.sm))
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        "${user.customId} · ${user.nickname}" + if (user.isGuest) " (게스트)" else "",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                            .format(java.util.Date(user.requestedAt)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(Spacing.xs))
                                    PermissionChipsRow(
                                        permissions = pendingSelection[user.uid] ?: AccountSyncClient.Permissions.ALL,
                                        onChange = { pendingSelection[user.uid] = it }
                                    )
                                    Spacer(Modifier.height(Spacing.xs))
                                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                        Button(onClick = {
                                            scope.launch {
                                                val perms = pendingSelection[user.uid] ?: AccountSyncClient.Permissions.ALL
                                                AccountSyncClient.approveUser(prefs.fbDatabaseUrl, prefs.fbApiKey, user.uid, perms)
                                                pendingSelection.remove(user.uid)
                                                refreshAdminLists()
                                            }
                                        }) { Text("승인") }
                                        Button(onClick = {
                                            scope.launch {
                                                AccountSyncClient.rejectUser(prefs.fbDatabaseUrl, prefs.fbApiKey, user.uid)
                                                pendingSelection.remove(user.uid)
                                                refreshAdminLists()
                                            }
                                        }) { Text("거절") }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(Spacing.md))
                        Text("승인된 사용자 관리", style = MaterialTheme.typography.titleSmall)
                        if (approvedUsers.isEmpty()) {
                            Text("없음", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            approvedUsers.forEach { user ->
                                Spacer(Modifier.height(Spacing.sm))
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        "${user.customId} · ${user.nickname}" + if (user.isGuest) " (게스트)" else "",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(Modifier.height(Spacing.xs))
                                    PermissionChipsRow(
                                        permissions = user.permissions,
                                        onChange = { updated ->
                                            scope.launch {
                                                AccountSyncClient.updatePermissions(prefs.fbDatabaseUrl, prefs.fbApiKey, user.uid, updated)
                                                refreshAdminLists()
                                            }
                                        }
                                    )
                                    Spacer(Modifier.height(Spacing.xs))
                                    Button(onClick = {
                                        scope.launch {
                                            AccountSyncClient.revokeUser(prefs.fbDatabaseUrl, prefs.fbApiKey, user.uid)
                                            refreshAdminLists()
                                        }
                                    }) { Text("승인취소") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (isTabletWidth()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("설정") },
                    actions = { TextButton(onClick = onClose) { Text("✕ 닫기") } }
                )
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.width(200.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    visibleCategories.forEach { cat ->
                        val selected = category == cat
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = 2.dp).clickable { category = cat },
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                Text(cat.emoji, modifier = Modifier.padding(end = Spacing.sm))
                                Text(
                                    cat.label,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
                detailContent(Modifier.weight(1f).fillMaxHeight())
            }
        }
    } else {
        val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Spacer(Modifier.height(Spacing.md))
                    visibleCategories.forEach { cat ->
                        NavigationDrawerItem(
                            label = { Text("${cat.emoji} ${cat.label}") },
                            selected = category == cat,
                            onClick = {
                                category = cat
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = Spacing.sm)
                        )
                    }
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("${category.emoji} ${category.label}") },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "설정 카테고리")
                            }
                        },
                        actions = { TextButton(onClick = onClose) { Text("✕ 닫기") } }
                    )
                }
            ) { padding ->
                detailContent(Modifier.fillMaxSize().padding(padding))
            }
        }
    }
}

/**
 * "공부 잠금 허용 앱" 접이식 선택 — 앱 목록/검색 로직은 [StudyLockAppsScreen.kt]의
 * `AllowedAppsPickerBody`를 그대로 재사용한다(전체화면판과 코드 중복 없이 공유). 기본은 접힌
 * 상태로 시작해서 헤더를 눌러야만 펼쳐진다(설치 앱이 수십~수백 개라 항상 펼쳐두면 설정 탭이
 * 지나치게 길어짐). 32차에 타이머 탭 인라인 섹션으로 만들어졌다가 90차에 설정 > 공부 탭으로
 * 옮겨왔고, 그때 관리 탭에 따로 있던 "허용 앱 선택"(전체화면) 버튼도 여기로 합쳤다.
 */
@Composable
private fun AllowedAppsCollapsibleSection(onOpenFullScreen: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var expanded by remember { mutableStateOf(false) }
    val allowedCount = prefs.studyLockAllowedPackages.size

    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.padding(end = Spacing.xs)
                )
                Text(
                    "🔒 공부 잠금 허용 앱" + if (allowedCount > 0) " ($allowedCount)" else "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
            if (expanded) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "공부앱 타이머가 \"공부\" 페이즈로 진행 중일 때(휴식 중엔 아님) 여기서 고른 앱 외에는 열자마자 " +
                        "감지해서 잠금 화면으로 돌려보냅니다. 기기 소유자 권한이 없어 진짜 실행 차단은 아니고, " +
                        "감지 후 재차단하는 베스트 에포트 방식입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                AllowedAppsPickerBody(prefs = prefs, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Spacing.sm))
                Button(onClick = onOpenFullScreen, modifier = Modifier.fillMaxWidth()) {
                    Text("전체 화면에서 고르기")
                }
            }
        }
    }
}

/** 관리자 패널에서 사용자별 기능 범위(홈/루틴/공부/규칙/모임)를 고르는 칩 5개 — 눌린 것만 허용. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PermissionChipsRow(
    permissions: AccountSyncClient.Permissions,
    onChange: (AccountSyncClient.Permissions) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        // 122차(사용자 요청): 라벨을 "홈(식물)" → "홈"으로 통일하고, 앱의 첫 탭인 만큼 가장 왼쪽으로 옮겼다.
        FilterChip(
            selected = permissions.plant,
            onClick = { onChange(permissions.copy(plant = !permissions.plant)) },
            label = { Text("홈") }
        )
        FilterChip(
            selected = permissions.routine,
            onClick = { onChange(permissions.copy(routine = !permissions.routine)) },
            label = { Text("루틴") }
        )
        FilterChip(
            selected = permissions.study,
            onClick = { onChange(permissions.copy(study = !permissions.study)) },
            label = { Text("공부") }
        )
        FilterChip(
            selected = permissions.manage,
            onClick = { onChange(permissions.copy(manage = !permissions.manage)) },
            label = { Text("규칙") }
        )
        FilterChip(
            selected = permissions.social,
            onClick = { onChange(permissions.copy(social = !permissions.social)) },
            label = { Text("모임") }
        )
    }
}
