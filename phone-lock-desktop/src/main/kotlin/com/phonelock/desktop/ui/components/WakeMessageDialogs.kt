package com.phonelock.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.phonelock.desktop.monitor.VoiceRecorder
import com.phonelock.desktop.ui.theme.Spacing

/**
 * "무전기"는 "😴 깨우기"의 확장이라는 관점에서, 대상을 깨울 때 항상 이 선택창부터 거친다 — 알림만 보낼지,
 * 음성을 녹음해 보낼지, 텍스트를 적어 TTS로 읽어줄지. 멤버 목록의 빠른 깨우기 버튼/멤버 상세 화면 둘 다
 * 이 다이얼로그를 공유한다(중복 방지, 안드로이드판과 대칭).
 */
@Composable
fun WakeOptionsDialog(
    targetName: String,
    onDismiss: () -> Unit,
    onNudge: () -> Unit,
    onOpenVoiceRecorder: () -> Unit,
    onOpenTextMessage: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$targetName 깨우기") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                // 음성/텍스트는 여기서 onDismiss()를 부르면 안 된다 — onDismiss는 "깨우기 대상 자체를
                // 잊어버려라"는 뜻(cancelWakeFlow)인데, 음성/텍스트는 이 선택창 다음 단계에서 그 대상이
                // 계속 필요하다. wakeStep이 바뀌면 이 다이얼로그는 어차피 (wakeStep=="options" 조건이
                // 깨지면서) 화면에서 사라지므로 별도로 닫을 필요가 없다 — 실제로 이걸 부르는 바람에
                // "선택 후 대상이 사라져서 아무 일도 안 일어나는" 버그가 있었다.
                Button(onClick = { onDismiss(); onNudge() }, modifier = Modifier.fillMaxWidth()) { Text("😴 알림만 보내기") }
                Button(onClick = onOpenVoiceRecorder, modifier = Modifier.fillMaxWidth()) { Text("🎙️ 음성 메시지 녹음") }
                Button(onClick = onOpenTextMessage, modifier = Modifier.fillMaxWidth()) { Text("💬 텍스트 메시지(읽어주기)") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

/** 무전 녹음 다이얼로그 — 시작/정지, 최대 [VoiceRecorder.MAX_DURATION_MS] 자동 종료, 완료 후 보내기/취소. */
@Composable
fun VoiceRecordDialog(onDismiss: () -> Unit, onSend: (ByteArray, Long) -> Unit) {
    var elapsedMs by remember { mutableStateOf(0L) }
    var isRecording by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val session = remember { VoiceRecorder.Session() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎙️ 무전 녹음") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        finished -> "녹음 완료 (${elapsedMs / 1000}초)"
                        isRecording -> "녹음 중... ${elapsedMs / 1000}초 / ${VoiceRecorder.MAX_DURATION_MS / 1000}초"
                        else -> "버튼을 눌러 녹음을 시작하세요(최대 ${VoiceRecorder.MAX_DURATION_MS / 1000}초)"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(Spacing.md))
                if (!isRecording && !finished) {
                    Button(onClick = {
                        isRecording = true
                        Thread {
                            session.start { elapsed -> elapsedMs = elapsed }
                            isRecording = false
                            finished = true
                        }.start()
                    }) { Text("녹음 시작") }
                } else if (isRecording) {
                    Button(onClick = { session.stop() }) { Text("정지") }
                }
            }
        },
        confirmButton = {
            if (finished) {
                Button(onClick = { onSend(session.toWavBytes(), session.durationMs) }) { Text("보내기") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

/** 텍스트 메시지(TTS) 작성 다이얼로그 — 상대 기기에서 이 문장을 읽어준다. */
@Composable
fun TextMessageDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("💬 텍스트 메시지") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "상대 기기에서 이 문장을 소리내어 읽어줍니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("메시지") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSend(text) }, enabled = text.isNotBlank()) { Text("보내기") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}
