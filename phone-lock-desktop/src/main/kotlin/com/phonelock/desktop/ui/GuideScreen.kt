package com.phonelock.desktop.ui

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
import androidx.compose.ui.draw.clip
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
 * 탭별 상세 도움말(97차 신규, 97차 2차 개편으로 페이징 도입) — 각 탭 화면의 ❓ 버튼과 설정 탭 "다시
 * 보기"가 공용으로 쓴다. 처음엔 한 화면에 섹션을 전부 나열했는데, 사용자가 "정보는 많은데 글만
 * 있다"고 지적 — 워크스루처럼 섹션 하나당 페이지 하나로 쪼개고 큰 아이콘 배지를 붙였다(안드로이드판
 * TabGuideDialog와 대칭). 0번 페이지는 탭 전체 소개+모크업, 이후 페이지는 섹션 하나씩.
 */
@Composable
fun TabGuideDialog(guide: TabGuide, onDismiss: () -> Unit) {
    var pageIndex by remember { mutableIntStateOf(0) }
    val pageCount = guide.sections.size + 1

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
                    key(pageIndex) {
                        if (pageIndex == 0) {
                            TabGuideIntroPage(guide)
                        } else {
                            TabGuideSectionPage(guide.sections[pageIndex - 1])
                        }
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
                    val isLast = pageIndex == pageCount - 1
                    Button(
                        onClick = { if (isLast) onDismiss() else pageIndex++ },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isLast) "확인" else "다음")
                    }
                }
            }
        }
    }
}

/** 이미지 리소스 키(예: "manage_limits")를 데스크탑 classpath 경로로 매핑(`src/main/resources/guide/`). */
private fun guideResourcePath(imageRes: String): String = "guide/$imageRes.png"

@Composable
private fun TabGuideIntroPage(guide: TabGuide) {
    Column(
        Modifier.fillMaxWidth().padding(top = Spacing.md),
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
            painter = androidx.compose.ui.res.painterResource(guideResourcePath(guide.imageRes)),
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
    Column(
        Modifier.fillMaxWidth().padding(top = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val imageRes = section.imageRes
        if (imageRes != null) {
            Text(section.heading, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.md))
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(guideResourcePath(imageRes)),
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

