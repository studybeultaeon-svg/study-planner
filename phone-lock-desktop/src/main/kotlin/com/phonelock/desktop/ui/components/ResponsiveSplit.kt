package com.phonelock.desktop.ui.components

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
 * 데스크탑 좌우 분할(마스터-디테일) 화면들이 창을 좁혀도 항상 Row로 남아있어서, 폭이 줄면 양쪽 모두
 * 그만큼 좁아지며 텍스트/버튼이 뭉개지던 문제(79차, 사용자 요청)를 해결하기 위한 공용 레이아웃.
 * [narrowBreakpoint] 미만이면 안드로이드처럼 위아래로 쌓아(Column) 각 영역이 항상 창 전체 폭을
 * 쓰게 하고, 그 이상이면 기존과 동일한 좌우 분할(Row)을 유지한다. 두 모드 모두 각 영역은
 * weight로 부모로부터 유한한 크기를 받으므로, 내부에서 쓰는 `weight()`/`verticalScroll()` 등이
 * 모드 전환과 무관하게 그대로 동작한다(중첩 스크롤 충돌 없음).
 */
@Composable
fun ResponsiveSplit(
    modifier: Modifier = Modifier,
    narrowBreakpoint: Dp = 760.dp,
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
