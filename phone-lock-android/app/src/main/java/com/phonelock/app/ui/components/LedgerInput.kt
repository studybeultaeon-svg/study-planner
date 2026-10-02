package com.phonelock.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Ledger 입력칸(147차 — DECISIONS.md 147차, 데스크탑 ui/components/LedgerInput.kt와 같은 구성).
 *
 * 사용자 지적: 규칙 편집·계산기 업무 입력에 "글씨 쓰는 상자"가 기능마다 하나씩이라 과하고 투박하다. 테두리 상자 대신
 * 장부의 기입란처럼 작은 라벨 + 값 + 아래 가는 선 하나로 둔다(포커스 땐 선과 라벨이 강조색). [CompactField]·
 * [CompactNumberField]·[NumberStepperField]·[CompactDateField]·[DatePickerField]가 모두 이 틀을 쓴다.
 */
@Composable
fun LedgerInputFrame(
    modifier: Modifier = Modifier,
    label: String? = null,
    focused: Boolean = false,
    centerLabel: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (focused) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centerLabel) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        // 선 두께가 1 → 2dp로 바뀌어도 아래 줄이 밀리지 않게 2dp 칸 안에서 바닥에 붙여 그린다.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            Box(
                Modifier.fillMaxWidth().height(if (focused) 2.dp else 1.dp).align(Alignment.BottomStart)
                    .background(if (focused) accent else MaterialTheme.colorScheme.outline)
            )
        }
    }
}

/** 입력칸 안의 작은 아이콘 버튼(줄이기·늘리기·위·아래) — 판 없이 강조색 아이콘만, 누르는 범위는 원. */
@Composable
fun LedgerInputGlyph(
    icon: ImageVector,
    contentDescription: String?,
    size: Dp = 28.dp,
    iconSize: Dp = 18.dp,
    onClick: () -> Unit
) {
    Box(
        Modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(iconSize))
    }
}
