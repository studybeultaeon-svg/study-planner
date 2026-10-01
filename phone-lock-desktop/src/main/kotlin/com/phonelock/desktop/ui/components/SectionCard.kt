package com.phonelock.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.ui.theme.Spacing

/**
 * 관련 설정을 하나로 묶는 묶음(설정·규칙 편집·타이머 등 폼 화면 공용).
 *
 * 144차 리디자인: 테두리 카드 → **제목은 묶음 바깥 위쪽 글자, 내용은 테두리 없는 표면**(라이트 테마는 종이 위의 흰 판,
 * 다크 테마는 한 단계 밝은 판). 여러 묶음이 이어지는 폼에서 칸칸이 선으로 갇힌 느낌 없이 "제목 → 내용" 리듬으로 읽힌다.
 * [accentColor]를 주면 제목 앞에 그 색 점을 찍고 표면을 그 색으로 옅게 물들인다(경고·강조 묶음).
 * [emoji]는 예전 호출부 호환용으로 받기만 하고 그리지 않는다 — 이모지는 기기마다 그림·기준선이 달라 제목 줄이 흔들렸다.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") emoji: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val hasAccent = accentColor != Color.Unspecified
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, bottom = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            if (hasAccent) {
                Box(Modifier.size(8.dp).background(accentColor, CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (hasAccent) accentColor else MaterialTheme.colorScheme.onBackground
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = if (hasAccent) accentColor.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(Spacing.md)) {
                content()
            }
        }
    }
}
