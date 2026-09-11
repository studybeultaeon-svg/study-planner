package com.phonelock.app.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * 당겨서 새로고침(98차, 사용자 요청) — 화면을 아래로 당기면 [onRefresh]를 호출하고 인디케이터를 보여준다.
 * 목록형 메인 화면(루틴/캘린더/계산기/그룹/모임)에 공통으로 쓰는 얇은 래퍼. 데스크탑판은 스와이프 제스처가
 * 없어 대신 새로고침 버튼을 둔다(각 화면 상단, 이 컴포저블과는 무관).
 *
 * 106차: 안드로이드/태블릿에서 새로고침 아이콘이 다 돌고도 화면에 그대로 남는 버그 제보 —
 * 원인은 material3 1.2.x의 실험적 `rememberPullToRefreshState`+`PullToRefreshContainer` 조합이
 * 드래그가 임계값을 살짝 못 넘긴 채 손을 뗐을 때 인디케이터가 완전히 수축하지 못하고 멈춰버리는
 * 라이브러리 자체 버그였음([[BUGS.md]] 99차에서 원인 미확정으로 남겨뒀던 항목). compose-bom을
 * 2024.09.00으로 올려 material3 1.3.0의 안정화된(비-실험적) `PullToRefreshBox` API로 교체 —
 * 이 API는 `isRefreshing`/`onRefresh`만 넘기면 인디케이터 표시/수축을 내부에서 전부 관리한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshBox(
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            scope.launch {
                try {
                    onRefresh()
                } finally {
                    isRefreshing = false
                }
            }
        },
        modifier = modifier,
        content = content
    )
}
