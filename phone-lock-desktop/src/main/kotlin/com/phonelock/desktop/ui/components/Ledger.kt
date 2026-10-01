package com.phonelock.desktop.ui.components

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.ui.theme.LocalAppMotion
import com.phonelock.desktop.ui.theme.Spacing
import com.phonelock.desktop.ui.theme.pressScale

/*
 * "Ledger" 편집형 컴포넌트(144차 리디자인 — DECISIONS.md 144차, 안드로이드 ui/components/Ledger.kt와 같은 구성).
 *
 * 정보를 둥근 카드에 하나씩 담는 대신 **큰 숫자 · 작은 라벨 · 가는 선 · 여백**으로 나눈다. 화면마다 같은 부품을
 * 쓰되 배치(히어로/목록/대시보드)는 화면 목적에 맞게 다르게 짠다.
 */

/** 섹션·수치 위에 붙는 작은 라벨(메타 정보 층). 흐린 먹색, 넓은 자간. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing * 2),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** 섹션을 나누는 가는 선(카드 테두리 대신). */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 큰 숫자 + 단위. 숫자와 단위를 **같은 기준선**에 놓고 한 줄로 묶어서(줄바꿈 금지) "42"와 "분"이 떨어지지 않게 한다.
 * 숫자는 고정폭이라(에이투지체) 값이 바뀌어도 폭이 흔들리지 않는다.
 */
@Composable
fun BigNumber(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    style: TextStyle = MaterialTheme.typography.displaySmall,
    unitStyle: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onBackground,
    unitColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(modifier) {
        Text(value, style = style, color = color, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
        if (unit != null) {
            Spacer(Modifier.width(3.dp))
            Text(unit, style = unitStyle, color = unitColor, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
        }
    }
}

/** 라벨 + 큰 숫자 한 묶음(대시보드의 한 칸). 여러 개를 [StatRow]로 나란히 둔다. */
@Composable
fun StatBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    caption: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onBackground,
    valueStyle: TextStyle = MaterialTheme.typography.headlineMedium
) {
    Column(modifier) {
        Overline(label)
        Spacer(Modifier.height(6.dp))
        BigNumber(value, unit = unit, style = valueStyle, color = valueColor)
        if (caption != null) {
            Spacer(Modifier.height(2.dp))
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** [StatBlock] 여러 개를 같은 폭으로 나란히, 사이를 세로 가는 선으로 나눈다(카드 그리드 대신). */
@Composable
fun StatRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md), content = content)
}

/** 세로 가는 선 — [StatRow] 칸 사이. */
@Composable
fun VerticalHairline(modifier: Modifier = Modifier, height: Dp = 40.dp) {
    Box(modifier.width(1.dp).height(height).background(MaterialTheme.colorScheme.outlineVariant))
}

/**
 * 편집형 섹션 — 작은 라벨 줄(오른쪽에 동작 하나) + 내용. 카드로 감싸지 않고 위쪽 가는 선과 여백으로만 구분한다.
 * [divider]를 끄면 선 없이 여백만 둔다(화면 맨 첫 섹션).
 */
@Composable
fun LedgerSection(
    title: String,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth()) {
        if (divider) Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(top = Spacing.md, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Overline(title, Modifier.weight(1f))
            trailing?.invoke()
        }
        content()
    }
}

/**
 * 화면 머리(마스트헤드) — 작은 라벨(날짜 등) + 큰 화면 제목 + 오른쪽 동작들. 탭 화면마다 맨 위에 하나.
 * 제목은 한 줄로 두고(줄바꿈 금지), 길면 동작 버튼이 아니라 제목이 줄어든다(...).
 */
@Composable
fun PageMasthead(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.md, top = Spacing.md),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(Modifier.weight(1f)) {
            if (overline != null) {
                Overline(overline)
                Spacer(Modifier.height(2.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * 텍스트 탭(서브탭) — Material TabRow 대신. 고른 탭은 먹색 굵은 글씨, 나머지는 흐린 글씨이고, 아래 강조색 막대가
 * 고른 탭으로 미끄러져 간다(성능 모드에선 바로 옮겨 간다). 탭이 많아 넘치면 가로로 스크롤된다 — 라벨은 줄바꿈하지 않는다.
 */
@Composable
fun SectionTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = LocalAppMotion.current
    val density = LocalDensity.current
    // 각 탭의 x 위치·폭(px) — 처음 그려진 뒤에 채워진다.
    val positions = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
    val selected = positions[selectedIndex]
    val indicatorX by animateDpAsState(with(density) { (selected?.first ?: 0).toDp() }, motion.standard(), label = "tabX")
    val indicatorW by animateDpAsState(with(density) { (selected?.second ?: 0).toDp() }, motion.standard(), label = "tabW")
    val scroll = rememberScrollState()
    LaunchedEffect(selectedIndex, selected) {
        // 고른 탭이 화면 밖이면 보이게 끌어온다.
        val (x, w) = selected ?: return@LaunchedEffect
        if (x < scroll.value || x + w > scroll.value + scroll.viewportSize) scroll.animateScrollTo((x - 48).coerceAtLeast(0))
    }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Row(Modifier.padding(horizontal = Spacing.gutter - 10.dp)) {
                labels.forEachIndexed { index, label ->
                    val isSelected = index == selectedIndex
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .onGloballyPositioned { positions[index] = it.positionInParent().x.toInt() + with(density) { (Spacing.gutter - 10.dp).roundToPx() } to it.size.width }
                            .pressScale(interaction)
                            .clickable(interaction, indication = null, role = Role.Tab) { onSelect(index) }
                            .heightIn(min = 44.dp)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
            if (selected != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = indicatorX + 10.dp)
                        .width((indicatorW - 20.dp).coerceAtLeast(12.dp))
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
            }
        }
        Hairline()
    }
}


/**
 * 2단계 탭(세그먼트) — 섹션 탭 아래에서 한 화면 안의 보기만 바꿀 때(계산기 입력/결과/저장됨 등). 옅은 바탕 트랙 위에서
 * 고른 칸만 표면색 알약으로 떠오른다. 상위 [SectionTabs]보다 작고 조용해서 두 줄의 탭이 같은 무게로 겹쳐 보이지 않는다.
 */
@Composable
fun SegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = LocalAppMotion.current
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(3.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val bg by androidx.compose.animation.animateColorAsState(
                if (selected) MaterialTheme.colorScheme.surface else Color.Transparent, motion.quick(), label = "segBg"
            )
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .background(bg, RoundedCornerShape(10.dp))
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 토요일 글자색 — 한국 달력 관례(파랑, 일요일은 오류색). 라이트·다크 각각 읽히는 값이고, 흑백인 미니멀(성능) 모드에선
 * 색 대신 흐린 먹색으로 둔다(캘린더·일정표 공용).
 */
@Composable
fun saturdayInk(): Color = when {
    com.phonelock.desktop.ui.theme.LocalPerformanceMode.current -> MaterialTheme.colorScheme.onSurfaceVariant
    com.phonelock.desktop.ui.theme.LocalPhoneLockPalette.current.isDark -> Color(0xFF7DA6FF)
    else -> Color(0xFF2F62C9)
}

/** 상세 화면 앱바 색(144차) — 앱바가 흰 판으로 떠 보이지 않게 바탕색과 같게(스크롤해도 같게) 둔다. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ledgerTopBarColors() = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.background
)

/** 상세 화면의 뒤로 가기 아이콘 버튼(144차) — 화면마다 "<" 글자·이모지 대신 같은 벡터 아이콘. */
@Composable
fun LedgerBackButton(onBack: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onBack) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "뒤로"
        )
    }
}
