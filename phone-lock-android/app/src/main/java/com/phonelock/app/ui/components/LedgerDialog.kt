package com.phonelock.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/*
 * 떠 있는 창(146차 — DECISIONS.md 146차, 데스크탑 ui/components/LedgerDialog.kt와 같은 구성).
 *
 * Material `AlertDialog`의 톤 입힌 둥근 판(28dp) 대신 화면과 같은 종이 바탕 + 가는 테두리, 제목은 굵은 먹색,
 * 버튼 줄은 가는 선 아래에 둔다. 인자 이름이 `AlertDialog`와 같아서 호출부는 함수 이름만 바꾸면 된다.
 */

/**
 * 떠 있는 판 하나(종이 바탕 + 가는 테두리). 확인 창이 아닌 직접 짠 창(달력 등)도 이 판을 쓴다.
 * 다크 테마는 바탕이 거의 검정이라 같은 색이면 뒤 화면과 구분이 안 돼서 한 단 밝은 판(surfaceContainerLow)을 쓴다.
 */
@Composable
fun LedgerDialogSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dark = com.phonelock.app.ui.theme.LocalPhoneLockPalette.current.isDark
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = if (dark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        content = content
    )
}

/**
 * `AlertDialog` 자리. 제목 → 본문(길면 이 칸만 줄어든다) → 가는 선 → 오른쪽 정렬 버튼 줄.
 * 버튼을 본문 안에 직접 둔 창은 [confirmButton]·[dismissButton]을 둘 다 비우면 선과 버튼 줄이 빠진다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties()
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier, properties = properties) {
        LedgerDialogSurface {
            Column(Modifier.padding(top = 24.dp)) {
                if (title != null) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (text != null) {
                    Box(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 24.dp)) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                        }
                    }
                }
                if (confirmButton == null && dismissButton == null) {
                    Spacer(Modifier.height(24.dp))
                    return@Column
                }
                Spacer(Modifier.height(20.dp))
                Hairline()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (dismissButton != null) {
                        dismissButton()
                        Spacer(Modifier.width(8.dp))
                    }
                    confirmButton?.invoke()
                }
            }
        }
    }
}
