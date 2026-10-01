package com.phonelock.desktop.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 관련 설정을 하나로 묶는 묶음(설정·규칙 편집·타이머 등 폼 화면 공용).
 *
 * 146차: 흰 판(표면)을 없애고 [LedgerSection]과 같은 문법 — 위쪽 가는 선 + 작은 라벨 + 종이 위에 바로 놓인 내용 — 으로
 * 그린다. 144차의 "제목 + 흰 판"은 기준 화면(차단 규칙 목록)과 다른 문법이라, 이 묶음을 쓰는 화면들이 리디자인 전처럼
 * 보였다(DECISIONS.md 146차). 화면 머리 바로 아래 첫 묶음은 [divider]를 꺼서 선이 겹치지 않게 한다.
 * [emoji]는 예전 호출부 호환용으로 받기만 하고 그리지 않는다 — 이모지는 기기마다 그림·기준선이 달라 제목 줄이 흔들렸다.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    @Suppress("UNUSED_PARAMETER") emoji: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    LedgerSection(title = title, modifier = modifier, divider = divider, content = content)
}
