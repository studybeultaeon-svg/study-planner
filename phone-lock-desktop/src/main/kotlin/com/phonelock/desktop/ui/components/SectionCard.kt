package com.phonelock.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.ui.theme.Spacing

/**
 * 관련 설정을 하나로 묶는 카드. 화면마다 제각각이던 Divider 구분을 이걸로 통일한다.
 * 공부앱(index.html)의 카드 스타일(옅은 1px 테두리 + 진한 카드 배경)을 그대로 따른다.
 * [accentColor]를 주면 배경을 그 색으로 옅게 물들이고 테두리도 진하게 강조한다(웹앱의 상태별 색
 * 강조를 흉내낸 것 — 예: 계산 결과 카드는 성공/경고 색, 타이머 상태 카드는 공부/휴식 색).
 */
@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, accentColor: Color = Color.Unspecified, content: @Composable ColumnScope.() -> Unit) {
    val hasAccent = accentColor != Color.Unspecified
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (hasAccent) accentColor.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (hasAccent) 1.5.dp else 1.dp, if (hasAccent) accentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (hasAccent) accentColor else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))
            content()
        }
    }
}
