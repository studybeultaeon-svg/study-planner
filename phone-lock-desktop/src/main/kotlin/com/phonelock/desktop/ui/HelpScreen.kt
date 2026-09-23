package com.phonelock.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.desktop.ui.theme.Spacing
import com.phonelock.shared.HelpContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 도움말(134차, 설정 → ❓ 도움말, 안드로이드판과 대칭) — 내용은 `shared/HelpContent.kt`(데스크탑과 공유하는 순수 데이터)에 있고,
 * 이 파일은 그것을 카드로 그리기만 한다.
 *
 * 구성: 맨 위 소개 카드(검색 + 주제 바로가기) → "처음이라면 이 순서로" 네 단계 → 주제별 카드. 주제 카드는 접혀
 * 있다가 누르면 펼쳐지고, 펼치면 📍 찾아가는 길 → 섹션(설명·단계·표·팁·주의) → 자주 묻는 질문 순서로 보여준다.
 * 검색어가 있으면 걸린 주제만 남기고 전부 펼친다. 설정 화면의 세로 스크롤 안에 들어가므로 여기선 스크롤을 따로
 * 만들지 않고, 바로가기는 BringIntoViewRequester로 바깥 스크롤을 그 카드까지 움직인다(안드로이드판과 대칭).
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
            // 펼침 애니메이션이 자리를 잡은 뒤에 스크롤해야 카드 머리가 화면 위쪽에 온다.
            delay(120)
            requesters[id]?.bringIntoView()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        HelpHero(
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
                "\"${query.trim()}\"에 대한 도움말을 찾지 못했어요. 다른 낱말로 검색해 보세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xs)
            )
        }

        shown.forEach { topic ->
            HelpTopicCard(
                topic = topic,
                expanded = searching || topic.id in expanded,
                onToggle = {
                    if (!searching) expanded = if (topic.id in expanded) expanded - topic.id else expanded + topic.id
                },
                modifier = Modifier.bringIntoViewRequester(requesters.getValue(topic.id))
            )
        }

        if (!searching && expanded.isNotEmpty()) {
            TextButton(onClick = { expanded = emptySet() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("모든 카드 접기")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HelpHero(
    query: String,
    onQueryChange: (String) -> Unit,
    topics: List<HelpContent.Topic>,
    onOpenTopic: (String) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, primary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(primary.copy(alpha = 0.16f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.06f))))
                .padding(Spacing.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(primary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) { Text("📖", fontSize = 26.sp) }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text("사용 설명서", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "기능마다 무엇을 · 어디서 · 어떻게 쓰는지, 알아둘 점과 자주 묻는 질문까지 정리했어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                leadingIcon = { Text("🔍") },
                trailingIcon = if (query.isNotEmpty()) ({ TextButton(onClick = { onQueryChange("") }) { Text("✕") } }) else null,
                placeholder = { Text("궁금한 기능을 검색해 보세요 (예: 잠깐 풀기, 환생)") },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            if (query.isBlank()) {
                Spacer(Modifier.height(Spacing.sm))
                Text("바로가기", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.xs))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    topics.forEach { t ->
                        Text(
                            "${t.emoji} ${t.title.substringBefore(" · ")}",
                            style = MaterialTheme.typography.labelLarge,
                            color = primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(primary.copy(alpha = 0.10f))
                                .clickable { onOpenTopic(t.id) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpQuickStart(steps: List<HelpContent.QuickStep>, onOpenTopic: (String) -> Unit) {
    if (steps.isEmpty()) return
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Text("🚀 처음이라면 이 순서로", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Spacing.sm))
            steps.forEachIndexed { i, step ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenTopic(step.topicId) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    NumberBadge(i + 1, size = 26)
                    Spacer(Modifier.width(Spacing.sm))
                    Column(Modifier.weight(1f)) {
                        Text("${step.emoji} ${step.title}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = Spacing.xs))
                }
            }
        }
    }
}

@Composable
private fun HelpTopicCard(topic: HelpContent.Topic, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "helpChevron")
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (expanded) 1.5.dp else 1.dp, if (expanded) primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(primary.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) { Text(topic.emoji, fontSize = 22.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(topic.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        topic.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text("⌄", fontSize = 22.sp, color = primary, modifier = Modifier.padding(start = Spacing.sm).rotate(chevron))
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(primary.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("📍", modifier = Modifier.padding(end = 6.dp))
                        Text(topic.where, style = MaterialTheme.typography.bodySmall, color = primary, fontWeight = FontWeight.Medium)
                    }
                    topic.sections.forEach { HelpSectionView(it) }
                    if (topic.faqs.isNotEmpty()) HelpFaqList(topic.faqs)
                }
            }
        }
    }
}

@Composable
private fun HelpSectionView(section: HelpContent.Section) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(section.heading)
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
                                Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(item, style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
                is HelpContent.Block.Table -> HelpTable(block.rows)
                is HelpContent.Block.Tip -> Callout("💡", block.text, MaterialTheme.colorScheme.tertiary)
                is HelpContent.Block.Warn -> Callout("⚠️", block.text, MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NumberBadge(n: Int, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HelpTable(rows: List<Pair<String, String>>) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            rows.forEachIndexed { i, (key, value) ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        key,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(0.38f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(value, style = MaterialTheme.typography.bodySmall, lineHeight = 19.sp, modifier = Modifier.weight(0.62f))
                }
            }
        }
    }
}

@Composable
private fun Callout(icon: String, text: String, accent: Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.10f)).padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(icon, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, lineHeight = 19.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun HelpFaqList(faqs: List<HelpContent.Faq>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("자주 묻는 질문")
        faqs.forEach { faq ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
}
