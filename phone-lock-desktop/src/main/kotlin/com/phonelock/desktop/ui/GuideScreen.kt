package com.phonelock.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.ui.theme.Spacing
import com.phonelock.shared.GuideContent
import com.phonelock.shared.GuidePage
import com.phonelock.shared.TabGuide

/**
 * 최초 실행 시 자동 표시되는 짧은 워크스루(2페이지). 개별 기능 상세는 더 이상 여기서 다루지 않고
 * [TabGuideDialog]로 옮겼다(97차, 배경은 GuideContent.kt 참고). 안드로이드판(스와이프)과 달리
 * 데스크탑은 마우스 조작이 기본이라 이전/다음 버튼으로 페이지를 넘긴다.
 */
@Composable
fun GuideScreen(onDismiss: () -> Unit) {
    val pages = GuideContent.pages
    var pageIndex by remember { mutableIntStateOf(0) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(480.dp).fillMaxSize().padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("건너뛰기") }
                }

                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    key(pageIndex) { GuidePageContent(pages[pageIndex]) }
                }

                Row(
                    Modifier.fillMaxWidth().padding(vertical = Spacing.md),
                    horizontalArrangement = Arrangement.Center
                ) {
                    pages.indices.forEach { i ->
                        Box(
                            Modifier
                                .padding(horizontal = 4.dp)
                                .size(8.dp)
                                .background(
                                    if (i == pageIndex) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                        )
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (pageIndex > 0) {
                        OutlinedButton(onClick = { pageIndex-- }, modifier = Modifier.weight(1f)) { Text("이전") }
                    }
                    val isLast = pageIndex == pages.lastIndex
                    Button(
                        onClick = { if (isLast) onDismiss() else pageIndex++ },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isLast) "시작하기" else "다음")
                    }
                }
            }
        }
    }
}

@Composable
private fun GuidePageContent(page: GuidePage) {
    Column(
        Modifier.fillMaxWidth().padding(top = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(page.emoji, style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(Spacing.sm))
        Text(page.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.lg))

        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            page.bullets.forEach { line ->
                Row {
                    Text("•  ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * 탭별 상세 도움말(97차 신규) — 각 탭 화면의 ❓ 버튼과 설정 탭 "다시 보기"가 공용으로 쓴다. 안드로이드판
 * TabGuideDialog와 대칭이며, 페이지 넘김 없이 한 화면에 섹션별로 쭉 나열한다.
 */
@Composable
fun TabGuideDialog(guide: TabGuide, onDismiss: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.width(560.dp).fillMaxSize().padding(Spacing.lg)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("닫기") }
                }

                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(guide.emoji, style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(Spacing.sm))
                    Text(guide.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        guide.intro,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(Spacing.md))

                    GuideMockup(guide.id, modifier = Modifier.fillMaxWidth().aspectRatio(2.2f))
                    Spacer(Modifier.height(Spacing.lg))

                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                        guide.sections.forEach { section ->
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                Text(
                                    section.heading,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(4.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    section.bullets.forEach { line ->
                                        Row {
                                            Text("•  ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                            Text(line, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.lg))
                }

                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
                    Text("확인")
                }
            }
        }
    }
}

/** 페이지별 미니 모크업 — 실제 화면을 그대로 그리지 않고 핵심 요소만 단순화해서 보여준다(안드로이드판과 대칭). */
@Composable
private fun GuideMockup(pageId: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Box(Modifier.fillMaxSize().padding(Spacing.md)) {
            when (pageId) {
                "manage" -> MockupManage()
                "study" -> MockupStudy()
                "routine" -> MockupRoutine()
                "social" -> MockupSocial()
                "settings" -> MockupSettings()
                else -> {}
            }
        }
    }
}

@Composable
private fun MockupManage() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        listOf("SNS 차단" to true, "게임 차단" to false).forEach { (name, locked) ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (locked) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            if (locked) "🔒 잠김" else "사용 가능",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MockupStudy() {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Column(Modifier.weight(1f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("25:00", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("공부 중", style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.primary).forEach { c ->
                Box(Modifier.fillMaxWidth().weight(1f).background(c, RoundedCornerShape(4.dp)))
            }
        }
    }
}

@Composable
private fun MockupRoutine() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        listOf("아침 스트레칭" to true, "물 8잔 마시기" to true, "일기 쓰기" to false).forEach { (name, done) ->
            Row(
                Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        if (done) Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun MockupSocial() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        listOf("나" to 82, "친구 A" to 55, "친구 B" to 10).forEach { (name, pct) ->
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, style = MaterialTheme.typography.labelMedium)
                    Text("$pct%", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(2.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))) {
                    Box(
                        Modifier
                            .fillMaxWidth(pct / 100f)
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun MockupSettings() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        listOf("Google 로그인" to "✓ 연결됨", "동기화" to "방금", "새 버전" to "확인하기").forEach { (label, value) ->
            Row(
                Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
