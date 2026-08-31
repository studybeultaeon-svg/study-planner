package com.phonelock.app.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab as MaterialTab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import com.phonelock.app.R
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PreMigrationBackup
import com.phonelock.app.routine.RoutineAlarmScheduler
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.service.AccessibilityServiceChecker
import com.phonelock.app.service.AccountSyncClient
import com.phonelock.app.service.AuthManager
import com.phonelock.app.service.PhoneLockDeviceAdminReceiver
import com.phonelock.app.ui.components.SectionCard
import com.phonelock.app.ui.components.ToggleRow
import com.phonelock.app.ui.theme.Spacing
import com.phonelock.app.widget.RoutineWidgetProvider
import kotlinx.coroutines.launch
import org.json.JSONObject

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun isDeviceAdminActive(context: android.content.Context): Boolean {
    val dpm = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return dpm.isAdminActive(PhoneLockDeviceAdminReceiver.componentName(context))
}

private fun canScheduleExactAlarms(context: android.content.Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return true
    val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    return am.canScheduleExactAlarms()
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    repository: PhoneLockRepository,
    onNavigateToStudyLockApps: () -> Unit = {},
    onThemeChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context) }

    var themeMode by remember { mutableStateOf(prefs.themeMode) }
    var customBgText by remember { mutableStateOf(prefs.customThemeBackground) }
    var customAccentText by remember { mutableStateOf(prefs.customThemeAccent) }
    var accessibilityEnabled by remember { mutableStateOf(AccessibilityServiceChecker.isEnabled(context)) }
    var deviceAdminActive by remember { mutableStateOf(isDeviceAdminActive(context)) }
    var batteryOptIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var exactAlarmGranted by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    var blockReels by remember { mutableStateOf(prefs.blockReels) }
    var blockShorts by remember { mutableStateOf(prefs.blockShorts) }
    var routineStreakNotifyEnabled by remember { mutableStateOf(prefs.routineStreakNotifyEnabled) }
    var defaultMultiPassEnabled by remember { mutableStateOf(prefs.defaultMultiPassEnabled) }
    var settingsSubTab by remember { mutableIntStateOf(0) }
    var dailyResetHourText by remember { mutableStateOf(prefs.dailyResetHour.toString()) }
    var loginId by remember { mutableStateOf(AuthManager.currentLoginId) }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    val autoBackups = remember { PreMigrationBackup.listBackups(context) }
    var groupRestoreResult by remember { mutableStateOf<String?>(null) }
    var showRoutineRestoreConfirmDialog by remember { mutableStateOf(false) }
    var pendingRoutineRestoreUri by remember { mutableStateOf<Uri?>(null) }

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

    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        deviceAdminActive = isDeviceAdminActive(context)
    }

    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOptIgnored = isIgnoringBatteryOptimizations(context)
    }

    val exactAlarmLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        exactAlarmGranted = canScheduleExactAlarms(context)
        if (exactAlarmGranted) {
            scope.launch { RoutineAlarmScheduler.rescheduleAll(context, repository) }
        }
    }

    if (showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = { Text("복원 확인") },
            text = { Text("복원하면 현재 그룹이 백업 파일 내용으로 대체됩니다. 계속할까요?") },
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

    Scaffold(
        topBar = { TopAppBar(title = { Text("설정") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 관리자가 승인 시 지정한 기능 범위(MainActivity.visibleTabs와 동일한 permXxx)에 맞춰
            // 해당 서브탭만 보여준다 — "공통"은 로그아웃 등 항상 필요한 항목이라 예외로 항상 표시.
            // 본문 각 섹션은 여전히 고정 인덱스(0~4)로 분기하므로 숨긴 탭은 그냥 선택 불가능해질 뿐이다.
            TabRow(selectedTabIndex = settingsSubTab) {
                MaterialTab(selected = settingsSubTab == 0, onClick = { settingsSubTab = 0 }, text = { Text("공통") })
                if (prefs.permRoutine) {
                    MaterialTab(selected = settingsSubTab == 1, onClick = { settingsSubTab = 1 }, text = { Text("루틴") })
                }
                if (prefs.permStudy) {
                    MaterialTab(selected = settingsSubTab == 2, onClick = { settingsSubTab = 2 }, text = { Text("공부") })
                }
                if (prefs.permManage) {
                    MaterialTab(selected = settingsSubTab == 3, onClick = { settingsSubTab = 3 }, text = { Text("관리") })
                }
                if (prefs.permSocial) {
                    MaterialTab(selected = settingsSubTab == 4, onClick = { settingsSubTab = 4 }, text = { Text("모임") })
                }
            }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md)
        ) {
          if (settingsSubTab == 0) {
            SectionCard("테마") {
                Text(
                    "앱 전체 배경/포인트 색과 차단/실행확인 화면 강조색, 홈 화면 위젯 색까지 함께 바뀝니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                // 53차: 3종→8종으로 늘면서 고정 Row가 화면 밖으로 잘려 찌부러지던 문제 —
                // 계산기 연동 업무 선택 버튼(50차)과 동일하게 FlowRow로 자동 줄바꿈.
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
                // 79차(사용자 요청): 배경색/포인트색 두 개만 직접 골라 나만의 테마를 만드는 기능.
                // 나머지 색은 buildCustomPalette()가 이 둘로부터 자동 계산한다(데스크탑판과 동일).
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

            SectionCard("권한 / 백그라운드 보호") {
                Text(
                    if (accessibilityEnabled) "접근성 서비스: 활성화됨" else "접근성 서비스: 비활성화됨",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "그룹 잠금/실행 확인 기능이 동작하려면 켜야 합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                Button(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("접근성 설정 열기")
                }
                Spacer(Modifier.height(Spacing.sm))
                Button(
                    onClick = { accessibilityEnabled = AccessibilityServiceChecker.isEnabled(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("상태 새로고침")
                }

                Spacer(Modifier.height(Spacing.md))
                Text(
                    if (batteryOptIgnored) "백그라운드 실행 보호: 활성화됨" else "백그라운드 실행 보호: 비활성화됨",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "배터리 최적화 대상에서 제외해서, 제조사 배터리 관리 기능이 앱을 강제로 죽이는 것을 막아줍니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!batteryOptIgnored) {
                    Spacer(Modifier.height(Spacing.sm))
                    Button(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                            batteryOptLauncher.launch(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("백그라운드 실행 보호 켜기")
                    }
                }

                Spacer(Modifier.height(Spacing.md))
                Text(
                    if (exactAlarmGranted) "정확한 알람: 허용됨" else "정확한 알람: 거부됨",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "허용하면 루틴 알림이 정확한 시각에 옵니다. 꺼져 있으면 배터리 절약 때문에 몇 분 늦게 올 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!exactAlarmGranted) {
                    Spacer(Modifier.height(Spacing.sm))
                    Button(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}")
                            )
                            exactAlarmLauncher.launch(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("정확한 알람 허용하기")
                    }
                }

                Spacer(Modifier.height(Spacing.md))
                Text(
                    if (deviceAdminActive) "삭제 방지: 활성화됨" else "삭제 방지: 비활성화됨",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "켜두면 삭제 전에 이 권한부터 해제해야 해서 충동적인 삭제를 막아줍니다. (강제종료는 안드로이드 시스템 자체가 막고 있어 어떤 앱도 방지할 수 없습니다.)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                if (deviceAdminActive) {
                    Button(
                        onClick = {
                            val dpm = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                            dpm.removeActiveAdmin(PhoneLockDeviceAdminReceiver.componentName(context))
                            deviceAdminActive = isDeviceAdminActive(context)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("삭제 방지 해제")
                    }
                } else {
                    Button(
                        onClick = {
                            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, PhoneLockDeviceAdminReceiver.componentName(context))
                                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.device_admin_description))
                            }
                            deviceAdminLauncher.launch(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("삭제 방지 켜기")
                    }
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
          }

          if (settingsSubTab == 4) {
            SectionCard("모임 공유 설정") {
                Text(
                    "모임마다 공개할 내 정보(루틴/공부/스트릭/오늘 일정/공부중 여부/작동 중인 관리 그룹)를 " +
                        "다르게 정할 수 있어, 여기가 아니라 각 모임 화면의 ⚙ 공유 설정에서 모임별로 관리합니다. " +
                        "특정 멤버에게만 내 정보를 숨기거나 특정 멤버의 정보를 안 보이게 하는 것도 그 " +
                        "멤버의 상세 화면에서 따로 설정할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "무전기(음성/텍스트 메시지) 수신 설정도 모임마다 다르게 정할 수 있어 여기가 아니라 각 모임 " +
                    "화면의 ⚙ 무전기 설정에서 관리합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }

          if (settingsSubTab == 3) {
            SectionCard("일일 사용 한도 초기화 시각") {
                OutlinedTextField(
                    value = dailyResetHourText,
                    onValueChange = { text ->
                        dailyResetHourText = text
                        text.toIntOrNull()?.let { if (it in 0..23) prefs.dailyResetHour = it }
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
                SectionCard("⚠ 그룹 데이터 복구") {
                    Text(
                        "앱 업데이트로 로컬 데이터가 초기화됐을 때 자동으로 만들어진 백업이 있습니다. 그룹(차단 " +
                            "대상 앱/사이트 목록)은 동기화되지 않는 데이터라 지워졌다면 이 백업에서만 복구할 수 " +
                            "있습니다. 그룹이 이미 정상적으로 보이면 누르지 마세요(같은 그룹이 중복으로 추가됩니다).",
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
                                groupRestoreResult = "그룹 ${count}개 복구 완료. 앱을 재시작해주세요."
                            }
                        }
                    }) { Text("이 백업에서 그룹 복구") }
                    groupRestoreResult?.let {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(Spacing.md))
            }

            SectionCard("백업 / 복원") {
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
          }

          if (settingsSubTab == 1) {
            SectionCard("루틴 스트릭 알림") {
                ToggleRow(
                    title = "스트릭 알림 받기",
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
                    "하루 중 랜덤한 시각에 어제 루틴 스트릭 상태를 알려줍니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(Spacing.md))
          }

          if (settingsSubTab == 0) {
            SectionCard("계정 동기화 (로그인 필수)") {
                Text(
                    "동기화(실행확인 레벨/스누즈/일일사용량/캘린더/계산기/루틴)는 이제 로그인이 있어야만 " +
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
            Spacer(Modifier.height(Spacing.md))

            if (loginId != null) {
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

            var isAdmin by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                isAdmin = AccountSyncClient.isAdmin(prefs.fbDatabaseUrl, prefs.fbApiKey)
            }
            if (isAdmin) {
                SectionCard("관리자 패널") {
                    var pendingUsers by remember { mutableStateOf<List<AccountSyncClient.PendingUser>>(emptyList()) }
                    var approvedUsers by remember { mutableStateOf<List<AccountSyncClient.ApprovedUser>>(emptyList()) }
                    // 대기 중인 사용자를 승인할 때 고를 권한 — 기본은 전부 허용, uid별로 독립적으로 고른다.
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
                Spacer(Modifier.height(Spacing.md))
            }
          }

          if (settingsSubTab == 3) {
            SectionCard("공부 잠금 허용 앱") {
                Text(
                    "공부앱 타이머가 \"공부\" 페이즈로 진행 중일 때(휴식 중엔 아님) 여기서 고른 앱 외에는 열자마자 " +
                        "감지해서 잠금 화면으로 돌려보냅니다. 기기 소유자 권한이 없어 진짜 실행 차단은 아니고, " +
                        "감지 후 재차단하는 베스트 에포트 방식입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                Button(onClick = onNavigateToStudyLockApps, modifier = Modifier.fillMaxWidth()) {
                    Text("허용 앱 선택")
                }
            }
            Spacer(Modifier.height(Spacing.md))
          }

          if (settingsSubTab == 0) {
            SectionCard("업데이트") {
                var checking by remember { mutableStateOf(false) }
                var apkUrl by remember { mutableStateOf(repository.pendingUpdateApkUrl()) }
                // 2026-08-30: "최신 버전"과 "확인 실패"(네트워크 오류, GitHub 요청 한도 초과 등)를 구분
                // 못 해서 실패해도 무조건 "최신 버전"으로 잘못 표시되던 문제를 고쳤다 — 이제 세 상태를
                // 명확히 나눠 보여준다(checkedOnce 대신 lastOutcome 하나로 표현).
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
                Button(
                    onClick = { scope.launch { lastResult = repository.pruneOldStats(12) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("🧹 12개월 이상 지난 기록 정리") }
                lastResult?.let {
                    Spacer(Modifier.height(Spacing.xs))
                    Text("$it 건 삭제됨", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(Spacing.md))

          }

          if (settingsSubTab == 2) {
            SectionCard("캘린더 다회독 기본값") {
                ToggleRow(
                    title = "새 일정을 다회독으로 시작",
                    checked = defaultMultiPassEnabled,
                    onCheckedChange = { checked ->
                        defaultMultiPassEnabled = checked
                        prefs.defaultMultiPassEnabled = checked
                    }
                )
                Text(
                    "켜두면 캘린더에 새로 추가하는 일정이 완료(O) 시 다음 회독을 자동 생성하는 상태로 시작됩니다. 이미 만든 일정에는 영향 없고, 각 일정에서 개별적으로 다시 켜고 끌 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
          }

          if (settingsSubTab == 1) {
            SectionCard("루틴 내보내기 / 가져오기") {
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
        }
        }
    }
}

/** 관리자 패널에서 사용자별 기능 범위(루틴/공부/관리/모임)를 고르는 칩 4개 — 눌린 것만 허용. */
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
