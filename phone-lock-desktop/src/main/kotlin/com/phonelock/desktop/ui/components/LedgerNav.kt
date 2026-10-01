package com.phonelock.desktop.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.ui.theme.LocalAppMotion
import com.phonelock.desktop.ui.theme.pressScale

/** 하단 독/좌측 레일의 한 칸. 고르면 [selectedIcon](채운 아이콘), 아니면 [icon](외곽선 아이콘). */
data class LedgerNavItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
)

private val ItemHeight = 64.dp
private const val MARKER_WIDTH_DP = 20
private const val MARKER_THICKNESS_DP = 3

/**
 * 하단 독(폰) — 144차 리디자인. 바탕과 같은 색 위에 가는 선 하나로만 본문과 나누고, 고른 칸 위쪽에 강조색 짧은 막대
 * ("장부의 책갈피")가 미끄러져 간다. 고른 칸은 채운 아이콘 + 먹색 굵은 라벨, 나머지는 외곽선 아이콘 + 흐린 라벨.
 * 라벨은 항상 한 줄(칸 폭이 좁아도 줄바꿈하지 않고 글자 크기를 유지 — 다섯 칸 × 두 글자라 360dp 폭에서도 들어간다).
 */
@Composable
fun LedgerNavBar(
    items: List<LedgerNavItem>,
    selectedKey: String?,
    onSelect: (LedgerNavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = LocalAppMotion.current
    val selectedIndex = items.indexOfFirst { it.key == selectedKey }
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Hairline()
        BoxWithConstraints(Modifier.fillMaxWidth().height(ItemHeight)) {
            val cell = maxWidth / items.size.coerceAtLeast(1)
            val markerX by animateDpAsState(
                cell * selectedIndex.coerceAtLeast(0) + (cell - MARKER_WIDTH_DP.dp) / 2,
                motion.standard(),
                label = "navMarker"
            )
            if (selectedIndex >= 0) {
                Box(
                    Modifier
                        .offset(x = markerX)
                        .width(MARKER_WIDTH_DP.dp)
                        .height(MARKER_THICKNESS_DP.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp))
                )
            }
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { index, item ->
                    NavCell(item, index == selectedIndex, Modifier.weight(1f).fillMaxHeight()) { onSelect(item) }
                }
            }
        }
    }
}

/**
 * 좌측 레일(태블릿) — 위쪽에 작은 워드마크, 고른 칸 왼쪽에 세로 강조 막대가 미끄러져 간다. 오른쪽 가는 선으로 본문과 나눈다.
 */
@Composable
fun LedgerNavRail(
    items: List<LedgerNavItem>,
    selectedKey: String?,
    onSelect: (LedgerNavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = LocalAppMotion.current
    val selectedIndex = items.indexOfFirst { it.key == selectedKey }
    Row(modifier.fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.width(88.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                "갓생",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.height(28.dp))
            Box {
                val markerY by animateDpAsState(
                    ItemHeight * selectedIndex.coerceAtLeast(0) + (ItemHeight - 24.dp) / 2,
                    motion.standard(),
                    label = "railMarker"
                )
                if (selectedIndex >= 0) {
                    Box(
                        Modifier
                            .offset(y = markerY)
                            .width(MARKER_THICKNESS_DP.dp)
                            .height(24.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                    )
                }
                Column(verticalArrangement = Arrangement.Top) {
                    items.forEachIndexed { index, item ->
                        NavCell(item, index == selectedIndex, Modifier.width(88.dp).height(ItemHeight)) { onSelect(item) }
                    }
                }
            }
        }
        VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun NavCell(item: LedgerNavItem, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val ink = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .semantics { this.selected = selected; contentDescription = item.label }
            .pressScale(interaction, 0.92f)
            .clickable(interaction, indication = null, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            if (selected) item.selectedIcon else item.icon,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            item.label,
            style = if (selected) MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W700) else MaterialTheme.typography.labelMedium,
            color = ink,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}
