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
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
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
        // 144차: 편집형 머리 — 작은 라벨 + 큰 제목.
        com.phonelock.app.ui.components.Overline("시작하기 · 권한")
        Text("몇 가지만\n허락해 주세요", style = MaterialTheme.typography.headlineLarge)
        Text(
            "차단·알림이 제대로 동작하려면 필요합니다. 나중에 설정에서도 열 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionGuideItem(
                title = "알림",
                granted = notificationGranted,
                why = "루틴·모임 알림과 업데이트 안내에 씁니다.",
                actionLabel = "알림 허용하기",
                onAction = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            )
        }

        PermissionGuideItem(
            title = "접근성 서비스",
            granted = accessibilityEnabled,
            why = "막을 앱이 열리는 걸 감지합니다. 이 앱의 핵심입니다.",
            whereToSet = "열린 목록에서 이 앱을 찾아 켜세요.",
            actionLabel = "접근성 설정 열기",
            onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )

        PermissionGuideItem(
            title = "백그라운드 실행 보호",
            granted = batteryOptIgnored,
            why = "꺼 두면 절전 기능이 감시를 멈출 수 있습니다.",
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
            why = "루틴 알림이 정한 시각에 오게 합니다.",
            actionLabel = "정확한 알람 허용하기",
            onAction = {
                exactAlarmLauncher.launch(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                )
            }
        )

        // 125차: 잠긴 앱의 백그라운드 재생 차단(BackgroundMediaGuard)은 이 권한 없이는 동작하지 않는다.
        PermissionGuideItem(
            title = "알림 접근",
            granted = mediaAccessGranted,
            why = "잠긴 앱의 뒤에서 나는 재생을 멈춥니다. 알림 내용은 읽지 않습니다.",
            whereToSet = "흐리게 보이면 앱 정보 > 메뉴 > \"제한된 설정 허용\"부터 누르세요.",
            actionLabel = "알림 접근 설정 열기",
            onAction = { BackgroundMediaGuard.openAccessSettings(context) }
        )

        SectionCard("삭제 방지 (선택)") {
            PermissionStatus(deviceAdminActive)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "켜 두면 앱을 지우기 전에 이 권한부터 꺼야 합니다.",
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
                "접근성이 꺼져 있으면 차단이 동작하지 않습니다.",
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
    whereToSet: String? = null,
    actionLabel: String,
    onAction: () -> Unit
) {
    SectionCard(title) {
        // 146차: "왜 필요한가요?/어디서 설정하나요?" 문단 → 상태 한 줄 + 이유 한 줄(+ 꼭 필요한 안내만 한 줄).
        PermissionStatus(granted)
        Spacer(Modifier.height(Spacing.xs))
        Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!granted) {
            if (whereToSet != null) {
                Text(whereToSet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(Spacing.sm))
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(actionLabel, maxLines = 1, softWrap = false) }
        }
    }
}

/** 권한 상태 한 줄 — 켜짐은 성공색 체크, 꺼짐은 빈 원(146차: ✅/○ 글자 대신 벡터 아이콘). */
@Composable
private fun PermissionStatus(granted: Boolean) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Icon(
            if (granted) androidx.compose.material.icons.Icons.Filled.CheckCircle else androidx.compose.material.icons.Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (granted) com.phonelock.app.ui.theme.LocalPhoneLockPalette.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = Spacing.sm)
        )
        Text(
            if (granted) "설정됨" else "아직 설정 안 됨",
            style = MaterialTheme.typography.bodyLarge,
            color = if (granted) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun isNotificationGranted(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
