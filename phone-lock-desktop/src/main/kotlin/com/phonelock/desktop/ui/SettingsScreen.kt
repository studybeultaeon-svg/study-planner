package com.phonelock.desktop.ui

import com.phonelock.desktop.ui.components.LedgerAlertDialog
import com.phonelock.desktop.ui.components.PageMasthead
import com.phonelock.desktop.ui.components.Hairline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.phonelock.shared.PERSUASION_MESSAGES
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

/** 118차: 설정 카테고리 — 탭(TabRow) 대신 좌측 카테고리 목록 + 우측 세부 설정 2단 구조로 개편했다.
 *  기존 SettingsSubTab(COMMON/ROUTINE/STUDY/MANAGE/SOCIAL) 5분류를 성격별로 더 잘게 나눴다: 계정 관련
 *  카드는 PROFILE로, 테마는 DISPLAY로, 백업/내보내기/정리는 DATA로, 자동실행/업데이트/워치독/종료확인은
 *  SYSTEM으로, 관리자 전용 카드는 ADMIN으로 독립시켰다. */
private enum class SettingsCategory(val label: String, val icon: ImageVector, val summary: String) {
    PROFILE("프로필", Icons.Outlined.Person, "닉네임 · 프로필 사진 · 로그인 및 보안"),
    DISPLAY("화면", Icons.Outlined.Palette, "테마 · 미니멀(성능) 모드"),
    RULES("관리", Icons.Outlined.Shield, "수정·삭제 방지 · 릴스/쇼츠 · 하루 시작 기준"),
    STUDY("집중", Icons.Outlined.Timer, "반복 기본값 · 집중 알림 · 허용 프로그램과 사이트"),
    ROUTINE("루틴", Icons.Outlined.TaskAlt, "연속 기록 알림 · 방지권 · 내보내기"),
    SOCIAL("모임", Icons.Outlined.Groups, "모임 공유 설정"),
    DATA("데이터", Icons.Outlined.Backup, "백업 · 복원 · 오래된 기록 정리"),
    SYSTEM("시스템", Icons.Outlined.Tune, "자동 실행 · 업데이트 · 워치독"),
    HELP("도움말", Icons.Outlined.HelpOutline, "기능 설명과 자주 묻는 질문"),
    ADMIN("관리자 패널", Icons.Outlined.AdminPanelSettings, "가입 승인 · 권한")
}

/**
 * 90차(사용자 요청): 설정 카드들이 넓은 데스크탑 창에서도 한 줄로만 길게 쌓여 좌우 공간을 못 쓰던 문제를
 * 해결하는 배치 전용 래퍼. 카드 순서/내용/로직은 그대로 두고 어느 컬럼에 놓을지만 정한다. 창이 좁아지면
 * (ResponsiveSplit의 임계값과 같은 맥락으로) 기존처럼 한 컬럼으로 되돌아가 위아래로 쌓인다.
 * ResponsiveSplit을 쓰지 않는 이유: 이 화면은 바깥 Column이 이미 verticalScroll이라 높이가 무한이고,
 * ResponsiveSplit은 fillMaxSize()+weight로 유한한 높이를 전제하기 때문(중첩 스크롤 충돌).
 */
@Composable
private fun SettingsColumns(
    narrowBreakpoint: androidx.compose.ui.unit.Dp = 700.dp,
    left: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    right: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < narrowBreakpoint) {
            Column(Modifier.fillMaxWidth()) {
                left()
                Spacer(Modifier.height(Spacing.md))
                right()
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Column(Modifier.weight(1f)) { left() }
                Column(Modifier.weight(1f)) { right() }
            }
        }
    }
}

