package com.phonelock.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * 당겨서 새로고침(98차, 사용자 요청) — 화면을 아래로 당기면 [onRefresh]를 호출하고 인디케이터를 보여준다.
 * 목록형 메인 화면(루틴/캘린더/계산기/그룹/모임)에 공통으로 쓰는 얇은 래퍼. 데스크탑판은 스와이프 제스처가
 * 없어 대신 새로고침 버튼을 둔다(각 화면 상단, 이 컴포저블과는 무관).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshBox(
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val state = rememberPullToRefreshState()
    if (state.isRefreshing) {
        LaunchedEffect(Unit) {
            // 버그 수정(사용자 제보 — 캘린더에서 새로고침 아이콘이 사라지지 않고 그대로 머무름):
            // onRefresh()가 예외를 던지면(예: Firebase 동기화 중 JSON 파싱/DB 트랜잭션 실패) endRefresh()
            // 호출이 스킵돼 isRefreshing이 영원히 true로 남아 스피너가 멈추지 않았다 — try/finally로 감싸서
            // 실패해도 항상 인디케이터가 닫히도록 한다.
            try {
                onRefresh()
            } finally {
                state.endRefresh()
            }
        }
    }
    Box(modifier.fillMaxSize().nestedScroll(state.nestedScrollConnection)) {
        content()
        PullToRefreshContainer(state = state, modifier = Modifier.align(Alignment.TopCenter))
    }
}
