package com.phonelock.app.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phonelock.app.R
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.routine.RoutineAlarmScheduler
import com.phonelock.app.service.AccessibilityServiceChecker
import com.phonelock.app.service.BackgroundMediaGuard
import com.phonelock.app.service.PhoneLockDeviceAdminReceiver
import com.phonelock.app.ui.components.SectionCard
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * 권한 설정 가이드 — 106차 신설. 로그인 직후(최초 실행) 한 번 보여주고, 이후엔 설정 탭 "권한 설정
 * 가이드" 버튼으로 언제든 다시 열 수 있다([SettingsScreen]에서 재사용). 각 항목마다 왜 필요한지/어떤
 * 기능에 쓰이는지/어디서 설정하는지를 순서대로 설명하고, OS 공식 API(Settings 인텐트, 권한 요청
 * 런처)로만 이동시킨다 — 시스템 설정 화면의 특정 버튼을 대신 눌러주는 동작은 만들지 않는다.
 *
 * 상태는 화면이 다시 보일 때(ON_RESUME, 시스템 설정 앱 갔다 온 경우 포함)마다 자동으로 새로고침한다.
 */
@Composable
fun PermissionOnboardingScreen(repository: PhoneLockRepository, onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var notificationGranted by remember { mutableStateOf(isNotificationGranted(context)) }
    var accessibilityEnabled by remember { mutableStateOf(AccessibilityServiceChecker.isEnabled(context)) }
    var batteryOptIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var exactAlarmGranted by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    var deviceAdminActive by remember { mutableStateOf(isDeviceAdminActive(context)) }
    var mediaAccessGranted by remember { mutableStateOf(BackgroundMediaGuard.hasSessionAccess(context)) }

    fun refreshAll() {
        notificationGranted = isNotificationGranted(context)
        accessibilityEnabled = AccessibilityServiceChecker.isEnabled(context)
        batteryOptIgnored = isIgnoringBatteryOptimizations(context)
        exactAlarmGranted = canScheduleExactAlarms(context)
        deviceAdminActive = isDeviceAdminActive(context)
        mediaAccessGranted = BackgroundMediaGuard.hasSessionAccess(context)
    }

    // 설정 앱을 갔다가 이 화면으로 돌아왔을 때(특히 접근성처럼 결과 콜백이 없는 startActivity 방식)
    // 상태가 항상 최신으로 반영되도록 화면이 다시 보일 때마다 새로고침한다.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshAll()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationGranted = granted }

    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { batteryOptIgnored = isIgnoringBatteryOptimizations(context) }

    val exactAlarmLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        exactAlarmGranted = canScheduleExactAlarms(context)
        if (exactAlarmGranted) {
            scope.launch { RoutineAlarmScheduler.rescheduleAll(context, repository) }
        }
    }

    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { deviceAdminActive = isDeviceAdminActive(context) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text("권한 설정 가이드", style = MaterialTheme.typography.headlineSmall)
        Text(
            "이 앱이 차단/알림 기능을 제대로 쓰려면 몇 가지 권한이 필요합니다. 하나씩 안내해 드릴게요. " +
                "지금 건너뛰어도 설정 탭에서 언제든 다시 열 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionGuideItem(
                title = "알림",
                granted = notificationGranted,
                why = "루틴/연속 기록/모임 알림, 자체 업데이트 안내를 받으려면 필요합니다.",
                whereToSet = "이 버튼을 누르면 바로 시스템 권한 요청 창이 뜹니다.",
                actionLabel = "알림 허용하기",
                onAction = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            )
        }

        PermissionGuideItem(
            title = "접근성 서비스",
            granted = accessibilityEnabled,
            why = "차단 대상 앱이 켜졌는지 감지해서 잠그거나 실행 확인 대기 화면을 띄우는 핵심 기능입니다.",
            whereToSet = "설정 > 접근성 > 설치된 앱 목록에서 이 앱을 찾아 켜야 합니다. 버튼을 누르면 " +
                "그 목록 화면으로 바로 이동합니다.",
            actionLabel = "접근성 설정 열기",
            onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )

        PermissionGuideItem(
            title = "백그라운드 실행 보호",
            granted = batteryOptIgnored,
            why = "배터리 최적화 대상에서 빼두지 않으면 제조사 배터리 관리 기능이 감시 기능을 강제로 죽일 수 있습니다.",
            whereToSet = "버튼을 누르면 이 앱을 배터리 최적화에서 제외할지 묻는 시스템 대화상자가 바로 뜹니다.",
            actionLabel = "백그라운드 실행 보호 켜기",
            onAction = {
                batteryOptLauncher.launch(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            }
        )

        PermissionGuideItem(
            title = "정확한 알람",
            granted = exactAlarmGranted,
            why = "루틴 알림이 설정한 시각에 정확히 오게 하려면 필요합니다. 꺼져 있으면 배터리 절약 때문에 몇 분 늦게 올 수 있습니다.",
            whereToSet = "버튼을 누르면 이 앱에 정확한 알람 권한을 허용할지 묻는 시스템 설정 화면이 바로 뜹니다.",
            actionLabel = "정확한 알람 허용하기",
            onAction = {
                exactAlarmLauncher.launch(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                )
            }
        )

        // 125차: 잠긴 앱의 백그라운드 재생 차단(BackgroundMediaGuard)은 이 권한 없이는 동작하지 않는다.
        PermissionGuideItem(
            title = "알림 접근 — 잠긴 앱 백그라운드 재생 차단",
            granted = mediaAccessGranted,
            why = "차단 규칙으로 잠겼거나 실행 확인을 통과하지 않았거나 공부 잠금 중인 앱(Spotify 등)이 화면 뒤에서 음악을 " +
                "계속 트는 걸 멈추려면 필요합니다. 어느 앱이 재생 중인지 알아내는 데만 쓰고 알림 내용은 읽거나 저장하지 않습니다. " +
                "꺼져 있으면 잠긴 앱의 백그라운드 재생을 막지 못하고, 백그라운드 재생 시간도 사용시간에 들어가지 않습니다.",
            whereToSet = "버튼을 누르면 알림 접근(기기 및 앱 알림) 설정 화면이 열립니다. 이 앱을 켜 주세요. 켤 수 없게 " +
                "흐리게 보이면 앱 정보 > 오른쪽 위 메뉴 > \"제한된 설정 허용\"을 먼저 누르세요(접근성 켤 때와 같음).",
            actionLabel = "알림 접근 설정 열기",
            onAction = { BackgroundMediaGuard.openAccessSettings(context) }
        )

        SectionCard("삭제 방지 (선택)") {
            Text(
                if (deviceAdminActive) "✅ 설정됨" else "○ 아직 설정 안 됨",
                style = MaterialTheme.typography.bodyLarge,
                color = if (deviceAdminActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "왜 필요한가요? 켜두면 이 앱을 삭제하기 전에 먼저 이 권한부터 해제해야 해서, 충동적으로 앱을 " +
                    "지워버리는 걸 막아줍니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "어디서 설정하나요? 버튼을 누르면 기기 관리자 권한을 요청/해제하는 시스템 화면이 바로 뜹니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            if (deviceAdminActive) {
                OutlinedButton(
                    onClick = {
                        val dpm = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        dpm.removeActiveAdmin(PhoneLockDeviceAdminReceiver.componentName(context))
                        deviceAdminActive = isDeviceAdminActive(context)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("삭제 방지 해제") }
            } else {
                Button(
                    onClick = {
                        deviceAdminLauncher.launch(
                            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, PhoneLockDeviceAdminReceiver.componentName(context))
                                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.device_admin_description))
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("삭제 방지 켜기") }
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        if (!accessibilityEnabled) {
            Text(
                "접근성 서비스를 켜지 않으면 차단/실행 확인 기능이 동작하지 않습니다. 지금 건너뛰어도 나중에 " +
                    "설정 탭 \"권한 설정 가이드\"에서 다시 켤 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(Spacing.sm))
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(if (accessibilityEnabled) "완료" else "나중에 설정하기")
        }
    }
}

@Composable
private fun PermissionGuideItem(
    title: String,
    granted: Boolean,
    why: String,
    whereToSet: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    SectionCard(title) {
        Text(
            if (granted) "✅ 설정됨" else "○ 아직 설정 안 됨",
            style = MaterialTheme.typography.bodyLarge,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.xs))
        Text("왜 필요한가요? $why", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.xs))
        Text("어디서 설정하나요? $whereToSet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!granted) {
            Spacer(Modifier.height(Spacing.sm))
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) }
        }
    }
}

internal fun isNotificationGranted(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
