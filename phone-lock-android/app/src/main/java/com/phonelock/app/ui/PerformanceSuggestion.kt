package com.phonelock.app.ui

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonelock.app.data.AppPreferences

/**
 * 성능 모드(= 미니멀 모드) 추천(144차 리디자인). 오래된·메모리가 적은 기기거나 배터리 절약 모드가 켜져 있으면 앱을 열 때
 * 한 번 "성능 최적화 모드를 켤까요?"라고 묻는다 — **절대 스스로 바꾸지 않는다**(사용자가 고른다). "나중에"는 7일 동안,
 * "다시 묻지 않기"는 영구히 묻지 않는다. 판정은 [shouldSuggestPerformanceMode] 하나(유닛 테스트 대상).
 */
enum class PerformanceReason { LOW_END_DEVICE, POWER_SAVE }

internal const val PERFORMANCE_SUGGEST_SNOOZE_MS = 7L * 24 * 60 * 60 * 1000

/** 지금 추천해야 하는가 — 이미 켜져 있거나, 영구 거절했거나, 미뤄 둔 기간이거나, 이유가 없으면 null. */
internal fun shouldSuggestPerformanceMode(
    minimalModeOn: Boolean,
    lowEndDevice: Boolean,
    powerSaveOn: Boolean,
    never: Boolean,
    snoozedUntilMillis: Long,
    nowMillis: Long
): PerformanceReason? = when {
    minimalModeOn || never || nowMillis < snoozedUntilMillis -> null
    powerSaveOn -> PerformanceReason.POWER_SAVE
    lowEndDevice -> PerformanceReason.LOW_END_DEVICE
    else -> null
}

/** 이 기기가 저사양에 가까운가 — 시스템이 저메모리 기기로 분류했거나 앱이 쓸 수 있는 메모리가 작다. */
internal fun isLowEndDevice(context: Context): Boolean = runCatching {
    val am = context.getSystemService(ActivityManager::class.java)
    am.isLowRamDevice || am.memoryClass <= 128
}.getOrDefault(false)

internal fun isPowerSaveOn(context: Context): Boolean = runCatching {
    context.getSystemService(PowerManager::class.java).isPowerSaveMode
}.getOrDefault(false)

/**
 * 앱 시작 시 한 번 띄우는 추천 창. [onEnable]은 미니멀 모드를 켠 뒤 테마를 다시 그리게 하는 콜백(설정 화면의 스위치와 같은 경로).
 */
@Composable
fun PerformanceModeSuggestion(onEnable: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var reason by remember {
        mutableStateOf(
            shouldSuggestPerformanceMode(
                minimalModeOn = prefs.minimalMode,
                lowEndDevice = isLowEndDevice(context),
                powerSaveOn = isPowerSaveOn(context),
                never = prefs.performanceSuggestNever,
                snoozedUntilMillis = prefs.performanceSuggestSnoozedUntil,
                nowMillis = System.currentTimeMillis()
            )
        )
    }
    val current = reason ?: return
    AlertDialog(
        onDismissRequest = {
            prefs.performanceSuggestSnoozedUntil = System.currentTimeMillis() + PERFORMANCE_SUGGEST_SNOOZE_MS
            reason = null
        },
        title = { Text("성능 최적화 모드를 켤까요?") },
        text = {
            Column {
                Text(
                    when (current) {
                        PerformanceReason.POWER_SAVE -> "지금 배터리 절약 모드가 켜져 있어요."
                        PerformanceReason.LOW_END_DEVICE -> "이 기기는 메모리가 넉넉하지 않은 편이에요."
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "미니멀(성능) 모드는 기능과 정보는 그대로 두고, 흑백 화면과 짧은 움직임으로 배터리와 처리량을 아낍니다. " +
                        "설정 > 화면에서 언제든 다시 바꿀 수 있어요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                reason = null
                onEnable()
            }) { Text("켜기") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    prefs.performanceSuggestSnoozedUntil = System.currentTimeMillis() + PERFORMANCE_SUGGEST_SNOOZE_MS
                    reason = null
                }) { Text("나중에") }
                TextButton(onClick = {
                    prefs.performanceSuggestNever = true
                    reason = null
                }) { Text("다시 묻지 않기") }
            }
        }
    )
}
