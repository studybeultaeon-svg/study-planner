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
import androidx.compose.foundation.layout.widthIn
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
import com.phonelock.shared.TabGuide
import kotlinx.coroutines.launch

/**
 * 최초 실행(+로그인 완료) 시 자동으로 뜨는 짧은 워크스루(2페이지) — 개별 기능 상세는 더 이상 여기서
 * 다루지 않고 [TabGuideDialog]로 옮겼다(97차, 자세한 배경은 GuideContent.kt 참고). 태블릿에서 내용이
 * 화면보다 커서 매번 스크롤해야 보이던 문제를 해결하려 페이지 수를 줄이고, 폭을 [maxDialogWidth]로
 * 제한해 넓은 화면에서도 카드처럼 가운데 정렬되게 한다(데스크탑판과 같은 방식).
 */
private val maxDialogWidth = 480.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GuideScreen(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val pages = GuideContent.pages
                val pagerState = rememberPagerState(pageCount = { pages.size })
                val scope = rememberCoroutineScope()

                Column(Modifier.widthIn(max = maxDialogWidth).fillMaxSize().padding(Spacing.lg)) {
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
}

@Composable
private fun GuidePageContent(page: GuidePage) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(page.emoji, style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(Spacing.sm))
        Text(page.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.lg))

        Spacer(Modifier.height(Spacing.md))
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
 * 탭별 상세 도움말(97차 신규, 97차 2차 개편으로 페이징 도입) — 각 탭 화면의 ❓ 버튼과 설정 탭의
 * "다시 보기"에서 공용으로 쓴다. 처음엔 한 화면에 섹션을 전부 세로로 나열했는데, 사용자가 "정보는
 * 많은데 글만 있다"고 지적 — 워크스루처럼 섹션 하나당 페이지 하나로 쪼개고, 페이지마다 큰 아이콘
 * 배지를 붙여 읽을 정보량을 줄이고 시각적으로 덜 밋밋하게 했다. 0번 페이지는 탭 전체 소개+모크업,
 * 이후 페이지는 [TabGuide.sections] 하나씩.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabGuideDialog(guide: TabGuide, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                // 0번 = 탭 소개 페이지, 1번부터 섹션 하나당 한 페이지.
                val pageCount = guide.sections.size + 1
                val pagerState = rememberPagerState(pageCount = { pageCount })
                val scope = rememberCoroutineScope()

                Column(Modifier.widthIn(max = maxDialogWidth).fillMaxSize().padding(Spacing.lg)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("닫기") }
                    }

                    HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
                        if (index == 0) {
                            TabGuideIntroPage(guide)
                        } else {
                            TabGuideSectionPage(guide.sections[index - 1])
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Spacing.md),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        (0 until pageCount).forEach { i ->
                            Box(
                                Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(8.dp)
                                    .background(
                                        if (i == pagerState.currentPage) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                        CircleShape
                                    )
                            )
                        }
                    }

                    val isFirst = pagerState.currentPage == 0
                    val isLast = pagerState.currentPage == pageCount - 1
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (!isFirst) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                                modifier = Modifier.weight(1f)
                            ) { Text("이전") }
                        }
                        Button(
                            onClick = {
                                if (isLast) onDismiss()
                                else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isLast) "확인" else "다음")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabGuideIntroPage(guide: TabGuide) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = Spacing.md),
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

        GuideMockup(guide.id, modifier = Modifier.fillMaxWidth().aspectRatio(1.8f))
        Spacer(Modifier.height(Spacing.md))
        Text(
            "다음 페이지부터 기능을 하나씩 자세히 설명해요 →",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TabGuideSectionPage(section: com.phonelock.shared.GuideSection) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SectionIconBadge(section.icon)
        Spacer(Modifier.height(Spacing.md))
        Text(section.heading, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.lg))

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            section.bullets.forEach { line ->
                Row {
                    Text("•  ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** 섹션 페이지 상단의 큰 원형 아이콘 배지 — 실제 스크린샷 없이도 페이지마다 시각적 구심점을 준다. */
@Composable
private fun SectionIconBadge(icon: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            Text(icon, style = MaterialTheme.typography.displaySmall)
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
