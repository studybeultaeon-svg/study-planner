package com.phonelock.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phonelock.app.ui.theme.Spacing
import com.phonelock.shared.GuideContent
import com.phonelock.shared.GuidePage
import kotlinx.coroutines.launch

/**
 * 그림으로 보는 사용법 안내(최초 실행 시 자동 표시 + 설정 화면 "도움말"에서 다시 열기).
 * 화면 이미지는 실제 스크린샷이 아니라 팔레트 색만 실제 테마에서 가져온 단순화된 모크업 —
 * 자산 파이프라인 없이 유지보수하기 위한 선택(설계는 DECISIONS.md 참고).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GuideScreen(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val pages = GuideContent.pages
            val pagerState = rememberPagerState(pageCount = { pages.size })
            val scope = rememberCoroutineScope()

            Column(Modifier.fillMaxSize().padding(Spacing.lg)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("건너뛰기") }
                }

                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
                    GuidePageContent(pages[index])
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
                                    if (i == pagerState.currentPage) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                        )
                    }
                }

                val isLast = pagerState.currentPage == pages.lastIndex
                Button(
                    onClick = {
                        if (isLast) {
                            onDismiss()
                        } else {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isLast) "시작하기" else "다음")
                }
            }
        }
    }
}

@Composable
private fun GuidePageContent(page: GuidePage) {
    // 이모지+제목+모크업(가로폭의 1/1.4 높이)+설명 여러 줄이 세로로 쌓여서, 화면이 작은 폰이나
    // 글자 크기를 키운 설정에서는 마지막 설명 줄이 화면 밖으로 잘려 아예 읽을 수 없었다 —
    // 페이지 안쪽을 세로 스크롤 가능하게 해서 어떤 크기에서도 전부 읽히게 한다.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(page.emoji, style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(Spacing.sm))
        Text(page.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.lg))

        GuideMockup(page.id, modifier = Modifier.fillMaxWidth().aspectRatio(1.4f))

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

/** 페이지별 미니 모크업 — 실제 화면을 그대로 그리지 않고 핵심 요소만 단순화해서 보여준다. */
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
                "intro" -> MockupIntro()
                "manage" -> MockupManage()
                "snooze" -> MockupSnooze()
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
private fun MockupIntro() {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        listOf("🗂️" to "관리", "📘" to "공부", "🌱" to "루틴", "👥" to "모임").forEach { (emoji, label) ->
            Column(
                Modifier.weight(1f).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(emoji, style = MaterialTheme.typography.headlineMedium)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall)
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
private fun MockupSnooze() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Text("⏸️", style = MaterialTheme.typography.headlineMedium)
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text("오늘 1/3회 사용", style = MaterialTheme.typography.labelMedium)
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