/**
 * 설정 화면 — 118차부터 탭이 아니라 홈(식물) 화면 우상단 버튼으로 들어오는 전용 화면이 되었고, 내부
 * 구조도 좌측 카테고리 목록(약 20%) + 우측 세부 설정(약 80%)으로 바뀌었다. 기존 SectionCard들은 로직
 * 변경 없이 카테고리별로 재배치만 했다 — 아이디 변경(프로필 카테고리)만 이번에 신규 추가.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    repository: Repository,
    onThemeChange: (String) -> Unit = {},
    onClose: () -> Unit = {}
) {
    var themeMode by remember { mutableStateOf(repository.themeMode) }
    var customBgText by remember { mutableStateOf(repository.customThemeBackground) }
    var customAccentText by remember { mutableStateOf(repository.customThemeAccent) }
    var showBgPalette by remember { mutableStateOf(false) }
    var showAccentPalette by remember { mutableStateOf(false) }
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
    // 루틴 연속 기록 방지권(142차) — 아래 동기화 이펙트가 값을 갱신하므로 그보다 먼저 선언한다.
    var routineStreakFreeze by remember { mutableStateOf(repository.routineStreakFreezePerWeek) }
    // 차단 규칙 수정·삭제 방지(129차, 사용자 요청) — 128차까지 11~23시로 하드코딩이던 걸 설정으로 뺐다.
    var editProtectionEnabled by remember { mutableStateOf(repository.editProtectionEnabled) }
    var editProtectionStartText by remember { mutableStateOf(repository.editProtectionStartHour.toString()) }
    var editProtectionEndText by remember { mutableStateOf(repository.editProtectionEndHour.toString()) }
    // 134차: 저장된 값과 입력칸 값을 분리한다 — 예전엔 글자를 칠 때마다 저장·판정해서, 방지 시간대 밖에서
    // "11" → "10"으로 고치는 도중의 중간값("1" = 1~23시)이 잠깐 저장되며 그 순간 방지 시간대가 돼버렸고,
    // 이어지는 타이핑이 확인 질문에 막혔다(안드로이드판과 대칭 수정).
    var savedProtectionStart by remember { mutableStateOf(repository.editProtectionStartHour) }
    var savedProtectionEnd by remember { mutableStateOf(repository.editProtectionEndHour) }
    // 방지를 끄거나 시간대를 좁혀 "지금"이 방지 밖으로 빠지는 변경은 그 한 번으로 모든 보호를 걷어내는
    // 새 우회로라, 79차 "종료 확인 절차 끄기"와 같이 회유 멘트 20개(ExitConfirmScreen)로 게이트한다 —
    // 반대로 켜거나 넓히는 방향은 즉시 적용. null이 아니면 게이트 진행 중.
    var pendingProtection by remember { mutableStateOf<Triple<Boolean, Int, Int>?>(null) }
    // 85차: 설정 화면 진입 시 다른 기기에서 바꾼 다회독 기본값/일일 초기화 시각을 받아와 로컬 상태를 갱신.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.syncSettingsFromFirebase() }
        defaultMultiPassEnabled = repository.defaultMultiPassEnabled
        defaultPassCount = repository.defaultPassCount
        defaultPassIntervals = com.phonelock.shared.calc.PassSchedule.parsePassIntervals(repository.defaultPassIntervalsCsv, repository.defaultPassCount)
        dailyResetHourText = repository.dailyResetHour.toString()
        editProtectionEnabled = repository.editProtectionEnabled
        editProtectionStartText = repository.editProtectionStartHour.toString()
        editProtectionEndText = repository.editProtectionEndHour.toString()
        savedProtectionStart = repository.editProtectionStartHour
        savedProtectionEnd = repository.editProtectionEndHour
        routineStreakFreeze = repository.routineStreakFreezePerWeek
    }

    // 저장된 값 기준으로 되돌리기(게이트 취소 시) / 실제 저장.
    fun revertProtectionFields() {
        editProtectionEnabled = repository.editProtectionEnabled
        editProtectionStartText = repository.editProtectionStartHour.toString()
        editProtectionEndText = repository.editProtectionEndHour.toString()
        savedProtectionStart = repository.editProtectionStartHour
        savedProtectionEnd = repository.editProtectionEndHour
    }

    fun saveProtection(enabled: Boolean, startHour: Int, endHour: Int) {
        repository.editProtectionEnabled = enabled
        repository.editProtectionStartHour = startHour
        repository.editProtectionEndHour = endHour
        editProtectionEnabled = enabled
        savedProtectionStart = startHour
        savedProtectionEnd = endHour
        editProtectionStartText = startHour.toString()
        editProtectionEndText = endHour.toString()
        // 다른 기기에도 바로 반영(설정 문서 LWW) — 방지 시간대 밖에서 바꾼 값도 그대로 동기화된다.
        repository.pushSettingsToFirebase()
    }

    /** 지금 시각이 방지 안에서 밖으로 빠지는 변경이면 회유 절차로, 아니면 즉시 적용한다. */
    fun requestProtection(enabled: Boolean, startHour: Int, endHour: Int) {
        val nowHour = java.time.LocalTime.now().hour
        val protectedBefore = com.phonelock.desktop.monitor.isEditProtectionHour(
            repository.editProtectionEnabled, repository.editProtectionStartHour, repository.editProtectionEndHour, nowHour
        )
        val protectedAfter = com.phonelock.desktop.monitor.isEditProtectionHour(enabled, startHour, endHour, nowHour)
        if (protectedBefore && !protectedAfter) {
            pendingProtection = Triple(enabled, startHour, endHour)
        } else {
            saveProtection(enabled, startHour, endHour)
        }
    }
    var launchAtStartup by remember { mutableStateOf(com.phonelock.desktop.isLaunchAtStartupEnabled()) }
    var blockReels by remember { mutableStateOf(repository.blockReels) }
    var blockShorts by remember { mutableStateOf(repository.blockShorts) }
    var routineStreakNotifyEnabled by remember { mutableStateOf(repository.routineStreakNotifyEnabled) }
    // 공부 알림(122차) — 값은 전부 data.json(이 기기 로컬)에 즉시 저장된다.
    var studyAlertEnabled by remember { mutableStateOf(repository.studyAlertEnabled) }
    var studyAlertNotStarted by remember { mutableStateOf(repository.studyAlertNotStartedEnabled) }
    var studyAlertPace by remember { mutableStateOf(repository.studyAlertPaceEnabled) }
    var studyAlertSchedule by remember { mutableStateOf(repository.studyAlertScheduleEnabled) }
    var studyAlertStartHour by remember { mutableStateOf(repository.studyAlertStartHour) }
    var studyAlertEndHour by remember { mutableStateOf(repository.studyAlertEndHour) }
    var studyAlertTestResult by remember { mutableStateOf<String?>(null) }
    // 90차: 타이머 탭에서 옮겨온 "공부 중 허용 프로그램/사이트"(공부 서브탭) — 저장 위치는 그대로다.
    var studyAllowedApps by remember { mutableStateOf(repository.studyLockAllowedApps) }
    var studyAllowedSites by remember { mutableStateOf(repository.studyLockAllowedSites) }
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

    val visibleCategories = remember(isAdmin) {
        listOfNotNull(
            SettingsCategory.PROFILE,
            SettingsCategory.DISPLAY,
            SettingsCategory.RULES.takeIf { repository.permManage },
            SettingsCategory.STUDY.takeIf { repository.permStudy },
            SettingsCategory.ROUTINE.takeIf { repository.permRoutine },
            SettingsCategory.SOCIAL.takeIf { repository.permSocial },
            SettingsCategory.DATA,
            SettingsCategory.SYSTEM,
            SettingsCategory.HELP,
            SettingsCategory.ADMIN.takeIf { isAdmin }
        )
    }
    var category by remember { mutableStateOf(SettingsCategory.PROFILE) }

    pendingRestoreFile?.let { file ->
        LedgerAlertDialog(
            onDismissRequest = { pendingRestoreFile = null },
            title = { Text("복원 확인") },
            text = { Text("지금 차단 규칙·기록이 ${file.name} 내용으로 바뀝니다(되돌릴 수 없음).") },
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
        LedgerAlertDialog(
            onDismissRequest = { pendingImportFile = null },
            title = { Text("가져오기 확인") },
            text = { Text("지금 차단 규칙·기록이 ${file.name} 내용으로 바뀝니다(되돌릴 수 없음).") },
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
        LedgerAlertDialog(
            onDismissRequest = { pendingRoutineImportFile = null },
            title = { Text("루틴 가져오기 확인") },
            text = { Text("지금 루틴·체크 기록이 ${file.name} 내용으로 바뀝니다(되돌릴 수 없음).") },
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

    pendingProtection?.let { (pEnabled, pStart, pEnd) ->
        androidx.compose.ui.window.Window(
            onCloseRequest = { revertProtectionFields(); pendingProtection = null },
            title = "수정·삭제 방지 약화",
            undecorated = true,
            alwaysOnTop = true,
            state = androidx.compose.ui.window.rememberWindowState(placement = androidx.compose.ui.window.WindowPlacement.Maximized)
        ) {
            com.phonelock.desktop.ui.theme.PhoneLockTheme(repository.currentPalette()) {
                ExitConfirmScreen(
                    title = "정말 차단 규칙 수정·삭제 방지를 약하게 만들까요?",
                    finalLabel = "적용",
                    onConfirmExit = {
                        saveProtection(pEnabled, pStart, pEnd)
                        pendingProtection = null
                    },
                    onCancel = { revertProtectionFields(); pendingProtection = null }
                )
            }
        }
    }

    // 144차 리디자인(안드로이드판과 같은 언어): 편집형 머리(큰 제목 + 닫기), 왼쪽 목차는 원 안의 아이콘 + 이름 + 한 줄 설명,
    // 오른쪽은 고른 분류의 큰 제목 + 세부 설정.
    Column(Modifier.fillMaxSize()) {
        PageMasthead(title = "설정", overline = "갓생키트") {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "설정 닫기") }
        }
        Spacer(Modifier.height(Spacing.md))
        Hairline()

        Row(Modifier.weight(1f).fillMaxSize()) {
            // 좌측: 분류 목차.
            Column(
                Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.sm, vertical = Spacing.sm)
            ) {
                visibleCategories.forEach { cat ->
                    val selected = category == cat
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .clickable { category = cat }
                            .padding(horizontal = Spacing.sm, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(36.dp).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(cat.icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cat.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
                            Text(cat.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 138차(사용자 요청): "일일 사용 한도 초기화 시각"을 "하루 시작 기준"으로 이름을 바꾸고 공부 탭에도 둔다 —
            // 이 값은 한도뿐 아니라 캘린더·공부 기록의 "오늘"도 정하는데, 규칙 탭에 한도 이름으로만 있어서 공부 쪽에선
            // 찾을 수 없었다. 두 탭이 같은 입력 상태(dailyResetHourText)와 같은 저장을 쓴다. 루틴은 이 값을 따르지 않고
            // 자정 기준이라(RoutineScreen의 LocalDate.now()) 루틴 탭엔 두지 않았다 — 두면 루틴도 바뀌는 것처럼 보인다.
            val dayStartCard: @Composable (String) -> Unit = { alsoIn ->
                SectionCard("하루 시작 기준") {
                    OutlinedTextField(
                        value = dailyResetHourText,
                        onValueChange = { text ->
                            dailyResetHourText = text
                            text.toIntOrNull()?.let { if (it in 0..23) { repository.dailyResetHour = it; repository.pushSettingsToFirebase() } }
                        },
                        label = { Text("하루 시작 시각 (0~23시)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "이 시각에 \"오늘\"이 바뀝니다(루틴은 자정). $alsoIn",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 우측: 선택된 분류의 세부 설정 — 위에 그 분류의 큰 제목.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            ) {
                Text(category.label, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(Spacing.md))
                when (category) {
                    SettingsCategory.PROFILE -> {
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

                        SectionCard("프로필 사진 선택") {
                            var selectedAvatar by remember { mutableStateOf<String?>(null) }
                            var avatarSaving by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                val url = repository.fbDatabaseUrl
                                val key = repository.fbApiKey
                                val profile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    AccountSyncClient.fetchMyProfile(url, key).getOrNull()
                                }
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
                                com.phonelock.desktop.ui.components.AvatarCatalog.PRESETS.forEach { (id, emoji) ->
                                    val selected = selectedAvatar == id
                                    Surface(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clickable(enabled = !avatarSaving) {
                                                selectedAvatar = id
                                                avatarSaving = true
                                                val url = repository.fbDatabaseUrl
                                                val key = repository.fbApiKey
                                                Thread {
                                                    AccountSyncClient.updateProfileImage(url, key, id)
                                                    avatarSaving = false
                                                }.start()
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

                        AccountSecuritySection(repository, onSignedOut = {})
                        Spacer(Modifier.height(Spacing.md))

                        // 98차(사용자 요청): 온라인/오프라인 모드 — 네트워크가 실제로 끊기면 자동으로
                        // 오프라인 전환되지만(NetworkMonitor), 필요하면 연결돼 있어도 수동으로 강제 오프라인 가능.
                        // 106차 후속: 게스트(익명 로그인) 전용 기능이므로 게스트에게만 노출한다.
                        if (AuthManager.isAnonymous) {
                            SectionCard("온라인 / 오프라인 모드") {
                                var offlineOverride by remember { mutableStateOf(repository.offlineModeOverride) }
                                ToggleRow(
                                    title = "오프라인 모드로 강제 전환",
                                    description = "켜면 동기화·모임 없이 이 기기에서만 씁니다.",
                                    checked = offlineOverride,
                                    onCheckedChange = { offlineOverride = it; repository.offlineModeOverride = it }
                                )
                            }
                            Spacer(Modifier.height(Spacing.md))
                        }
                    }

                    SettingsCategory.DISPLAY -> {
                        SectionCard("테마") {
                            Text(
                                "앱·차단 화면·브라우저 확장 색이 함께 바뀝니다.",
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
                                    // 86차(사용자 요청): 미리보기 상자를 누르면 헥스 직접 입력 대신 프리셋
                                    // 팔레트에서 골라 고를 수 있다(안드로이드판과 대칭).
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
                                            .clickable { showAccentPalette = true }
                                    )
                                }
                                Spacer(Modifier.height(Spacing.xs))
                                Text(
                                    "색 상자를 눌러 고르면 나머지 색은 자동으로 맞춥니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (showBgPalette) {
                                    com.phonelock.desktop.ui.components.ColorPaletteDialog(
                                        title = "배경색 고르기",
                                        currentHex = customBgText,
                                        onSelect = { hex ->
                                            customBgText = hex
                                            repository.customThemeBackground = hex
                                            onThemeChange(themeMode)
                                        },
                                        onDismiss = { showBgPalette = false }
                                    )
                                }
                                if (showAccentPalette) {
                                    com.phonelock.desktop.ui.components.ColorPaletteDialog(
                                        title = "포인트색 고르기",
                                        currentHex = customAccentText,
                                        onSelect = { hex ->
                                            customAccentText = hex
                                            repository.customThemeAccent = hex
                                            onThemeChange(themeMode)
                                        },
                                        onDismiss = { showAccentPalette = false }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(Spacing.md))
                        // 144차: 미니멀 모드 = 성능 모드(안드로이드 130차 미니멀 모드를 데스크탑에도) — 흑백 + 움직임·효과 최소화 +
                        // 글자로 된 홈. 기능과 정보는 그대로이고, 고른 테마는 남아 있어 끄면 바로 돌아온다. 이 PC에만 적용된다.
                        SectionCard("미니멀 모드 · 성능 우선") {
                            var minimalOn by remember { mutableStateOf(repository.minimalMode) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("미니멀 모드", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "흑백 · 움직임 최소 · 글자 홈. 이 PC에만 적용됩니다.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(Spacing.md))
                                Switch(checked = minimalOn, onCheckedChange = { on ->
                                    minimalOn = on
                                    repository.minimalMode = on
                                    onThemeChange(themeMode)
                                })
                            }
                        }
                    }

                    SettingsCategory.RULES -> SettingsColumns(left = {
                        dayStartCard("집중 설정에도 같은 값이 있습니다.")
                        Spacer(Modifier.height(Spacing.md))

                        SectionCard("차단 규칙 수정·삭제 방지") {
                            ToggleRow(
                                title = "방지 사용",
                                description = "이 시간대엔 약하게 바꾸려면 질문 ${PERSUASION_MESSAGES.size}개를 거칩니다.",
                                checked = editProtectionEnabled,
                                onCheckedChange = { checked ->
                                    val start = editProtectionStartText.toIntOrNull() ?: repository.editProtectionStartHour
                                    val end = editProtectionEndText.toIntOrNull() ?: repository.editProtectionEndHour
                                    if (checked) {
                                        // 보호를 강화하는 방향이라 즉시 적용.
                                        saveProtection(true, start, end)
                                    } else {
                                        // 끄는 것 자체를 회유 절차로 보호 — 통과 전엔 스위치도 그대로 둔다.
                                        requestProtection(false, start, end)
                                    }
                                }
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                OutlinedTextField(
                                    value = editProtectionStartText,
                                    onValueChange = { text -> editProtectionStartText = text.filter { it.isDigit() }.take(2) },
                                    label = { Text("시작 (0~23시)") },
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = editProtectionEndText,
                                    onValueChange = { text -> editProtectionEndText = text.filter { it.isDigit() }.take(2) },
                                    label = { Text("끝 (0~23시)") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // 입력칸은 값만 담아두고, 아래 버튼을 눌러야 저장·판정한다(타이핑 중간값으로 잠기지 않게).
                            val typedStart = editProtectionStartText.toIntOrNull()
                            val typedEnd = editProtectionEndText.toIntOrNull()
                            val rangeValid = typedStart in 0..23 && typedEnd in 0..23
                            val rangeChanged = rangeValid && (typedStart != savedProtectionStart || typedEnd != savedProtectionEnd)
                            Spacer(Modifier.height(Spacing.xs))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    enabled = rangeChanged,
                                    onClick = { requestProtection(editProtectionEnabled, typedStart!!, typedEnd!!) }
                                ) { Text("시간대 적용") }
                                if (rangeChanged) {
                                    TextButton(onClick = { revertProtectionFields() }) { Text("되돌리기") }
                                } else if (!rangeValid) {
                                    Text("0~23 사이 숫자를 넣어주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            Spacer(Modifier.height(Spacing.xs))
                            val protectedNow = com.phonelock.desktop.monitor.isEditProtectionHour(
                                editProtectionEnabled, savedProtectionStart, savedProtectionEnd, java.time.LocalTime.now().hour
                            )
                            Text(
                                if (protectedNow) {
                                    "지금은 방지 시간대 — 차단 중인 규칙을 약하게 바꾸려면 질문 ${PERSUASION_MESSAGES.size}개."
                                } else {
                                    "지금은 방지 시간대가 아닙니다 — 바로 수정됩니다."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (protectedNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "끝 시각은 빼고 셉니다(11~23 → 23시부터 자유). 같으면 하루 종일.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }, right = {
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
                                "브라우저 확장이 그 주소만 감지해 막습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    })

                    SettingsCategory.STUDY -> SettingsColumns(left = {
                        dayStartCard("관리 설정에도 같은 값이 있습니다.")
                        Spacer(Modifier.height(Spacing.md))
                        SectionCard("캘린더 반복 기본값") {
                            ToggleRow(
                                title = "새 일정을 반복으로 시작",
                                description = "완료하면 다음 회차가 생기는 상태로 만듭니다.",
                                checked = defaultMultiPassEnabled,
                                onCheckedChange = { checked ->
                                    defaultMultiPassEnabled = checked
                                    repository.defaultMultiPassEnabled = checked
                                    repository.pushSettingsToFirebase()
                                }
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                "캘린더에서 직접 만든 일정의 기본 횟수·간격입니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            com.phonelock.desktop.ui.components.NumberStepperField(
                                label = "기본 반복 횟수",
                                value = defaultPassCount.toString(),
                                onValueChange = { text ->
                                    val newCount = (text.toIntOrNull() ?: defaultPassCount)
                                        .coerceIn(com.phonelock.shared.calc.PassSchedule.MIN_PASS_COUNT, com.phonelock.shared.calc.PassSchedule.MAX_PASS_COUNT)
                                    defaultPassCount = newCount
                                    repository.defaultPassCount = newCount
                                    defaultPassIntervals = com.phonelock.shared.calc.PassSchedule.defaultPassIntervals(newCount)
                                    repository.defaultPassIntervalsCsv = defaultPassIntervals.joinToString(",")
                                    repository.pushSettingsToFirebase()
                                },
                                min = com.phonelock.shared.calc.PassSchedule.MIN_PASS_COUNT,
                                max = com.phonelock.shared.calc.PassSchedule.MAX_PASS_COUNT,
                                modifier = Modifier.width(160.dp)
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text("회차별 간격(일)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                defaultPassIntervals.forEachIndexed { i, days ->
                                    com.phonelock.desktop.ui.components.NumberStepperField(
                                        label = "${i + 1}→${i + 2}회차",
                                        value = days.toString(),
                                        onValueChange = { text ->
                                            val newDays = (text.toIntOrNull() ?: days).coerceIn(1, 90)
                                            val updated = defaultPassIntervals.toMutableList().also { it[i] = newDays }
                                            defaultPassIntervals = updated
                                            repository.defaultPassIntervalsCsv = updated.joinToString(",")
                                            repository.pushSettingsToFirebase()
                                        },
                                        min = 1, max = 90,
                                        modifier = Modifier.width(140.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(Spacing.md))

                        // 공부 알림(122차, 안드로이드판과 대칭) — 캘린더/계산기/일정표를 보고 계획보다 늦어질 때만
                        // 트레이 알림을 보낸다. 설정값은 이 기기 로컬(data.json)이라 통신이 끊겨도 초기화되지 않는다.
                        SectionCard("집중 알림") {
                            Text(
                                "계획보다 늦어질 때만, 종류마다 하루 한 번 트레이로 알립니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            ToggleRow(
                                title = "집중 알림 받기",
                                checked = studyAlertEnabled,
                                onCheckedChange = { checked ->
                                    studyAlertEnabled = checked
                                    repository.studyAlertEnabled = checked
                                }
                            )
                            if (studyAlertEnabled) {
                                ToggleRow(
                                    title = "집중 미실행 알림",
                                    description = "오늘 예정된 일정이 있는데 아직 아무것도 하지 않았을 때.",
                                    checked = studyAlertNotStarted,
                                    onCheckedChange = { checked ->
                                        studyAlertNotStarted = checked
                                        repository.studyAlertNotStartedEnabled = checked
                                    }
                                )
                                ToggleRow(
                                    title = "진행 페이스 지연 알림",
                                    description = "진행량이 계획(기간 경과율)보다 뒤처졌을 때.",
                                    checked = studyAlertPace,
                                    onCheckedChange = { checked ->
                                        studyAlertPace = checked
                                        repository.studyAlertPaceEnabled = checked
                                    }
                                )
                                ToggleRow(
                                    title = "일정 지연 알림",
                                    description = "목표대로 해도 마감까지 못 끝낼 때.",
                                    checked = studyAlertSchedule,
                                    onCheckedChange = { checked ->
                                        studyAlertSchedule = checked
                                        repository.studyAlertScheduleEnabled = checked
                                    }
                                )
                                Spacer(Modifier.height(Spacing.sm))
                                Text("알림 가능 시간대", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(Spacing.xs))
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                    com.phonelock.desktop.ui.components.NumberStepperField(
                                        label = "시작(시)",
                                        value = studyAlertStartHour.toString(),
                                        onValueChange = { text ->
                                            val hour = (text.toIntOrNull() ?: studyAlertStartHour).coerceIn(0, 23)
                                            studyAlertStartHour = hour
                                            repository.studyAlertStartHour = hour
                                        },
                                        min = 0, max = 23,
                                        modifier = Modifier.width(140.dp)
                                    )
                                    com.phonelock.desktop.ui.components.NumberStepperField(
                                        label = "종료(시)",
                                        value = studyAlertEndHour.toString(),
                                        onValueChange = { text ->
                                            val hour = (text.toIntOrNull() ?: studyAlertEndHour).coerceIn(0, 23)
                                            studyAlertEndHour = hour
                                            repository.studyAlertEndHour = hour
                                        },
                                        min = 0, max = 23,
                                        modifier = Modifier.width(140.dp)
                                    )
                                }
                                Text(
                                    "시작이 종료보다 늦으면 자정을 넘깁니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(Spacing.sm))
                                Button(
                                    onClick = { studyAlertTestResult = com.phonelock.desktop.routine.StudyAlertNotifier.checkAndNotify(repository, force = true) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("지금 한 번 확인") }
                                Text(
                                    "시간대·하루 1회 제한 없이 지금 확인합니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                studyAlertTestResult?.let {
                                    Spacer(Modifier.height(Spacing.xs))
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }, right = {
                        SectionCard("집중 중 허용 프로그램") {
                            Text(
                                "집중 중엔 여기 넣은 프로그램만 열립니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            LockListEditor(
                                items = studyAllowedApps,
                                placeholder = "예: chrome.exe",
                                onAdd = { name -> studyAllowedApps = studyAllowedApps + name; repository.studyLockAllowedApps = studyAllowedApps },
                                onRemove = { idx -> studyAllowedApps = studyAllowedApps.toMutableList().apply { removeAt(idx) }; repository.studyLockAllowedApps = studyAllowedApps }
                            )
                        }
                        Spacer(Modifier.height(Spacing.md))

                        SectionCard("집중 중 허용 사이트") {
                            Text(
                                "집중 중엔 여기 넣은 사이트만 열립니다(이 기기만).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            LockListEditor(
                                items = studyAllowedSites,
                                placeholder = "예: google.com",
                                onAdd = { name -> studyAllowedSites = studyAllowedSites + name; repository.studyLockAllowedSites = studyAllowedSites },
                                onRemove = { idx -> studyAllowedSites = studyAllowedSites.toMutableList().apply { removeAt(idx) }; repository.studyLockAllowedSites = studyAllowedSites }
                            )
                        }
                    })

                    SettingsCategory.ROUTINE -> SettingsColumns(left = {
                        SectionCard("루틴 연속 기록 알림") {
                            ToggleRow(
                                title = "연속 기록 알림 받기",
                                checked = routineStreakNotifyEnabled,
                                onCheckedChange = { checked ->
                                    routineStreakNotifyEnabled = checked
                                    repository.routineStreakNotifyEnabled = checked
                                }
                            )
                            Text(
                                "하루 한 번, 어제의 연속 기록을 트레이로 알려 줍니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(Spacing.md))

                        // 142차(사용자 요청): 주 2일로 굳어 있던 방지권을 사용자가 정한다.
                        SectionCard("연속 기록 방지권") {
                            com.phonelock.desktop.ui.components.NumberStepperField(
                                label = "일주일에 넘어갈 수 있는 날",
                                value = routineStreakFreeze.toString(),
                                onValueChange = { text ->
                                    val days = com.phonelock.shared.routine.RoutineStreak.clampFreeze(text.toIntOrNull() ?: routineStreakFreeze)
                                    routineStreakFreeze = days
                                    repository.routineStreakFreezePerWeek = days
                                    repository.pushSettingsToFirebase()
                                },
                                min = 0, max = com.phonelock.shared.routine.RoutineStreak.MAX_FREEZE_DAYS_PER_WEEK,
                                modifier = Modifier.width(200.dp)
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "한 주(월~일)에 이만큼은 못 채워도 이어집니다. 0이면 바로 끊깁니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }, right = {
                        SectionCard("루틴 내보내기 · 가져오기") {
                            Text(
                                "루틴만 골라 다른 계정으로 옮기거나 따로 보관할 때 씁니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                OutlinedButton(onClick = {
                                    pickSaveFile("루틴 내보내기", "phonelock_routines_${java.time.LocalDate.now()}.json")?.let { file ->
                                        file.writeText(repository.exportRoutinesBackupJson())
                                    }
                                }) { Text("내보내기", maxLines = 1, softWrap = false) }
                                OutlinedButton(onClick = {
                                    pickOpenFile("루틴 가져오기")?.let { file -> pendingRoutineImportFile = file }
                                }) { Text("가져오기", maxLines = 1, softWrap = false) }
                            }
                        }
                    })

                    SettingsCategory.SOCIAL -> {
                        SectionCard("모임 공유 설정") {
                            Text(
                                "공유 범위와 깨우기 수신은 모임마다 다릅니다 — 각 모임 화면의 설정에서 바꾸세요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    SettingsCategory.DATA -> SettingsColumns(left = {
                        SectionCard("일일 백업 · 복원") {
                            Text(
                                "하루 한 번 자동 백업, 7일치 보관. 복원하면 지금 데이터가 바뀝니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            if (backups.isEmpty()) {
                                Text("아직 백업이 없습니다(다음 시작 때 만들어집니다).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                backups.forEach { file ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(file.name.removePrefix("backup_").removeSuffix(".json"), style = MaterialTheme.typography.bodyMedium)
                                        TextButton(onClick = { pendingRestoreFile = file }) { Text("이 시점으로 복원", maxLines = 1, softWrap = false) }
                                    }
                                }
                            }
                            Spacer(Modifier.height(Spacing.sm))
                            TextButton(onClick = { backups = repository.listBackups() }) { Text("목록 새로고침", maxLines = 1, softWrap = false) }
                        }
                        Spacer(Modifier.height(Spacing.md))

                        SectionCard("설정·차단 규칙 내보내기 · 가져오기") {
                            Text(
                                "전체 데이터를 파일로 옮깁니다. 다른 PC로 옮길 때 씁니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                OutlinedButton(onClick = {
                                    pickSaveFile("설정·차단 규칙 내보내기", "phonelock_export_${java.time.LocalDate.now()}.json")?.let { file ->
                                        repository.exportDataToFile(file)
                                    }
                                }) { Text("내보내기", maxLines = 1, softWrap = false) }
                                OutlinedButton(onClick = {
                                    pickOpenFile("설정·차단 규칙 가져오기")?.let { file -> pendingImportFile = file }
                                }) { Text("가져오기", maxLines = 1, softWrap = false) }
                            }
                        }
                        // 85차(사용자 요청): "자동 백업 (Firebase)" 설정 UI를 제거했다 — 로그인/Storage 활성화
                        // 등 전제조건이 많아 실사용 검증이 부족한 상태였다. cloudBackupEnabled/CloudBackupClient
                        // 등 하위 코드는 그대로 남겨뒀으니(제거하지 않음) 나중에 제대로 재설계해 다시 노출할 수
                        // 있다 — 자세한 경위는 IDEAS.md/DECISIONS.md 85차 참고.
                    }, right = {
                        SectionCard("오래된 사용 기록 정리") {
                            var lastResult by remember { mutableStateOf<Int?>(null) }
                            Text(
                                "12개월 지난 사용·집중 기록을 지웁니다(되돌릴 수 없음).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            OutlinedButton(onClick = { lastResult = repository.pruneOldStats(12) }) { Text("지난 기록 정리", maxLines = 1, softWrap = false) }
                            lastResult?.let {
                                Spacer(Modifier.height(Spacing.xs))
                                Text("$it 건 삭제됨", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    })

                    SettingsCategory.SYSTEM -> SettingsColumns(left = {
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

                        SectionCard("업데이트") {
                            var checking by remember { mutableStateOf(false) }
                            var installerUrl by remember { mutableStateOf(repository.pendingUpdateInstallerUrl()) }
                            var lastOutcome by remember { mutableStateOf<Repository.UpdateCheckOutcome?>(null) }
                            Text(
                                "현재 빌드 ${repository.currentBuildTimestamp()} · 하루 한 번 자동 확인",
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
                    }, right = {
                        SectionCard("자동 재시작(워치독)") {
                            Text(
                                "강제로 꺼도 다시 켜집니다. 트레이 \"종료\"(10분 대기)로만 꺼집니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(Spacing.md))

                        // 91차(사용자 요청): 관리(차단) 탭에서 이리로 이동 — 워치독과 함께 "앱을 끄기 어렵게
                        // 만드는" 설정이라 옆에 두는 게 자연스럽다. 기본값은 false(OFF, Models.kt 기존값 그대로).
                        SectionCard("종료 시 확인 질문") {
                            ToggleRow(
                                title = "종료 시 확인 질문 20개 확인",
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
                    })

                    SettingsCategory.HELP -> {
                        // 도움말 본문은 shared/HelpContent.kt(안드로이드와 공유), 그리기는 HelpScreen.kt.
                        // 관리자가 꺼둔 기능 영역의 주제는 숨긴다(설정 카테고리를 숨기는 것과 같은 기준).
                        HelpCenter(
                            visibleAreas = buildSet {
                                add(com.phonelock.shared.HelpContent.Area.GENERAL)
                                if (repository.permPlant) add(com.phonelock.shared.HelpContent.Area.HOME)
                                if (repository.permRoutine) add(com.phonelock.shared.HelpContent.Area.ROUTINE)
                                if (repository.permStudy) add(com.phonelock.shared.HelpContent.Area.STUDY)
                                if (repository.permManage) add(com.phonelock.shared.HelpContent.Area.RULES)
                                if (repository.permSocial) add(com.phonelock.shared.HelpContent.Area.SOCIAL)
                            }
                        )
                    }

                    SettingsCategory.ADMIN -> SettingsColumns(left = {
                        SectionCard("가입 승인 대기") {
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
                    }, right = {
                        SectionCard("승인된 사용자 관리") {
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
                    })
                }
            }
        }
    }
}

/** 관리자 패널에서 사용자별 기능 범위(홈/루틴/공부/규칙/모임)를 고르는 칩 5개 — 눌린 것만 허용(안드로이드판과 대칭). */
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
            label = { Text("집중") }
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
