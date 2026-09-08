package com.phonelock.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.phonelock.app.ui.theme.Spacing

/**
 * 관련 설정을 하나로 묶는 카드. 화면마다 제각각이던 구분선을 이걸로 통일한다.
 * 공부앱(index.html)의 카드 스타일(옅은 1px 테두리 + 진한 카드 배경)을 그대로 따른다.
 * [accentColor]를 주면 배경을 그 색으로 옅게 물들이고 테두리도 진하게 강조한다(데스크탑판과 동일).
 * [emoji]를 주면 제목을 계산기 업무 카드의 "섹션 헤더 알약"(CalculatorScreen.kt의
 * CalcFieldGroupHeader와 같은 스타일 — 배경색 알약+이모지)로 그린다(96차, 차단 규칙 상세 화면 개편).
 * 안 주면(기본값 null) 기존처럼 밋밋한 titleMedium 텍스트 그대로라 다른 화면(설정/루틴 등)은 영향 없다.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.Unspecified,
    emoji: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val hasAccent = accentColor != Color.Unspecified
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (hasAccent) accentColor.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (hasAccent) 1.5.dp else 1.dp, if (hasAccent) accentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(Spacing.md)) {
            if (emoji != null) {
                val pillColor = if (hasAccent) accentColor else MaterialTheme.colorScheme.primary
                Text(
                    "$emoji $title",
                    style = MaterialTheme.typography.labelLarge,
                    color = pillColor,
                    modifier = Modifier
                        .background(pillColor.copy(alpha = 0.12f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            } else {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (hasAccent) accentColor else MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            content()
        }
    }
}
