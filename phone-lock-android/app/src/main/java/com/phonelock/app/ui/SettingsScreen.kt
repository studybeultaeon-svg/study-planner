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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import com.phonelock.app.data.getAllCalendarTasksOnce
import androidx.compose.ui.platform.LocalContext
import com.phonelock.app.R
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.*
import com.phonelock.app.data.PreMigrationBackup
import com.phonelock.app.routine.RoutineAlarmScheduler
import com.phonelock.app.routine.StudyAlertChecker
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.service.AccessibilityServiceChecker
import com.phonelock.app.service.AccountSyncClient
import com.phonelock.app.service.AuthManager
import com.phonelock.app.service.BackgroundMediaGuard
import com.phonelock.app.service.PhoneLockDeviceAdminReceiver
import com.phonelock.app.service.isEditProtectionHour
import com.phonelock.shared.PERSUASION_MESSAGES
import com.phonelock.app.ui.components.PersuasionStepper
import com.phonelock.app.ui.components.SectionCard
import com.phonelock.app.ui.components.ToggleRow
import com.phonelock.app.ui.components.isTabletWidth
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
private enum class SettingsCategory(val label: String, val icon: ImageVector, val summary: String) {
    PROFILE("프로필", Icons.Outlined.Person, "닉네임 · 프로필 사진 · 로그인 및 보안"),
    DISPLAY("화면", Icons.Outlined.Palette, "테마 · 미니멀(성능) 모드 · 런처 · 알림 묶음"),
    RULES("관리", Icons.Outlined.Shield, "수정·삭제 방지 · 릴스/쇼츠 · 하루 시작 기준"),
    STUDY("집중", Icons.Outlined.Timer, "반복 기본값 · 집중 알림 · 허용 앱과 사이트"),
    ROUTINE("루틴", Icons.Outlined.TaskAlt, "연속 기록 알림 · 방지권 · 내보내기"),
    SOCIAL("모임", Icons.Outlined.Groups, "모임 공유 설정"),
    DATA("데이터", Icons.Outlined.Backup, "백업 · 복원 · 오래된 기록 정리"),
    SYSTEM("시스템", Icons.Outlined.Tune, "권한 · 진단 · 업데이트"),
    HELP("도움말", Icons.Outlined.HelpOutline, "기능 설명과 자주 묻는 질문"),
    ADMIN("관리자 패널", Icons.Outlined.AdminPanelSettings, "가입 승인 · 권한")
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
    var routineStreakFreeze by remember { mutableStateOf(prefs.routineStreakFreezePerWeek) }
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
    // 차단 규칙 수정·삭제 방지(129차, 사용자 요청) — 128차까지 11~23시로 하드코딩이던 걸 설정으로 뺐다.
    var editProtectionEnabled by remember { mutableStateOf(prefs.editProtectionEnabled) }
    var editProtectionStartText by remember { mutableStateOf(prefs.editProtectionStartHour.toString()) }
    var editProtectionEndText by remember { mutableStateOf(prefs.editProtectionEndHour.toString()) }
    // 134차: 저장된 값과 입력칸 값을 분리해서 들고 있는다 — 예전엔 글자를 칠 때마다 곧바로 저장·판정해서,
    // 방지 시간대 밖에서 "11" → "10"으로 고치는 도중의 중간값("1" = 1~23시)이 잠깐 저장되며 그 순간
    // 지금이 방지 시간대가 돼버렸고, 이어지는 타이핑이 확인 질문 20개에 막혔다(사용자 지적).
    var savedProtectionStart by remember { mutableIntStateOf(prefs.editProtectionStartHour) }
    var savedProtectionEnd by remember { mutableIntStateOf(prefs.editProtectionEndHour) }
    // 방지를 끄거나 시간대를 좁혀 "지금"이 방지 밖으로 빠지는 변경은 그 한 번으로 모든 보호를 걷어내는
    // 새 우회로라, 79차 "종료 확인 절차 끄기"와 같이 회유 멘트 20개로 게이트한다(반대로 켜거나 넓히는
    // 방향은 즉시 적용). null이 아니면 게이트 진행 중이고, 끝까지 통과해야 실제로 저장된다.
    var pendingProtection by remember { mutableStateOf<Triple<Boolean, Int, Int>?>(null) }
    var pendingProtectionMessageIndex by remember { mutableIntStateOf(0) }
    // 85차: 설정 화면 진입 시 다른 기기에서 바꾼 다회독 기본값/일일 초기화 시각을 받아와 로컬 상태를 갱신.
    LaunchedEffect(Unit) {
        repository.syncSettingsFromFirebase()
        defaultMultiPassEnabled = prefs.defaultMultiPassEnabled
        defaultPassCount = prefs.defaultPassCount
        defaultPassIntervals = com.phonelock.shared.calc.PassSchedule.parsePassIntervals(prefs.defaultPassIntervalsCsv, prefs.defaultPassCount)
        dailyResetHourText = prefs.dailyResetHour.toString()
        editProtectionEnabled = prefs.editProtectionEnabled
        editProtectionStartText = prefs.editProtectionStartHour.toString()
        editProtectionEndText = prefs.editProtectionEndHour.toString()
        savedProtectionStart = prefs.editProtectionStartHour
        savedProtectionEnd = prefs.editProtectionEndHour
        routineStreakFreeze = prefs.routineStreakFreezePerWeek
    }

