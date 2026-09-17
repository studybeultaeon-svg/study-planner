package com.phonelock.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.monitor.MediaSessionBridge
import com.phonelock.desktop.ui.theme.Spacing

/**
 * 백그라운드 음악 앱 제어 카드(125차, 안드로이드 MediaControlCard와 대칭) — 창을 열 수 없는 차단/공부 잠금
 * 화면에서 이전 곡/재생·일시정지/다음 곡을 보낸다. 제어할 세션이 없으면 아무것도 그리지 않는다.
 *
 * @param targetProcess 잠김·실행확인 화면에서 막힌 프로그램(예: "Spotify.exe"). 그 프로그램의 미디어 세션이
 *   있을 때만 그 세션의 컨트롤을 보여준다. null이면(공부 잠금 화면) [excludedProcesses](허용 프로그램)에 없는
 *   재생 중인 세션을 보여주고, 일시정지해도 세션이 남아 있는 동안은 카드를 유지한다(다시 재생할 수 있게).
 */
@Composable
fun MediaControlCard(
    targetProcess: String?,
    modifier: Modifier = Modifier,
    excludedProcesses: List<String> = emptyList()
) {
    DisposableEffect(Unit) {
        MediaSessionBridge.acquire()
        onDispose { MediaSessionBridge.release() }
    }
    val sessions by MediaSessionBridge.sessions.collectAsState()
    var stickyAppId by remember { mutableStateOf<String?>(null) }
    val session = if (targetProcess != null) {
        sessions.firstOrNull { MediaSessionBridge.matchesProcess(it.appId, targetProcess) }
    } else {
        val candidates = sessions.filterNot { s -> excludedProcesses.any { MediaSessionBridge.matchesProcess(s.appId, it) } }
        candidates.firstOrNull { it.isPlaying } ?: candidates.firstOrNull { it.appId == stickyAppId }
    }
    LaunchedEffect(session?.appId) { stickyAppId = session?.appId }
    if (session == null) return

    Surface(
        modifier = modifier.widthIn(max = 440.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            Modifier.padding(horizontal = Spacing.md, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "🎵 " + MediaSessionBridge.displayName(session.appId),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOf(session.title, session.artist).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { if (session.isPlaying) "재생 중" else "일시정지됨" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                IconButton(onClick = { MediaSessionBridge.send(session.appId, MediaSessionBridge.Action.PREVIOUS) }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "이전 곡")
                }
                FilledIconButton(
                    onClick = { MediaSessionBridge.send(session.appId, MediaSessionBridge.Action.PLAY_PAUSE) },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        if (session.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (session.isPlaying) "일시정지" else "재생"
                    )
                }
                IconButton(onClick = { MediaSessionBridge.send(session.appId, MediaSessionBridge.Action.NEXT) }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "다음 곡")
                }
            }
        }
    }
}
