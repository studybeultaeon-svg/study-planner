package com.phonelock.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 데스크탑 ui/components/ResponsiveSplit.kt를 그대로 이식(83차, 태블릿 UI를 데스크탑처럼) — 폭이
 * narrowBreakpoint 미만이면(폰) 위아래로 쌓고, 이상이면(태블릿) 좌우로 분할한다. 순수 Compose
 * (BoxWithConstraints)라 안드로이드/데스크탑 공용으로 그대로 동작.
 */
@Composable
fun ResponsiveSplit(
    modifier: Modifier = Modifier,
    narrowBreakpoint: Dp = 600.dp,
    leftWeight: Float = 1f,
    rightWeight: Float = 1f,
    spacing: Dp = 16.dp,
    left: @Composable BoxScope.() -> Unit,
    right: @Composable BoxScope.() -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth < narrowBreakpoint) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(leftWeight).fillMaxWidth(), content = left)
                Spacer(Modifier.height(spacing))
                Box(Modifier.weight(rightWeight).fillMaxWidth(), content = right)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(leftWeight).fillMaxHeight(), content = left)
                Spacer(Modifier.width(spacing))
                Box(Modifier.weight(rightWeight).fillMaxHeight(), content = right)
            }
        }
    }
}