    // 저장된 값 기준으로 되돌리기(게이트 취소 시) / 실제 저장.
    fun revertProtectionFields() {
        editProtectionEnabled = prefs.editProtectionEnabled
        editProtectionStartText = prefs.editProtectionStartHour.toString()
        editProtectionEndText = prefs.editProtectionEndHour.toString()
        savedProtectionStart = prefs.editProtectionStartHour
        savedProtectionEnd = prefs.editProtectionEndHour
    }

    fun saveProtection(enabled: Boolean, startHour: Int, endHour: Int) {
        prefs.editProtectionEnabled = enabled
        prefs.editProtectionStartHour = startHour
        prefs.editProtectionEndHour = endHour
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
        val protectedBefore = isEditProtectionHour(
            prefs.editProtectionEnabled, prefs.editProtectionStartHour, prefs.editProtectionEndHour, nowHour
        )
        val protectedAfter = isEditProtectionHour(enabled, startHour, endHour, nowHour)
        if (protectedBefore && !protectedAfter) {
            pendingProtection = Triple(enabled, startHour, endHour)
            pendingProtectionMessageIndex = 0
        } else {
            saveProtection(enabled, startHour, endHour)
        }
    }
    // 미니멀 모드는 그냥 켜고 끄는 표시 설정이라 아무 절차 없이 바로 적용한다(131차 사용자 요청 —
    // 130차엔 끌 때 회유 멘트 20개를 거치게 했었다). 반면 **기본 런처를 이 앱에서 다른 앱으로 되돌리는 건**
    // 지금 걸려 있는 제한을 통째로 걷어내는 행동이라 수정·삭제 방지 시간대 안에서는 그대로 회유 멘트를
    // 거친다. 단, 시스템 설정 > 기본 앱에서 직접 바꾸는 경로는 앱이 막을 수 없다([[BUGS.md]] 130차).
    var minimalMode by remember { mutableStateOf(prefs.minimalMode) }
    var pendingLauncherGate by remember { mutableStateOf(false) }
    var pendingMinimalMessageIndex by remember { mutableIntStateOf(0) }
    val launcherRoleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 결과는 쓰지 않는다 — "지금 기본 런처" 표시는 설정에 다시 들어올 때 갱신된다 */ }

    fun isEditProtectedNow(): Boolean = isEditProtectionHour(
        prefs.editProtectionEnabled,
        prefs.editProtectionStartHour,
        prefs.editProtectionEndHour,
        java.time.LocalTime.now().hour
    )

    fun applyMinimalMode(enabled: Boolean) {
        prefs.minimalMode = enabled
        minimalMode = enabled
        onThemeChange(prefs.effectiveThemeMode)
        RoutineWidgetProvider.updateAll(context)
    }

    fun openLauncherChooser() {
        runCatching {
            launcherRoleLauncher.launch(com.phonelock.app.ui.launcher.buildSetDefaultLauncherIntent(context))
        }.onFailure {
            Toast.makeText(context, "이 기기에서는 설정 > 앱 > 기본 앱에서 홈 앱을 바꿔주세요", Toast.LENGTH_LONG).show()
        }
    }

    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    val autoBackups = remember { PreMigrationBackup.listBackups(context) }
    var groupRestoreResult by remember { mutableStateOf<String?>(null) }
    var showRoutineRestoreConfirmDialog by remember { mutableStateOf(false) }
    var pendingRoutineRestoreUri by remember { mutableStateOf<Uri?>(null) }

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
            SettingsCategory.HELP,
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

    pendingProtection?.let { staged ->
        val (pEnabled, pStart, pEnd) = staged
        val isLast = pendingProtectionMessageIndex == PERSUASION_MESSAGES.lastIndex
        AlertDialog(
            // 밖을 눌러 닫으면 절차를 건너뛰고 창만 사라지는 셈이라 취소 버튼으로만 빠져나가게 한다.
            onDismissRequest = {},
            title = { Text("수정·삭제 방지를 약하게 만드는 변경입니다") },
            text = {
                Column {
                    PersuasionStepper(
                        stepKey = staged,
                        messageIndex = pendingProtectionMessageIndex,
                        headerText = "(%d/%d)".format(pendingProtectionMessageIndex + 1, PERSUASION_MESSAGES.size),
                        message = PERSUASION_MESSAGES[pendingProtectionMessageIndex],
                        confirmLabel = if (isLast) "적용" else "예",
                        onCancel = {
                            revertProtectionFields()
                            pendingProtection = null
                            pendingProtectionMessageIndex = 0
                        },
                        onConfirmStep = {
                            if (isLast) {
                                saveProtection(pEnabled, pStart, pEnd)
                                pendingProtection = null
                                pendingProtectionMessageIndex = 0
                            } else {
                                pendingProtectionMessageIndex++
                            }
                        }
                    )
                }
            },
            confirmButton = {}
        )
    }

    if (pendingLauncherGate) {
        val isLast = pendingMinimalMessageIndex == PERSUASION_MESSAGES.lastIndex
        AlertDialog(
            onDismissRequest = {},
            title = { Text("기본 런처를 다시 고르려 합니다") },
            text = {
                Column {
                    PersuasionStepper(
                        stepKey = "launcher_change",
                        messageIndex = pendingMinimalMessageIndex,
                        headerText = "(%d/%d)".format(pendingMinimalMessageIndex + 1, PERSUASION_MESSAGES.size),
                        message = PERSUASION_MESSAGES[pendingMinimalMessageIndex],
                        confirmLabel = if (isLast) "진행" else "예",
                        onCancel = {
                            pendingLauncherGate = false
                            pendingMinimalMessageIndex = 0
                        },
                        onConfirmStep = {
                            if (isLast) {
                                openLauncherChooser()
                                pendingLauncherGate = false
                                pendingMinimalMessageIndex = 0
                            } else {
                                pendingMinimalMessageIndex++
                            }
                        }
                    )
                }
            },
            confirmButton = {}
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
                    text.toIntOrNull()?.let { if (it in 0..23) { prefs.dailyResetHour = it; repository.pushSettingsToFirebase() } }
                },
                label = { Text("하루 시작 시각 (0~23시)") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "이 시각에 앱의 \"오늘\"이 바뀝니다 — 차단 규칙의 오늘 사용 시간·잠깐 풀기 횟수, 캘린더·일정표·집중 기록의 오늘이 이 시각부터 새로 시작돼요. 루틴은 이 설정과 상관없이 자정 기준이에요.\n$alsoIn",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 카테고리별 세부 설정 — 태블릿의 우측 패널과 폰의 드로어 본문이 같은 람다를 재사용한다
    // (MainActivity.kt의 navHostContent와 같은 패턴).
    val detailContent: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter).padding(top = Spacing.sm, bottom = Spacing.xxl)) {
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

                    AccountSecuritySection(prefs)
                    Spacer(Modifier.height(Spacing.md))

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
                                        onThemeChange(prefs.effectiveThemeMode); RoutineWidgetProvider.updateAll(context)
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
                                            onThemeChange(prefs.effectiveThemeMode); RoutineWidgetProvider.updateAll(context)
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
                                            onThemeChange(prefs.effectiveThemeMode); RoutineWidgetProvider.updateAll(context)
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
                                        onThemeChange(prefs.effectiveThemeMode); RoutineWidgetProvider.updateAll(context)
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
                                        onThemeChange(prefs.effectiveThemeMode); RoutineWidgetProvider.updateAll(context)
                                    },
                                    onDismiss = { showAccentPalette = false }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(Spacing.md))
                    SectionCard("미니멀 모드 · 성능 우선") {
                        ToggleRow(
                            title = "미니멀 모드",
                            description = "앱 전체를 흑백으로 바꾸고, 화면 전환·숫자 변화 같은 움직임을 짧은 페이드로 줄이며, 홈의 움직이는 장면 대신 " +
                                "글자로 된 홈을 보여줍니다. 기능과 정보는 그대로라 오래된 폰·배터리 절약 중에 가볍게 쓰기 좋습니다. " +
                                "위에서 고른 테마는 그대로 남아 있어 끄면 바로 돌아옵니다.",
                            checked = minimalMode,
                            onCheckedChange = { checked -> applyMinimalMode(checked) }
                        )
                    }

                    Spacer(Modifier.height(Spacing.md))
                    SectionCard("미니멀 런처") {
                        val isDefaultLauncher = com.phonelock.app.ui.launcher.isDefaultLauncher(context)
                        var hiddenPackages by remember { mutableStateOf(prefs.launcherHiddenPackages) }

                        Text(
                            "기본 런처로 지정하면 홈 버튼을 눌렀을 때 아이콘 없는 텍스트 홈 화면이 뜹니다. " +
                                "그 홈에는 레벨·먼저 할 루틴·집중 시간·먼저 할 일정 요약과 아래에서 고른 디데이, " +
                                "그리고 앱 탭 5개(홈/루틴/집중/관리/모임) 바로가기가 함께 올라옵니다. " +
                                "즐겨찾기(최대 ${com.phonelock.app.ui.launcher.LAUNCHER_FAVORITE_MAX}개)와 앱 이름 바꾸기/숨기기는 " +
                                "런처의 \"모든 앱\"에서 앱을 길게 눌러 설정합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "지금 기본 런처: " +
                                if (isDefaultLauncher) "갓생살기종합세트"
                                else (com.phonelock.app.ui.launcher.currentLauncherLabel(context) ?: "선택 안 함"),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Button(onClick = {
                            // 이 앱이 이미 기본 런처인 상태에서 다시 고르는 건 "런처에서 빠져나가는" 행동이라
                            // 방지 시간대 안에서는 회유 절차를 거친다. 아직 기본이 아니면 바로 연다.
                            if (isDefaultLauncher && isEditProtectedNow()) {
                                pendingLauncherGate = true
                                pendingMinimalMessageIndex = 0
                            } else {
                                openLauncherChooser()
                            }
                        }) {
                            Text(if (isDefaultLauncher) "기본 런처 다시 고르기" else "기본 런처로 지정")
                        }
                        if (isDefaultLauncher) {
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                "참고: 시스템 설정 > 앱 > 기본 앱에서 홈 앱을 직접 바꾸는 건 앱이 막을 수 없습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.height(Spacing.md))
                        Text("숨긴 앱 (${hiddenPackages.size}개)", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(Spacing.xs))
                        if (hiddenPackages.isEmpty()) {
                            Text(
                                "없음",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "눌러서 다시 보이게 합니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                hiddenPackages.sorted().forEach { packageName ->
                                    val label = remember(packageName) {
                                        runCatching {
                                            val pm = context.packageManager
                                            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
                                        }.getOrDefault(packageName)
                                    }
                                    FilterChip(
                                        selected = true,
                                        onClick = {
                                            val next = hiddenPackages - packageName
                                            hiddenPackages = next
                                            prefs.launcherHiddenPackages = next
                                        },
                                        label = { Text("$label ✕") }
                                    )
                                }
                            }
                        }

                        // 홈 화면 디데이(133차, 사용자 요청) — 후보를 여러 개 만들어두고 그중 하나만 홈에 띄운다.
                        // 캘린더 일정에서 가져오면 이름/날짜를 복사해 담는다(가져온 뒤엔 일정이 바뀌거나
                        // 지워져도 디데이는 그대로 남는다 — 홈 화면 표시가 일정 관리에 끌려다니지 않게).
                        var ddays by remember {
                            mutableStateOf(com.phonelock.app.ui.launcher.parseLauncherDdays(prefs.launcherDdaysText))
                        }
                        var pinnedDdayId by remember { mutableStateOf(prefs.launcherPinnedDdayId) }
                        var showDdayAdd by remember { mutableStateOf(false) }
                        var ddayName by remember { mutableStateOf("") }
                        var ddayDate by remember { mutableStateOf("") }
                        var showDdayCalendarPick by remember { mutableStateOf(false) }
                        var ddayCalendarPicks by remember {
                            mutableStateOf<List<com.phonelock.app.data.CalendarTask>>(emptyList())
                        }

                        fun saveDdays(next: List<com.phonelock.app.ui.launcher.LauncherDday>) {
                            ddays = next
                            prefs.launcherDdaysText = com.phonelock.app.ui.launcher.launcherDdaysToText(next)
                        }

                        fun addDday(name: String, date: String) {
                            val item = com.phonelock.app.ui.launcher.LauncherDday(
                                id = "dday_" + System.currentTimeMillis(),
                                name = name.trim(),
                                date = date.trim()
                            )
                            saveDdays(ddays + item)
                            // 첫 후보는 곧바로 홈에 띄운다 — 만들었는데 아무 일도 안 일어나면 왜 안 뜨는지 알기 어렵다.
                            if (pinnedDdayId == null) {
                                pinnedDdayId = item.id
                                prefs.launcherPinnedDdayId = item.id
                            }
                        }

                        Spacer(Modifier.height(Spacing.md))
                        Text("홈 화면 디데이 (" + ddays.size + "개)", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "후보를 여러 개 만들어두고 그중 하나만 런처 홈 화면에 뜹니다. " +
                                "칩을 누르면 홈에 띄울 디데이가 되고, 다시 누르면 홈에서 내려갑니다. 뒤의 ✕는 후보를 지웁니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        if (ddays.isEmpty()) {
                            Text(
                                "없음",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                ddays.forEach { dday ->
                                    FilterChip(
                                        selected = dday.id == pinnedDdayId,
                                        onClick = {
                                            val next = if (dday.id == pinnedDdayId) null else dday.id
                                            pinnedDdayId = next
                                            prefs.launcherPinnedDdayId = next
                                        },
                                        label = { Text(dday.name + " · " + dday.date) },
                                        trailingIcon = {
                                            Text("✕", modifier = Modifier.clickable {
                                                saveDdays(ddays.filterNot { it.id == dday.id })
                                                if (pinnedDdayId == dday.id) {
                                                    pinnedDdayId = null
                                                    prefs.launcherPinnedDdayId = null
                                                }
                                            })
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            OutlinedButton(onClick = { ddayName = ""; ddayDate = ""; showDdayAdd = true }) {
                                Text("직접 추가")
                            }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val todayKey = java.time.LocalDate.now().toString()
                                    ddayCalendarPicks = runCatching {
                                        repository.getAllCalendarTasksOnce()
                                            .filter { it.dateKey >= todayKey }
                                            .sortedWith(compareBy({ it.dateKey }, { it.sortOrder }))
                                            .take(30)
                                    }.getOrDefault(emptyList())
                                    showDdayCalendarPick = true
                                }
                            }) { Text("캘린더에서 가져오기") }
                        }

                        if (showDdayAdd) {
                            AlertDialog(
                                onDismissRequest = { showDdayAdd = false },
                                title = { Text("디데이 추가") },
                                text = {
                                    Column {
                                        OutlinedTextField(
                                            value = ddayName,
                                            onValueChange = { ddayName = it },
                                            label = { Text("이름") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(Modifier.height(Spacing.sm))
                                        com.phonelock.app.ui.components.DatePickerField(
                                            value = ddayDate,
                                            onValueChange = { ddayDate = it },
                                            label = "날짜"
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        enabled = ddayName.isNotBlank() && ddayDate.isNotBlank(),
                                        onClick = { addDday(ddayName, ddayDate); showDdayAdd = false }
                                    ) { Text("추가") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDdayAdd = false }) { Text("취소") }
                                }
                            )
                        }

                        if (showDdayCalendarPick) {
                            AlertDialog(
                                onDismissRequest = { showDdayCalendarPick = false },
                                title = { Text("캘린더에서 가져오기") },
                                text = {
                                    if (ddayCalendarPicks.isEmpty()) {
                                        Text(
                                            "오늘 이후로 등록된 캘린더 일정이 없습니다. 아래 \"직접 추가\"로 날짜를 고르세요.",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    } else {
                                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                                            ddayCalendarPicks.forEach { task ->
                                                Text(
                                                    task.dateKey + " · " + task.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier.fillMaxWidth()
                                                        .clickable {
                                                            addDday(task.name, task.dateKey)
                                                            showDdayCalendarPick = false
                                                        }
                                                        .padding(vertical = Spacing.sm)
                                                )
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { showDdayCalendarPick = false }) { Text("닫기") }
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(Spacing.md))
                    SectionCard("알림 필터 · 묶음 요약") {
                        var digestTimesText by remember { mutableStateOf(prefs.notificationDigestTimesCsv) }
                        val hasNotificationAccess =
                            com.phonelock.app.service.BackgroundMediaGuard.hasSessionAccess(context)

                        Text(
                            "고른 앱의 알림은 뜨는 즉시 사라지고, 아래 시각에 \"읽지 않은 알림 N건\" 한 줄로 한 번에 옵니다. " +
                                "전화·문자·알람과 진행 중인 알림(음악 재생 등)은 골라도 거르지 않습니다. " +
                                "지운 알림은 되돌릴 수 없어 제목만 요약에 담기니, 중요한 앱은 고르지 마세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!hasNotificationAccess) {
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                "알림 접근 권한이 꺼져 있어 지금은 동작하지 않습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            OutlinedButton(onClick = {
                                com.phonelock.app.service.BackgroundMediaGuard.openAccessSettings(context)
                            }) { Text("알림 접근 설정 열기") }
                        }

                        Spacer(Modifier.height(Spacing.sm))
                        OutlinedTextField(
                            value = digestTimesText,
                            onValueChange = { text ->
                                digestTimesText = text
                                prefs.notificationDigestTimesCsv = text
                                RoutineAlarmScheduler.scheduleNotificationDigest(context)
                            },
                            label = { Text("요약 시각 (쉼표로 구분, 예: 12:30, 18:30)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        val parsedTimes = com.phonelock.app.service.parseDigestTimes(digestTimesText)
                        Text(
                            if (parsedTimes.isEmpty()) "시각을 하나도 못 읽어서 요약이 발송되지 않습니다."
                            else "적용됨: " + parsedTimes.joinToString(", ") { "%02d:%02d".format(it.hour, it.minute) },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (parsedTimes.isEmpty()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(Spacing.sm))
                        Text("알림을 거를 앱", style = MaterialTheme.typography.titleSmall)
                        AppMultiSelectPicker(
                            initialSelection = prefs.notificationFilterPackages,
                            onChange = { selected ->
                                prefs.notificationFilterPackages = selected
                                RoutineAlarmScheduler.scheduleNotificationDigest(context)
                            },
                            searchLabel = "앱 검색 (고른 앱만 걸러집니다)"
                        )
                    }
                }

                SettingsCategory.RULES -> {
                    dayStartCard("🎯 집중 탭에서도 같은 값을 바꿀 수 있어요.")
                    Spacer(Modifier.height(Spacing.md))

                    SectionCard("차단 규칙 수정·삭제 방지") {
                        ToggleRow(
                            title = "방지 사용",
                            checked = editProtectionEnabled,
                            onCheckedChange = { checked ->
                                val start = editProtectionStartText.toIntOrNull() ?: prefs.editProtectionStartHour
                                val end = editProtectionEndText.toIntOrNull() ?: prefs.editProtectionEndHour
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
                        val protectedNow = isEditProtectionHour(
                            editProtectionEnabled, savedProtectionStart, savedProtectionEnd, java.time.LocalTime.now().hour
                        )
                        Text(
                            if (protectedNow) {
                                "지금은 방지 시간대(${savedProtectionStart}시~${savedProtectionEnd}시)입니다 — 지금 차단 중인 규칙을 약하게 바꾸거나 지우거나 끄려면 확인 질문 ${PERSUASION_MESSAGES.size}개를 통과해야 합니다."
                            } else {
                                "지금은 방지 시간대가 아닙니다 — 차단 규칙도, 이 방지 설정도 확인 질문 없이 바로 수정되고 다른 기기에도 그대로 동기화됩니다."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (protectedNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "끝 시각은 포함하지 않으며(예: 11~23이면 23시부터 자유), 시작과 끝이 같으면 하루 종일 적용됩니다. " +
                                "방지 시간대 안에서 방지를 끄거나 시간대를 좁혀 지금이 빠지게 하는 변경은 그 자체가 확인 질문을 거칩니다.",
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
                        SectionCard("차단 규칙 데이터 복구") {
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
                    dayStartCard("🗂️ 관리 탭의 같은 항목과 같은 값이에요.")
                    Spacer(Modifier.height(Spacing.md))
                    SectionCard("캘린더 반복 기본값") {
                        ToggleRow(
                            title = "새 일정을 반복으로 시작",
                            checked = defaultMultiPassEnabled,
                            onCheckedChange = { checked ->
                                defaultMultiPassEnabled = checked
                                prefs.defaultMultiPassEnabled = checked
                                repository.pushSettingsToFirebase()
                            }
                        )
                        Text(
                            "켜두면 캘린더에 새로 추가하는 일정이 완료(O) 시 다음 회차를 자동 생성하는 상태로 시작됩니다. 이미 만든 일정에는 영향 없고, 각 일정에서 개별적으로 다시 켜고 끌 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "계산기 업무와 연결하지 않고 캘린더에서 직접 추가하는 일정에 적용되는 기본 반복 횟수/간격입니다 " +
                                "(계산기 업무는 업무별로 각 업무 입력 카드에서 따로 설정).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        com.phonelock.app.ui.components.NumberStepperField(
                            label = "기본 반복 횟수",
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
                        Text("회차별 간격(일)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            defaultPassIntervals.forEachIndexed { i, days ->
                                com.phonelock.app.ui.components.NumberStepperField(
                                    label = "${i + 1}→${i + 2}회차",
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
                    SectionCard("집중 알림") {
                        Text(
                            "캘린더·일정표에 예정된 계획과 실제 진행 상황을 비교해서, 계획보다 늦어질 때만 알림을 보냅니다. " +
                                "일정한 간격으로 무조건 보내지 않으며 같은 종류의 알림은 하루에 한 번만 옵니다. " +
                                "이 설정은 기기별로 저장되고 동기화하지 않으므로 인터넷이 끊겨도 초기화되지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        ToggleRow(
                            title = "집중 알림 받기",
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
                                title = "집중 미실행 알림",
                                description = "오늘 예정된 일정이 있는데 아직 아무것도 하지 않았을 때.",
                                checked = studyAlertNotStarted,
                                onCheckedChange = { checked ->
                                    studyAlertNotStarted = checked
                                    prefs.studyAlertNotStartedEnabled = checked
                                }
                            )
                            ToggleRow(
                                title = "진행 페이스 지연 알림",
                                description = "진행량이 계획(기간 경과율)보다 뒤처졌을 때.",
                                checked = studyAlertPace,
                                onCheckedChange = { checked ->
                                    studyAlertPace = checked
                                    prefs.studyAlertPaceEnabled = checked
                                }
                            )
                            ToggleRow(
                                title = "일정 지연 알림",
                                description = "마감이 지났거나, 요일별 목표대로 해도 마감까지 다 못 끝낼 때(하루치 이상 모자랄 때만).",
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

                    SectionCard("집중 잠금 허용 사이트") {
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

                    // 142차(사용자 요청): 주 2일로 굳어 있던 방지권을 사용자가 정한다.
                    SectionCard("연속 기록 방지권") {
                        com.phonelock.app.ui.components.NumberStepperField(
                            label = "일주일에 넘어갈 수 있는 날",
                            value = routineStreakFreeze.toString(),
                            onValueChange = { text ->
                                val days = com.phonelock.shared.routine.RoutineStreak.clampFreeze(text.toIntOrNull() ?: routineStreakFreeze)
                                routineStreakFreeze = days
                                prefs.routineStreakFreezePerWeek = days
                                repository.pushSettingsToFirebase()
                            },
                            min = 0, max = com.phonelock.shared.routine.RoutineStreak.MAX_FREEZE_DAYS_PER_WEEK,
                            modifier = Modifier.width(200.dp)
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "일주일(월~일)에 이 일수만큼은 그날 루틴을 100% 채우지 못해도 연속 기록이 끊기지 않습니다. " +
                                "루틴 하나하나가 아니라 하루 단위이고, 넘어간 날은 연속 일수에 더해지지 않습니다. " +
                                "0으로 두면 하루만 못 채워도 끊깁니다. 다른 기기에도 같은 값이 적용됩니다.",
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
                            "모임마다 공개할 내 정보(루틴/집중/연속 기록/오늘 일정/집중 중 여부/작동 중인 차단 규칙)를 " +
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


                    SectionCard("오래된 사용 기록 정리") {
                        var lastResult by remember { mutableStateOf<Int?>(null) }
                        Text(
                            "12개월 이상 지난 사용시간/재확인 통과 횟수/집중 기록을 영구 삭제합니다(되돌리기 없음). " +
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
                            SectionCard("마지막 강제종료 로그") {
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
                            "현재 버전: ${repository.currentVersionCode()} · 하루 시작 시각이 지나면 하루 1회 자동으로도 확인합니다.",
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

                SettingsCategory.HELP -> {
                    // 도움말 본문은 shared/HelpContent.kt(데스크탑과 공유), 그리기는 HelpScreen.kt.
                    // 관리자가 꺼둔 기능 영역의 주제는 숨긴다(설정 카테고리를 숨기는 것과 같은 기준).
                    HelpCenter(
                        visibleAreas = buildSet {
                            add(com.phonelock.shared.HelpContent.Area.GENERAL)
                            if (prefs.permPlant) add(com.phonelock.shared.HelpContent.Area.HOME)
                            if (prefs.permRoutine) add(com.phonelock.shared.HelpContent.Area.ROUTINE)
                            if (prefs.permStudy) add(com.phonelock.shared.HelpContent.Area.STUDY)
                            if (prefs.permManage) add(com.phonelock.shared.HelpContent.Area.RULES)
                            if (prefs.permSocial) add(com.phonelock.shared.HelpContent.Area.SOCIAL)
                        }
                    )
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

    // 144차 리디자인: 폰은 드로어 대신 "목차 → 세부" 두 단계(목차는 아이콘 + 이름 + 한 줄 설명, 세부는 큰 제목),
    // 태블릿은 왼쪽 목차 + 오른쪽 세부. 뒤로가기는 세부 → 목차 → 설정 닫기 순서.
    val motion = com.phonelock.app.ui.theme.LocalAppMotion.current
    if (isTabletWidth()) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = Spacing.md)) {
                com.phonelock.app.ui.components.PageMasthead(title = "설정", overline = "갓생살기종합세트")
                Spacer(Modifier.height(Spacing.md))
                visibleCategories.forEach { cat ->
                    SettingsCategoryRow(cat, selected = category == cat, compact = true) { category = cat }
                }
            }
            androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.sm, top = Spacing.md), verticalAlignment = Alignment.Bottom) {
                    Text(category.label, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "설정 닫기") }
                }
                detailContent(Modifier.weight(1f).fillMaxWidth())
            }
        }
    } else {
        var detailOpen by rememberSaveable { mutableStateOf(false) }
        androidx.activity.compose.BackHandler(enabled = detailOpen) { detailOpen = false }
        androidx.compose.animation.AnimatedContent(
            targetState = detailOpen,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            transitionSpec = {
                val forward = targetState
                if (motion.reduced) {
                    androidx.compose.animation.fadeIn(motion.standard()) togetherWith androidx.compose.animation.fadeOut(motion.exit())
                } else {
                    (androidx.compose.animation.fadeIn(motion.emphasized()) + androidx.compose.animation.slideInHorizontally(motion.emphasized()) { w -> if (forward) w / 8 else -w / 8 }) togetherWith
                        (androidx.compose.animation.fadeOut(motion.exit()) + androidx.compose.animation.slideOutHorizontally(motion.exit()) { w -> if (forward) -w / 12 else w / 12 })
                }
            },
            label = "settingsLevel"
        ) { open ->
            if (!open) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl)) {
                    com.phonelock.app.ui.components.PageMasthead(title = "설정", overline = "갓생살기종합세트") {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "설정 닫기") }
                    }
                    Spacer(Modifier.height(Spacing.md))
                    visibleCategories.forEach { cat ->
                        SettingsCategoryRow(cat, selected = false, compact = false) {
                            category = cat
                            detailOpen = true
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { detailOpen = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "설정 목차로") }
                        com.phonelock.app.ui.components.Overline("설정", Modifier.weight(1f))
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "설정 닫기") }
                    }
                    Text(
                        category.label,
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(horizontal = Spacing.gutter).padding(bottom = Spacing.md)
                    )
                    detailContent(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

/** 설정 목차 한 줄 — 원 안의 아이콘 + 이름 + 한 줄 설명 + 오른쪽 화살표(폰). 태블릿 목차는 설명 없이 고른 줄을 옅게 칠한다. */
@Composable
private fun SettingsCategoryRow(cat: SettingsCategory, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = if (compact) Spacing.sm else 0.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) Spacing.sm else Spacing.gutter, vertical = if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(40.dp).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(cat.icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(cat.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
            if (!compact) {
                Text(cat.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
        if (!compact) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    "🔒 집중 잠금 허용 앱" + if (allowedCount > 0) " ($allowedCount)" else "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
            if (expanded) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "집중 타이머가 \"집중\" 페이즈로 진행 중일 때(휴식 중엔 아님) 여기서 고른 앱 외에는 열자마자 " +
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
