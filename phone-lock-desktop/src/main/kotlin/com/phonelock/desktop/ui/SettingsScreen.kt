package com.phonelock.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.data.*
import com.phonelock.desktop.monitor.AccountSyncClient
import com.phonelock.desktop.monitor.AuthManager
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.components.ToggleRow
import com.phonelock.desktop.ui.theme.Spacing
import java.io.File

/** 저장(내보내기) 파일 선택 창 — StatsScreen.exportUsageCsvToFile()과 동일한 java.awt.FileDialog 패턴. */
private fun pickSaveFile(title: String, defaultName: String): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    val fileName = if (name.endsWith(".json", ignoreCase = true)) name else "$name.json"
    return File(dir, fileName)
}

/** 열기(가져오기) 파일 선택 창. */
private fun pickOpenFile(title: String): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(dir, name)
}

private enum class SettingsSubTab { COMMON, ROUTINE, STUDY, MANAGE, SOCIAL }

/**
 * 설정 화면 — 공통/루틴/공부/관리 4개 서브탭으로 세분화(MainScreen의 MANAGE/STUDY 서브탭과 동일한
 * TabRow 패턴). 기존 SectionCard들은 로직 변경 없이 재배치만 했다:
 * 공통 = 테마/일일한도초기화시각/계정동기화/모임 공유 설정(신규)/일일백업복원/
 *        설정그룹내보내기가져오기/오래된통계정리/종료방지
 * 루틴 = 루틴스트릭알림/루틴내보내기가져오기, 공부 = (자리만, 현재 항목 없음), 관리 = 릴스쇼츠차단
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(repository: Repository, onThemeChange: (String) -> Unit = {}) {
    var settingsSubTab by remember { mutableIntStateOf(0) }
    var themeMode by remember { mutableStateOf(repository.themeMode) }
    var customBgText by remember { mutableStateOf(repository.customThemeBackground) }
    var customAccentText by remember { mutableStateOf(repository.customThemeAccent) }
    // 79차(사용자 요청): "종료 확인 절차"는 관리(차단) 기능의 꼼수 방지 장치이므로 켜고 끌 수 있게 하되,
    // 켜짐→꺼짐으로 바꾸는 것 자체를 같은 회유 멘트 20개 절차로 보호한다(showExitConfirmGate).
    var exitConfirmEnabled by remember { mutableStateOf(repository.exitConfirmEnabled) }
    var defaultMultiPassEnabled by remember { mutableStateOf(repository.defaultMultiPassEnabled) }
    var defaultPassCount by remember { mutableStateOf(repository.defaultPassCount) }
    var defaultPassIntervals by remember {
        mutableStateOf(com.phonelock.shared.calc.PassSchedule.parsePassIntervals(repository.defaultPassIntervalsCsv, repository.defaultPassCount))
    }
    var showExitConfirmGate by remember { mutableStateOf(false) }
    var dailyResetHourText by remember { mutableStateOf(repository.dailyResetHour.toString()) }
    var launchAtStartup by remember { mutableStateOf(com.phonelock.desktop.isLaunchAtStartupEnabled()) }
    var blockReels by remember { mutableStateOf(repository.blockReels) }
    var blockShorts by remember { mutableStateOf(repository.blockShorts) }
    var routineStreakNotifyEnabled by remember { mutableStateOf(repository.routineStreakNotifyEnabled) }
    var googleEmail by remember { mutableStateOf(AuthManager.currentLoginId ?: AuthManager.currentEmail) }
    var backups by remember { mutableStateOf(repository.listBackups()) }
    var pendingRestoreFile by remember { mutableStateOf<File?>(null) }
    var pendingImportFile by remember { mutableStateOf<File?>(null) }
    var pendingRoutineImportFile by remember { mutableStateOf<File?>(null) }

    // 닉네임 설정
    var nicknameText by remember { mutableStateOf("") }
    var nicknameSaving by remember { mutableStateOf(false) }
    var nicknameMessage by remember { mutableStateOf<String?>(null) }

    // 관리자 패널(가입 승인) — usernames/BEULTAEON == 내 uid일 때만 표시.
    var isAdmin by remember { mutableStateOf(false) }
    var pendingUsers by remember { mutableStateOf<List<AccountSyncClient.PendingUser>>(emptyList()) }
    var approvedUsers by remember { mutableStateOf<List<AccountSyncClient.ApprovedUser>>(emptyList()) }
    var adminListLoading by remember { mutableStateOf(false) }
    var adminError by remember { mutableStateOf<String?>(null) }
    // 대기 중인 사용자를 승인할 때 고를 권한 — 기본은 전부 허용, uid별로 독립적으로 고른다.
    val pendingSelection = remember { mutableStateMapOf<String, AccountSyncClient.Permissions>() }

    fun refreshAdminLists() {
        val url = repository.fbDatabaseUrl
        val key = repository.fbApiKey
        adminListLoading = true
        adminError = null
        Thread {
            val pendingResult = AccountSyncClient.listPendingUsers(url, key)
            val approvedResult = AccountSyncClient.listApprovedUsers(url, key)
            adminListLoading = false
            pendingResult.onSuccess { pendingUsers = it }.onFailure { e -> adminError = e.message ?: "목록을 불러오지 못했습니다." }
            approvedResult.onSuccess { approvedUsers = it }
        }.start()
    }

    LaunchedEffect(AuthManager.isSignedIn) {
        if (!AuthManager.isSignedIn) {
            isAdmin = false
            return@LaunchedEffect
        }
        val url = repository.fbDatabaseUrl
        val key = repository.fbApiKey
        Thread {
            val admin = AccountSyncClient.isAdmin(url, key)
            isAdmin = admin
            if (admin) refreshAdminLists()
        }.start()
    }

    pendingRestoreFile?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingRestoreFile = null },
            title = { Text("복원 확인") },
            text = { Text("복원하면 현재 그룹/기록이 ${file.name} 백업 내용으로 완전히 대체됩니다(되돌리기 없음). 계속할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    repository.restoreFromBackup(file)
                    pendingRestoreFile = null
                }) { Text("복원") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreFile = null }) { Text("취소") }
            }
        )
    }

    pendingImportFile?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingImportFile = null },
            title = { Text("가져오기 확인") },
            text = { Text("가져오면 현재 그룹/기록이 ${file.name} 파일 내용으로 완전히 대체됩니다(되돌리기 없음). 계속할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    repository.restoreFromBackup(file)
                    pendingImportFile = null
                }) { Text("가져오기") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportFile = null }) { Text("취소") }
            }
        )
    }

    pendingRoutineImportFile?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingRoutineImportFile = null },
            title = { Text("루틴 가져오기 확인") },
            text = { Text("가져오면 현재 루틴/체크 기록이 ${file.name} 파일 내용으로 완전히 대체됩니다(되돌리기 없음). 계속할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    repository.importRoutinesBackupJson(file.readText())
                    pendingRoutineImportFile = null
                }) { Text("가져오기") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRoutineImportFile = null }) { Text("취소") }
            }
        )
    }

    if (showExitConfirmGate) {
        androidx.compose.ui.window.Window(
            onCloseRequest = { showExitConfirmGate = false },
            title = "종료 확인 절차 끄기",
            undecorated = true,
            alwaysOnTop = true,
            state = androidx.compose.ui.window.rememberWindowState(placement = androidx.compose.ui.window.WindowPlacement.Maximized)
        ) {
            com.phonelock.desktop.ui.theme.PhoneLockTheme(repository.currentPalette()) {
                ExitConfirmScreen(
                    title = "정말 종료 확인 절차를 끌까요?",
                    finalLabel = "끄기",
                    onConfirmExit = {
                        exitConfirmEnabled = false
                        repository.exitConfirmEnabled = false
                        showExitConfirmGate = false
                    },
                    onCancel = { showExitConfirmGate = false }
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("설정", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(Spacing.md))

        TabRow(
            selectedTabIndex = settingsSubTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            // 관리자가 승인 시 지정한 기능 범위(MainScreen.kt의 permXxx와 동일)에 맞춰 해당 서브탭만
            // 보여준다 — "공통"은 로그아웃 등 항상 필요한 항목이라 예외로 항상 표시. 본문 각 섹션은
            // 여전히 고정 인덱스(0~4)로 분기하므로 숨긴 탭은 그냥 선택 불가능해질 뿐이다.
            Tab(selected = settingsSubTab == 0, onClick = { settingsSubTab = 0 }, text = { Text("공통") })
            if (repository.permRoutine) {
                Tab(selected = settingsSubTab == 1, onClick = { settingsSubTab = 1 }, text = { Text("루틴") })
            }
            if (repository.permStudy) {
                Tab(selected = settingsSubTab == 2, onClick = { settingsSubTab = 2 }, text = { Text("공부") })
            }
            if (repository.permManage) {
                Tab(selected = settingsSubTab == 3, onClick = { settingsSubTab = 3 }, text = { Text("관리") })
            }
            if (repository.permSocial) {
                Tab(selected = settingsSubTab == 4, onClick = { settingsSubTab = 4 }, text = { Text("모임") })
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md)
        ) {
            when (SettingsSubTab.entries[settingsSubTab]) {
                SettingsSubTab.COMMON -> {
                    SectionCard("테마") {
                        Text(
                            "앱 전체 배경/포인트 색과 차단/실행확인 화면 강조색, 브라우저 확장 색까지 함께 바뀝니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            com.phonelock.desktop.ui.theme.THEME_DISPLAY_NAMES.forEach { (mode, label) ->
                                FilterChip(
                                    selected = themeMode == mode,
                                    onClick = { themeMode = mode; repository.themeMode = mode; onThemeChange(mode) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        // 79차(사용자 요청): 배경색/포인트색 두 개만 직접 골라 나만의 테마를 만드는 기능.
                        // 나머지 색(텍스트/카드/보조색 등)은 buildCustomPalette()가 이 둘로부터 자동 계산한다.
                        if (themeMode == com.phonelock.desktop.ui.theme.ThemeMode.CUSTOM) {
                            Spacer(Modifier.height(Spacing.sm))
                            val bgPreview = com.phonelock.desktop.ui.theme.parseHexColor(customBgText)
                            val accentPreview = com.phonelock.desktop.ui.theme.parseHexColor(customAccentText)
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = customBgText,
                                    onValueChange = { text ->
                                        customBgText = text
                                        if (com.phonelock.desktop.ui.theme.parseHexColor(text) != null) {
                                            repository.customThemeBackground = text.trim()
                                            onThemeChange(themeMode)
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
                                )
                            }
                            Spacer(Modifier.height(Spacing.sm))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = customAccentText,
                                    onValueChange = { text ->
                                        customAccentText = text
                                        if (com.phonelock.desktop.ui.theme.parseHexColor(text) != null) {
                                            repository.customThemeAccent = text.trim()
                                            onThemeChange(themeMode)
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
                                )
                            }
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "\"#RRGGBB\" 형식(예: #FF9800)으로 입력하세요. 배경 밝기로 라이트/다크를 자동 판정하고, 나머지 색은 두 색을 섞어 자동으로 맞춥니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("자동 실행") {
                        ToggleRow(
                            title = "컴퓨터 시작 시 자동 실행",
                            description = "Windows 로그인 시 이 계정으로 앱이 자동으로 켜집니다.",
                            checked = launchAtStartup,
                            onCheckedChange = { checked ->
                                launchAtStartup = checked
                                com.phonelock.desktop.setLaunchAtStartupEnabled(checked)
                            }
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("계정 동기화 (로그인 필수)") {
                        Text(
                            "동기화(실행확인 레벨/스누즈/일일사용량/캘린더/계산기/루틴)는 이제 로그인이 있어야만 " +
                                "작동합니다. 같은 계정으로 로그인한 기기끼리 자동으로 연결됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        if (AuthManager.isSignedIn) {
                            Text("로그인됨: ${googleEmail ?: AuthManager.currentUid}", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(Spacing.sm))
                            Button(
                                onClick = {
                                    AuthManager.signOut()
                                    googleEmail = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("로그아웃") }
                        } else {
                            Text(
                                "로그아웃되었습니다. 앱을 다시 시작해서 로그인해주세요.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (AuthManager.isSignedIn) {
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
                                                val url = repository.fbDatabaseUrl
                                                val key = repository.fbApiKey
                                                deleting = true
                                                deleteError = null
                                                Thread {
                                                    val delResult = AccountSyncClient.deleteMyData(url, key)
                                                    val authResult = if (key != null) AuthManager.deleteAccount(key) else Result.failure(IllegalStateException("Firebase 설정이 비어있습니다."))
                                                    deleting = false
                                                    if (authResult.isSuccess) {
                                                        showDeleteConfirm = false
                                                        googleEmail = null
                                                    } else {
                                                        deleteError = delResult.exceptionOrNull()?.message
                                                            ?: authResult.exceptionOrNull()?.message
                                                            ?: "삭제 실패"
                                                    }
                                                }.start()
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
                    Spacer(Modifier.height(Spacing.md))

                    if (AuthManager.isSignedIn && !AuthManager.isAnonymous) {
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
                                    val apiKey = repository.fbApiKey
                                    if (apiKey.isNullOrBlank()) {
                                        pwResult = "Firebase 설정이 비어있습니다."
                                        return@Button
                                    }
                                    pwSaving = true
                                    pwResult = null
                                    Thread {
                                        val result = AuthManager.changePassword(newPassword, apiKey)
                                        pwSaving = false
                                        result.onSuccess {
                                            pwResult = "변경되었습니다."
                                            newPassword = ""
                                            newPasswordConfirm = ""
                                        }
                                        result.onFailure { e ->
                                            pwResult = e.message ?: "변경 실패 — 오래 전에 로그인했다면 로그아웃 후 다시 로그인해서 시도해주세요."
                                        }
                                    }.start()
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

                    SectionCard("닉네임 설정") {
                        OutlinedTextField(
                            value = nicknameText,
                            onValueChange = { nicknameText = it },
                            label = { Text("닉네임 (1~20자)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Button(
                            onClick = {
                                val trimmed = nicknameText.trim()
                                if (trimmed.isEmpty() || trimmed.length > 20) {
                                    nicknameMessage = "닉네임은 1~20자여야 합니다."
                                    return@Button
                                }
                                val url = repository.fbDatabaseUrl
                                val key = repository.fbApiKey
                                nicknameSaving = true
                                nicknameMessage = null
                                Thread {
                                    val result = AccountSyncClient.updateNickname(url, key, trimmed)
                                    nicknameSaving = false
                                    result.onSuccess { nicknameMessage = "저장되었습니다." }
                                    result.onFailure { e -> nicknameMessage = e.message ?: "저장 실패" }
                                }.start()
                            },
                            enabled = !nicknameSaving,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (nicknameSaving) "저장 중..." else "저장") }
                        nicknameMessage?.let { msg ->
                            Spacer(Modifier.height(Spacing.xs))
                            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    if (isAdmin) {
                        SectionCard("관리자 패널 — 가입 승인 대기") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    "새로 가입 신청한 사용자를 승인/거절합니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(onClick = { refreshAdminLists() }, enabled = !adminListLoading) {
                                    Text(if (adminListLoading) "새로고침 중..." else "새로고침")
                                }
                            }
                            adminError?.let { msg ->
                                Spacer(Modifier.height(Spacing.xs))
                                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(Modifier.height(Spacing.sm))
                            if (pendingUsers.isEmpty()) {
                                Text("대기 중인 신청이 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                pendingUsers.forEach { user ->
                                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Column {
                                                Text("${user.customId} · ${user.nickname}" + (if (user.isGuest) " (게스트)" else ""), style = MaterialTheme.typography.bodyMedium)
                                                Text(
                                                    java.time.Instant.ofEpochMilli(user.requestedAt).toString(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                                Button(onClick = {
                                                    val url = repository.fbDatabaseUrl
                                                    val key = repository.fbApiKey
                                                    val perms = pendingSelection[user.uid] ?: AccountSyncClient.Permissions.ALL
                                                    Thread {
                                                        AccountSyncClient.approveUser(url, key, user.uid, perms)
                                                        refreshAdminLists()
                                                    }.start()
                                                    pendingSelection.remove(user.uid)
                                                }) { Text("승인") }
                                                OutlinedButton(onClick = {
                                                    val url = repository.fbDatabaseUrl
                                                    val key = repository.fbApiKey
                                                    Thread {
                                                        AccountSyncClient.rejectUser(url, key, user.uid)
                                                        refreshAdminLists()
                                                    }.start()
                                                    pendingSelection.remove(user.uid)
                                                }) { Text("거절") }
                                            }
                                        }
                                        Spacer(Modifier.height(Spacing.xs))
                                        PermissionChipsRow(
                                            permissions = pendingSelection[user.uid] ?: AccountSyncClient.Permissions.ALL,
                                            onChange = { pendingSelection[user.uid] = it }
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(Spacing.md))

                        SectionCard("관리자 패널 — 승인된 사용자 관리") {
                            if (approvedUsers.isEmpty()) {
                                Text("승인된 사용자가 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                approvedUsers.forEach { user ->
                                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("${user.customId} · ${user.nickname}" + (if (user.isGuest) " (게스트)" else ""), style = MaterialTheme.typography.bodyMedium)
                                            OutlinedButton(onClick = {
                                                val url = repository.fbDatabaseUrl
                                                val key = repository.fbApiKey
                                                Thread {
                                                    AccountSyncClient.revokeUser(url, key, user.uid)
                                                    refreshAdminLists()
                                                }.start()
                                            }) { Text("승인 취소") }
                                        }
                                        Spacer(Modifier.height(Spacing.xs))
                                        PermissionChipsRow(
                                            permissions = user.permissions,
                                            onChange = { updated ->
                                                val url = repository.fbDatabaseUrl
                                                val key = repository.fbApiKey
                                                Thread {
                                                    AccountSyncClient.updatePermissions(url, key, user.uid, updated)
                                                    refreshAdminLists()
                                                }.start()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(Spacing.md))
                    }

                    SectionCard("일일 백업 / 복원") {
                        Text(
                            "앱 시작 시 하루 한 번 전체 데이터(그룹/사용시간/캘린더/계산기 등)를 자동 백업합니다. " +
                                "최근 7일치를 보관하며, 복원하면 현재 데이터가 완전히 대체됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        if (backups.isEmpty()) {
                            Text("아직 백업이 없습니다(다음 앱 재시작 시 처음 만들어집니다).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            backups.forEach { file ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(file.name.removePrefix("backup_").removeSuffix(".json"), style = MaterialTheme.typography.bodyMedium)
                                    OutlinedButton(onClick = { pendingRestoreFile = file }) { Text("이 시점으로 복원") }
                                }
                            }
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        Button(onClick = { backups = repository.listBackups() }) { Text("목록 새로고침") }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("설정/그룹 내보내기 · 가져오기") {
                        Text(
                            "기기 교체나 재설치 시 현재 데이터 전체(그룹/사용시간/캘린더/계산기 등)를 원하는 위치에 파일로 저장하거나, " +
                                "저장해둔 파일에서 그대로 불러올 수 있습니다. 위 자동 백업과 달리 파일 위치를 직접 고를 수 있어 다른 PC로 옮길 때 유용합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Button(onClick = {
                                pickSaveFile("설정/그룹 내보내기", "phonelock_export_${java.time.LocalDate.now()}.json")?.let { file ->
                                    repository.exportDataToFile(file)
                                }
                            }) { Text("📤 내보내기") }
                            OutlinedButton(onClick = {
                                pickOpenFile("설정/그룹 가져오기")?.let { file -> pendingImportFile = file }
                            }) { Text("📥 가져오기") }
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("자동 백업 (Firebase)") {
                        var cloudBackupEnabled by remember { mutableStateOf(repository.cloudBackupEnabled) }
                        ToggleRow(
                            title = "매일 자동으로 클라우드에 백업",
                            checked = cloudBackupEnabled,
                            onCheckedChange = { checked ->
                                cloudBackupEnabled = checked
                                repository.cloudBackupEnabled = checked
                            }
                        )
                        Text(
                            "로그인이 필요하며, Firebase 콘솔에서 Storage를 먼저 활성화해야 동작합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        var lastResultText by remember { mutableStateOf(repository.lastCloudBackupResult) }
                        if (lastResultText.isNotBlank()) {
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                lastResultText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (lastResultText.contains("성공")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        val scope = rememberCoroutineScope()
                        Button(onClick = {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val result = repository.uploadCloudBackupNow()
                                lastResultText = if (result.isSuccess) "성공 (${result.getOrNull()})" else "실패: ${result.exceptionOrNull()?.message}"
                            }
                        }) { Text("지금 클라우드에 백업") }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("오래된 통계 데이터 정리") {
                        var lastResult by remember { mutableStateOf<Int?>(null) }
                        Text(
                            "12개월 이상 지난 사용시간/재확인 통과 횟수/공부 기록을 영구 삭제합니다(되돌리기 없음). " +
                                "캘린더 일정과 스트릭 계산에는 영향을 주지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Button(onClick = { lastResult = repository.pruneOldStats(12) }) { Text("🧹 12개월 이상 지난 기록 정리") }
                        lastResult?.let {
                            Spacer(Modifier.height(Spacing.xs))
                            Text("$it 건 삭제됨", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("업데이트") {
                        var checking by remember { mutableStateOf(false) }
                        var installerUrl by remember { mutableStateOf(repository.pendingUpdateInstallerUrl()) }
                        var lastOutcome by remember { mutableStateOf<Repository.UpdateCheckOutcome?>(null) }
                        Text(
                            "현재 빌드: ${repository.currentBuildTimestamp()} · 초기화 시각이 지나면 하루 1회 자동으로도 확인합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Button(enabled = !checking, onClick = {
                            checking = true
                            repository.checkForUpdateNow { outcome ->
                                lastOutcome = outcome
                                installerUrl = (outcome as? Repository.UpdateCheckOutcome.Available)?.installerUrl
                                checking = false
                            }
                        }) {
                            Text(if (checking) "확인 중..." else "지금 확인")
                        }
                        installerUrl?.let { url ->
                            Spacer(Modifier.height(Spacing.sm))
                            UpdateBanner(repository, url)
                        }
                        if (!checking) {
                            when (val outcome = lastOutcome) {
                                is Repository.UpdateCheckOutcome.UpToDate -> {
                                    Spacer(Modifier.height(Spacing.xs))
                                    Text("최신 버전입니다", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                is Repository.UpdateCheckOutcome.Failed -> {
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
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("종료 방지") {
                        Text(
                            "감시 프로세스와 작업 스케줄러가 함께 지켜보다가, 작업 관리자로 강제종료해도 자동으로 다시 실행됩니다. " +
                                "트레이 메뉴의 \"종료\"로 10분 대기를 마치고 정식으로 나가야만 꺼진 상태가 유지됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                SettingsSubTab.SOCIAL -> {
                    SectionCard("모임 공유 설정") {
                        Text(
                            "모임마다 공개할 내 정보(루틴/공부/스트릭/오늘 일정/공부중 여부/작동 중인 관리 그룹)를 " +
                                "다르게 정할 수 있어, 여기가 아니라 각 모임 화면의 🔒 공유 설정에서 모임별로 관리합니다. " +
                                "특정 멤버에게만 내 정보를 숨기거나 특정 멤버의 정보를 안 보이게 하는 것도 그 " +
                                "멤버의 상세 화면에서 따로 설정할 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "무전기(음성/텍스트 메시지) 수신 설정도 모임마다 다르게 정할 수 있어 여기가 아니라 각 " +
                            "모임 화면의 ⚙ 무전기 설정에서 관리합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SettingsSubTab.ROUTINE -> {
                    SectionCard("루틴 스트릭 알림") {
                        ToggleRow(
                            title = "스트릭 알림 받기",
                            checked = routineStreakNotifyEnabled,
                            onCheckedChange = { checked ->
                                routineStreakNotifyEnabled = checked
                                repository.routineStreakNotifyEnabled = checked
                            }
                        )
                        Text(
                            "하루 중 랜덤한 시각에 어제 루틴 스트릭 상태를 트레이 알림으로 알려줍니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("루틴 내보내기 / 가져오기") {
                        Text(
                            "루틴 목록과 체크 기록만 파일로 저장하거나 불러옵니다. 루틴은 이미 Firebase로 기기 간 자동 " +
                                "동기화되지만, 위 전체 백업과 달리 루틴만 골라서 다른 계정으로 옮기거나 별도 보관할 때 씁니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Button(onClick = {
                                pickSaveFile("루틴 내보내기", "phonelock_routines_${java.time.LocalDate.now()}.json")?.let { file ->
                                    file.writeText(repository.exportRoutinesBackupJson())
                                }
                            }) { Text("📤 내보내기") }
                            OutlinedButton(onClick = {
                                pickOpenFile("루틴 가져오기")?.let { file -> pendingRoutineImportFile = file }
                            }) { Text("📥 가져오기") }
                        }
                    }
                }

                SettingsSubTab.STUDY -> {
                    SectionCard("캘린더 다회독 기본값") {
                        ToggleRow(
                            title = "새 일정을 다회독으로 시작",
                            description = "켜두면 캘린더에 새로 추가하는 일정이 완료(O) 시 다음 회독을 자동 생성하는 상태로 시작됩니다. 이미 만든 일정에는 영향 없고, 각 일정에서 개별적으로 다시 켜고 끌 수 있습니다.",
                            checked = defaultMultiPassEnabled,
                            onCheckedChange = { checked ->
                                defaultMultiPassEnabled = checked
                                repository.defaultMultiPassEnabled = checked
                            }
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "계산기 업무와 연결하지 않고 캘린더에서 직접 추가하는 일정에 적용되는 기본 회독 수/간격입니다 " +
                                "(계산기 업무는 업무별로 각 업무 입력 카드에서 따로 설정).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        com.phonelock.desktop.ui.components.NumberStepperField(
                            label = "기본 회독 수",
                            value = defaultPassCount.toString(),
                            onValueChange = { text ->
                                val newCount = (text.toIntOrNull() ?: defaultPassCount)
                                    .coerceIn(com.phonelock.shared.calc.PassSchedule.MIN_PASS_COUNT, com.phonelock.shared.calc.PassSchedule.MAX_PASS_COUNT)
                                defaultPassCount = newCount
                                repository.defaultPassCount = newCount
                                defaultPassIntervals = com.phonelock.shared.calc.PassSchedule.defaultPassIntervals(newCount)
                                repository.defaultPassIntervalsCsv = defaultPassIntervals.joinToString(",")
                            },
                            min = com.phonelock.shared.calc.PassSchedule.MIN_PASS_COUNT,
                            max = com.phonelock.shared.calc.PassSchedule.MAX_PASS_COUNT,
                            modifier = Modifier.width(160.dp)
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text("회독별 간격(일)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            defaultPassIntervals.forEachIndexed { i, days ->
                                com.phonelock.desktop.ui.components.NumberStepperField(
                                    label = "${i + 1}→${i + 2}회독",
                                    value = days.toString(),
                                    onValueChange = { text ->
                                        val newDays = (text.toIntOrNull() ?: days).coerceIn(1, 90)
                                        val updated = defaultPassIntervals.toMutableList().also { it[i] = newDays }
                                        defaultPassIntervals = updated
                                        repository.defaultPassIntervalsCsv = updated.joinToString(",")
                                    },
                                    min = 1, max = 90,
                                    modifier = Modifier.width(140.dp)
                                )
                            }
                        }
                    }
                }

                SettingsSubTab.MANAGE -> {
                    SectionCard("일일 사용 한도 초기화 시각") {
                        OutlinedTextField(
                            value = dailyResetHourText,
                            onValueChange = { text ->
                                dailyResetHourText = text
                                text.toIntOrNull()?.let { if (it in 0..23) repository.dailyResetHour = it }
                            },
                            label = { Text("초기화 시각 (0~23시)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "이 시각이 되면 그룹별 오늘 사용 시간이 초기화됩니다. (캘린더/공부기록의 \"오늘\" 판정도 이 시각을 기준으로 함께 바뀝니다.)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("앱 종료 확인 절차") {
                        ToggleRow(
                            title = "종료 시 회유 멘트 20개 확인",
                            description = "꺼두면 트레이 \"종료\"를 눌렀을 때 이 확인 없이 바로 꺼집니다.",
                            checked = exitConfirmEnabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    exitConfirmEnabled = true
                                    repository.exitConfirmEnabled = true
                                } else {
                                    // 끄는 것 자체를 같은 절차로 보호 — 바로 끄지 않고 확인 게이트를 띄운다.
                                    showExitConfirmGate = true
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("릴스/쇼츠 차단") {
                        ToggleRow(
                            title = "릴스 차단 (인스타그램)",
                            checked = blockReels,
                            onCheckedChange = { checked ->
                                blockReels = checked
                                repository.blockReels = checked
                            }
                        )
                        ToggleRow(
                            title = "쇼츠 차단 (유튜브)",
                            checked = blockShorts,
                            onCheckedChange = { checked ->
                                blockShorts = checked
                                repository.blockShorts = checked
                            }
                        )
                        Text(
                            "브라우저 확장프로그램이 youtube.com/shorts, instagram.com/reels URL을 감지해서 차단합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 관리자 패널에서 사용자별 기능 범위(루틴/공부/관리/모임)를 고르는 칩 4개 — 눌린 것만 허용(안드로이드판과 대칭). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PermissionChipsRow(
    permissions: AccountSyncClient.Permissions,
    onChange: (AccountSyncClient.Permissions) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
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
            label = { Text("관리") }
        )
        FilterChip(
            selected = permissions.social,
            onClick = { onChange(permissions.copy(social = !permissions.social)) },
            label = { Text("모임") }
        )
    }
}
