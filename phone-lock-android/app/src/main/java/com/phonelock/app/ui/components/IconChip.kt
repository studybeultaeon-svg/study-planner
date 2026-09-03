package com.phonelock.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 순서변경(▲▼)/펼치기·접기(▶▼) 등에 앱 전체가 쓰던 유니코드 화살표 글리프("▲"/"▼"/"▶" Text)를
 * 벡터 아이콘 기반으로 교체한 공용 버튼(83차) — 앱 폰트를 카페24 써라운드로 바꾸면서 이 폰트에
 * 기하학 기호(▲▼▶) 글리프가 없어 화살표가 전부 안 보이는 문제가 생겼다. 텍스트 글리프는 폰트에
 * 의존하지만 벡터 아이콘은 폰트와 무관하게 항상 그려지므로 근본적으로 재발하지 않는다 — 앞으로
 * 화살표/토글류 버튼은 전부 이 컴포넌트로 통일한다(사용자 요청).
 */
@Composable
fun IconChip(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    size: androidx.compose.ui.unit.Dp = 20.dp,
    iconSize: androidx.compose.ui.unit.Dp = 14.dp,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.12f else 0.05f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(iconSize)
        )
    }
}
