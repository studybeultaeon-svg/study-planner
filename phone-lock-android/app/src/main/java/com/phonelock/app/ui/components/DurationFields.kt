package com.phonelock.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.phonelock.app.ui.theme.Spacing

/** 시/분/초 3칸으로 나눠서 입력받는 시간 길이 입력 행. 각 칸은 빈 문자열이면 0으로 취급된다. */
@Composable
fun DurationFieldsRow(
    label: String,
    hoursText: String,
    onHoursChange: (String) -> Unit,
    minutesText: String,
    onMinutesChange: (String) -> Unit,
    secondsText: String,
    onSecondsChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(
                value = hoursText,
                onValueChange = onHoursChange,
                label = { Text("시") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = minutesText,
                onValueChange = onMinutesChange,
                label = { Text("분") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = secondsText,
                onValueChange = onSecondsChange,
                label = { Text("초") },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** h/m/s 텍스트 3칸을 총 초로 합산한다. 빈 값이나 잘못된 값은 0으로 취급한다. */
fun hmsTextToSeconds(hoursText: String, minutesText: String, secondsText: String): Int {
    val h = hoursText.toIntOrNull() ?: 0
    val m = minutesText.toIntOrNull() ?: 0
    val s = secondsText.toIntOrNull() ?: 0
    return h * 3600 + m * 60 + s
}

/** 총 초를 시/분/초 텍스트 3칸으로 나눈다. 0인 자리는 빈 문자열로 둔다(단, 전부 0이면 초 칸만 "0"). */
fun secondsToHmsText(totalSeconds: Int): Triple<String, String, String> {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    val hoursText = if (h > 0) h.toString() else ""
    val minutesText = if (m > 0) m.toString() else ""
    val secondsText = if (s > 0 || (h == 0 && m == 0)) s.toString() else ""
    return Triple(hoursText, minutesText, secondsText)
}

/** 총 초를 "1시간 30분 5초" 같은 읽기 좋은 문자열로 만든다. */
fun formatHms(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return buildString {
        if (h > 0) append("${h}시간 ")
        if (m > 0 || h > 0) append("${m}분 ")
        append("${s}초")
    }
}
