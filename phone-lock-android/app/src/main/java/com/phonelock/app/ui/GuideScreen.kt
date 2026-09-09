package com.phonelock.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
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

/**
 * 이미지 리소스 키(예: "manage_limits")를 실제 drawable 리소스 ID로 변환. 97차 4차 개편으로 탭
 * 개요 5개+섹션별 이미지 20여 개, 총 27개까지 늘어나 일일이 when으로 나열하지 않고
 * `resources.getIdentifier`로 동적 조회한다(도움말 다이얼로그를 열 때만 호출되는 경로라 성능 영향 없음).
 */
private fun guideDrawableRes(context: android.content.Context, imageRes: String): Int {
    val id = context.resources.getIdentifier("guide_$imageRes", "drawable", context.packageName)
    return if (id != 0) id else com.phonelock.app.R.drawable.guide_manage_main
}

@Composable
private fun TabGuideIntroPage(guide: TabGuide) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(guide.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            guide.intro,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.md))

        // 97차 3차 개편: 실제 화면 레이아웃을 재현하고 번호 배지를 겹쳐 그린 이미지(GuideContent.kt 참고).
        // 이미지 자체엔 배지 숫자만 있고, 설명은 아래 범례로 별도 표시한다(이미지 재생성 없이 문구만 고칠 수 있게).
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = guideDrawableRes(context, guide.imageRes)),
            contentDescription = guide.title,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
        )
        Spacer(Modifier.height(Spacing.lg))

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            guide.legend.forEach { callout ->
                Row(verticalAlignment = Alignment.Top) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            Text(
                                callout.number.toString(),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Column {
                        Text(callout.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(callout.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        Text(
            "다음 페이지부터 기능을 하나씩 더 자세히 설명해요 →",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TabGuideSectionPage(section: com.phonelock.shared.GuideSection) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val imageRes = section.imageRes
        if (imageRes != null) {
            Text(section.heading, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.md))
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id = guideDrawableRes(context, imageRes)),
                contentDescription = section.heading,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            )
        } else {
            SectionIconBadge(section.icon)
            Spacer(Modifier.height(Spacing.md))
            Text(section.heading, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
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

