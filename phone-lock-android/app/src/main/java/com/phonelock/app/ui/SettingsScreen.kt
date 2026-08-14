package com.phonelock.app.ui

import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

private fun isNotificationPolicyAccessGranted(context: android.content.Context): Boolean {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return nm.isNotificationPolicyAccessGranted
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
    var accessibilityEnabled by remember { mutableStateOf(AccessibilityServiceChecker.isEnabled(context)) }
    var deviceAdminActive by remember { mutableStateOf(isDeviceAdminActive(context)) }
    var batteryOptIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var exactAlarmGranted by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    var blockReels by remember { mutableStateOf(prefs.blockReels) }
    var blockShorts by remember { mutableStateOf(prefs.blockShorts) }
    var routineStreakNotifyEnabled by remember { mutableStateOf(prefs.routineStreakNotifyEnabled) }
    var autoDndEnabled by remember { mutableStateOf(prefs.autoDndEnabled) }
    var notificationPolicyGranted by remember { mutableStateOf(isNotificationPolicyAccessGranted(context)) }
    var dailyResetHourText by remember { mutableStateOf(prefs.dailyResetHour.toString()) }
    var fbDatabaseUrlText by remember { mutableStateOf(prefs.fbDatabaseUrl ?: "") }
    var fbApiKeyText by remember { mutableStateOf(prefs.fbApiKey ?: "") }
    var fbUserText by remember { mutableStateOf(prefs.fbUser) }
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

    val notificationPolicyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        notificationPolicyGranted = isNotificationPolicyAccessGranted(context)
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.md)
        ) {
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
                    "이 시각이 되면 그룹별 오늘 사용 시간이 초기화됩니다.",
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

            SectionCard("루틴 스트릭 알림") {
                ToggleRow(
                    title = "스트릭 알림 받기",
                    checked = routineStreakNotifyEnabled,
                    onCheckedChange = { checked ->
                        routineStreakNotifyEnabled = checked
                        prefs.routineStreakNotifyEnabled = checked
                        if (checked) {
                            RoutineAlarmScheduler.scheduleStreakCheck(context, repository.dailyResetHour)
                        } else {
                            RoutineAlarmScheduler.cancelStreakCheck(context)
                        }
                    }
                )
                Text(
                    "매일 초기화 시각(위 \"일일 초기화\" 참고)에 어제 루틴 스트릭 상태를 알려줍니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(Spacing.md))

            SectionCard("공부 잠금 중 방해금지 모드") {
                Text(
                    "공부 잠금 화면이 뜨는 동안 자동으로 방해금지(우선순위만) 모드를 켜고, 잠금이 풀리면 원래대로 되돌립니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                ToggleRow(
                    title = "공부 잠금 중 방해금지 자동 적용",
                    checked = autoDndEnabled,
                    onCheckedChange = { checked ->
                        autoDndEnabled = checked
                        prefs.autoDndEnabled = checked
                    }
                )
                if (autoDndEnabled && !notificationPolicyGranted) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "알림 정책 접근 권한이 없어 아직 적용되지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Button(
                        onClick = { notificationPolicyLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("알림 정책 접근 권한 설정 열기") }
                }
            }
            Spacer(Modifier.height(Spacing.md))

            SectionCard("공부앱 연동 / 데스크탑과 실행 확인 레벨 동기화") {
                Text(
                    "공부앱(별도 웹앱)의 \"동기화 설정\"에 입력한 것과 동일한 Realtime Database URL / Web API Key / " +
                        "사용자 ID를 입력하세요. 이 설정은 두 가지에 쓰입니다 — ① 공부앱에서 뽀모도로 휴식이 시작될 때 " +
                        "그룹 편집 화면의 \"뽀모도로 휴식 시 자동 해제\"를 켜둔 그룹만 휴식 시간 동안 임시로 잠금이 " +
                        "풀립니다. ② 데스크탑과 동일한 값을 입력해두면 실행 확인 레벨이 Firebase를 통해 자동으로 " +
                        "동기화됩니다. 비워두면 두 기능 모두 꺼진 상태로 유지됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = fbDatabaseUrlText,
                    onValueChange = { text ->
                        fbDatabaseUrlText = text
                        prefs.fbDatabaseUrl = text.ifBlank { null }
                    },
                    label = { Text("Realtime Database URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = fbApiKeyText,
                    onValueChange = { text ->
                        fbApiKeyText = text
                        prefs.fbApiKey = text.ifBlank { null }
                    },
                    label = { Text("Web API Key") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = fbUserText,
                    onValueChange = { text ->
                        fbUserText = text
                        prefs.fbUser = text.ifBlank { "default" }
                    },
                    label = { Text("사용자 ID (공부앱과 동일하게, 비워두면 default)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(Spacing.md))

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
