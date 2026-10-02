package com.phonelock.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.desktop.ui.components.Hairline
import com.phonelock.desktop.ui.components.Overline
import com.phonelock.desktop.ui.theme.LocalPhoneLockPalette
import com.phonelock.desktop.ui.theme.Spacing
import com.phonelock.shared.HelpContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 도움말(134차, 설정 → 도움말, 안드로이드판과 대칭) — 내용은 `shared/HelpContent.kt`(안드로이드와 공유하는 순수 데이터)에 있고,
 * 이 파일은 그것을 그리기만 한다.
 *
 * 146차: 그라디언트 판·이모지 원·테두리 카드 → Ledger 문법. 맨 위 검색과 주제 바로가기, "처음이라면 이 순서로",
 * 주제 목록(가는 선으로 나뉜 줄 — 누르면 그 자리에서 펼쳐짐) 순서. 팁·주의는 왼쪽 색 막대가 있는 띠로 보여 준다.
 * 검색어가 있으면 걸린 주제만 남기고 전부 펼친다. 설정 화면의 세로 스크롤 안에 들어가므로 여기선 스크롤을 따로
 * 만들지 않고, 바로가기는 BringIntoViewRequester로 바깥 스크롤을 그 주제까지 움직인다.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun HelpCenter(visibleAreas: Set<HelpContent.Area>) {
    val topics = remember(visibleAreas) { HelpContent.topicsFor(HelpContent.Platform.DESKTOP, visibleAreas) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val requesters = remember(topics) { topics.associate { it.id to BringIntoViewRequester() } }
    val scope = rememberCoroutineScope()
    val searching = query.isNotBlank()
    val shown = if (searching) topics.filter { HelpContent.matches(it, query) } else topics

    fun open(id: String) {
        if (id !in topics.map { it.id }) return
        query = ""
        expanded = expanded + id
        scope.launch {
            // 펼침 애니메이션이 자리를 잡은 뒤에 스크롤해야 주제 머리가 화면 위쪽에 온다.
            delay(120)
            requesters[id]?.bringIntoView()
        }
    }

    Column {
        HelpSearch(
            query = query,
            onQueryChange = { query = it },
            topics = topics,
            onOpenTopic = ::open
        )

        if (!searching) {
            HelpQuickStart(
                steps = HelpContent.quickStart.filter { step -> topics.any { it.id == step.topicId } },
                onOpenTopic = ::open
            )
        } else if (shown.isEmpty()) {
            Text(
                "\"${query.trim()}\"에 대한 도움말이 없습니다. 다른 낱말로 찾아 보세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
        }

        if (shown.isNotEmpty()) {
            Hairline()
            Overline("주제", Modifier.padding(top = Spacing.md, bottom = Spacing.xs))
        }
        shown.forEach { topic ->
            HelpTopicRow(
                topic = topic,
                expanded = searching || topic.id in expanded,
                onToggle = {
                    if (!searching) expanded = if (topic.id in expanded) expanded - topic.id else expanded + topic.id
                },
                modifier = Modifier.bringIntoViewRequester(requesters.getValue(topic.id))
            )
            Hairline()
        }

        if (!searching && expanded.isNotEmpty()) {
            TextButton(onClick = { expanded = emptySet() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("모두 접기", maxLines = 1, softWrap = false)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HelpSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    topics: List<HelpContent.Topic>,
    onOpenTopic: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = Spacing.md)) {
        Text("사용 설명서", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "무엇을 · 어디서 · 어떻게, 자주 묻는 질문까지.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.md))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) ({
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "검색어 지우기") }
            }) else null,
            placeholder = { Text("기능 검색 (예: 잠깐 풀기)") },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        if (query.isBlank()) {
            Spacer(Modifier.height(Spacing.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                topics.forEach { t ->
                    Text(
                        t.title.substringBefore(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onOpenTopic(t.id) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpQuickStart(steps: List<HelpContent.QuickStep>, onOpenTopic: (String) -> Unit) {
    if (steps.isEmpty()) return
    Hairline()
    Overline("처음이라면 이 순서로", Modifier.padding(top = Spacing.md, bottom = Spacing.xs))
    steps.forEachIndexed { i, step ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpenTopic(step.topicId) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NumberBadge(i + 1, size = 26)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(step.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs)
            )
        }
    }
    Spacer(Modifier.height(Spacing.sm))
}

@Composable
private fun HelpTopicRow(topic: HelpContent.Topic, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "helpChevron")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(topic.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    topic.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "접기" else "펼치기",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.sm).rotate(chevron)
            )
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(bottom = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Column {
                    Overline("찾아가는 길")
                    Text(topic.where, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                topic.sections.forEach { HelpSectionView(it) }
                if (topic.faqs.isNotEmpty()) HelpFaqList(topic.faqs)
            }
        }
    }
}

@Composable
private fun HelpSectionView(section: HelpContent.Section) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(section.heading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        section.blocks.forEach { block ->
            when (block) {
                is HelpContent.Block.Text -> Text(block.text, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
                is HelpContent.Block.Steps -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    block.items.forEachIndexed { i, item ->
                        Row(verticalAlignment = Alignment.Top) {
                            NumberBadge(i + 1, size = 22)
                            Spacer(Modifier.width(10.dp))
                            Text(item, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
                is HelpContent.Block.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.items.forEach { item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier.padding(top = 9.dp).size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(item, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
                is HelpContent.Block.Table -> HelpTable(block.rows)
                is HelpContent.Block.Tip -> Callout("알아 두기", block.text, MaterialTheme.colorScheme.primary)
                is HelpContent.Block.Warn -> Callout("주의", block.text, LocalPhoneLockPalette.current.warning)
            }
        }
    }
}

/** 단계 번호 — 옅은 강조 바탕 원 + 강조색 숫자(146차: 진한 원 대신 조용하게). */
@Composable
private fun NumberBadge(n: Int, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text("$n", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

/** 표 — 테두리 상자 없이 가는 선으로 나뉜 "항목 | 설명" 줄(146차). */
@Composable
private fun HelpTable(rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, (key, value) ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Text(
                    key,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(0.38f)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp,
                    modifier = Modifier.weight(0.62f)
                )
            }
        }
    }
}

/**
 * 팁·주의 한 칸 — 147차: 옅은 색 바탕 상자 → 왼쪽 색 막대 + 작은 라벨 + 글(바탕 없음, Ledger의 가는 선 문법).
 * 색은 라벨과 막대에만 써서 본문 글은 다른 글과 같은 먹색으로 읽힌다.
 */
@Composable
private fun Callout(label: String, text: String, accent: Color) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent, RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 2.dp, bottom = 2.dp)) {
            Overline(label, color = accent)
            Spacer(Modifier.height(2.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                lineHeight = 19.sp
            )
        }
    }
}

@Composable
private fun HelpFaqList(faqs: List<HelpContent.Faq>) {
    Column {
        Text("자주 묻는 질문", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        faqs.forEachIndexed { i, faq ->
            if (i > 0) Hairline()
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text("Q", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                    Text(faq.q, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.Top) {
                    Text("A", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                    Text(faq.a, style = MaterialTheme.typography.bodySmall, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
