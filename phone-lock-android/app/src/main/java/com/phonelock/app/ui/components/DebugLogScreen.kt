package com.phonelock.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonelock.app.util.InAppLogger

/**
 * 디버그 로그 인앱 뷰어(82차, 감사보고서 §10③) — 실기기 검증 워크플로에서 사용자가 adb 없이 최근 오류를
 * 바로 확인/복사해서 다음 세션에 전달할 수 있게 한다. 판정 로직과 무관한 순수 관측 도구.
 */
@Composable
fun DebugLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lines = remember { InAppLogger.loadFromFileIfEmpty(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("디버그 로그 (최근 ${lines.size}줄)") },
        text = {
            if (lines.isEmpty()) {
                Text("기록된 로그가 없습니다.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    lines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("debug_log", lines.joinToString("\n")))
                Toast.makeText(context, "복사했습니다", Toast.LENGTH_SHORT).show()
            }) { Text("복사") }
        },
        dismissButton = {
            OutlinedButton(onClick = {
                InAppLogger.clear(context)
                onDismiss()
            }) { Text("지우고 닫기") }
        }
    )
}
