package com.phonelock.app.ui.components

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonelock.app.service.MediaControlClient
import com.phonelock.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val POLL_INTERVAL_MS = 1000L
/** 명령을 보낸 뒤 대상 앱이 상태를 갱신할 시간을 조금 주고 곧바로 다시 읽는다. */
private const val REFRESH_AFTER_COMMAND_MS = 350L

private data class MediaCardState(
    val hasAccess: Boolean = false,
    val session: MediaControlClient.Session? = null,
    /** 알림 접근이 없을 때 기본 제어(미디어 키) 카드를 보여줄지. */
    val fallbackVisible: Boolean = false,
    val anyMusicPlaying: Boolean = false
)

/**
 * 백그라운드 음악 앱 제어 카드(125차) — 앱 화면을 열 수 없는 차단/공부 잠금 화면에서 이전 곡/재생·일시정지/
 * 다음 곡을 보낸다. 제어할 대상이 없으면 아무것도 그리지 않는다.
 *
 * @param targetPackage 잠김·실행확인 화면에서 막힌 앱. 그 앱이 음악 앱일 때만 그 앱의 컨트롤을 보여준다.
 *   null이면(공부 잠금 화면) [excludedPackages]에 없는 앱 중 재생 중인 앱의 컨트롤을 보여주고, 일시정지해도
 *   세션이 남아 있는 동안은 카드를 유지한다(다시 재생할 수 있게).
 * @param showAccessButton 알림 접근 설정으로 바로 가는 버튼 표시 여부 — 공부 잠금 중엔 설정 앱도 잠기므로 false.
 */
@Composable
fun MediaControlCard(
    targetPackage: String?,
    modifier: Modifier = Modifier,
    excludedPackages: Set<String> = emptySet(),
    showAccessButton: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(MediaCardState()) }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    val targetLabel = remember(targetPackage) {
        targetPackage?.let { pkg ->
            val pm = context.packageManager
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }
    }

    LaunchedEffect(targetPackage, excludedPackages) {
        val targetIsMediaApp = targetPackage != null &&
            withContext(Dispatchers.IO) { MediaControlClient.isMediaApp(context, targetPackage) }
        var stickyPackage: String? = null
        var musicSeen = false
        while (true) {
            state = withContext(Dispatchers.IO) {
                val access = MediaControlClient.hasSessionAccess(context)
                val musicPlaying = MediaControlClient.isAnyMusicPlaying(context)
                if (access) {
                    val sessions = MediaControlClient.activeSessions(context).filter { it.packageName != context.packageName }
                    val shown = if (targetPackage != null) {
                        sessions.firstOrNull { it.packageName == targetPackage }
                    } else {
                        val candidates = sessions.filter { it.packageName !in excludedPackages }
                        candidates.firstOrNull { it.isPlaying } ?: candidates.firstOrNull { it.packageName == stickyPackage }
                    }
                    stickyPackage = shown?.packageName
                    MediaCardState(hasAccess = true, session = shown, anyMusicPlaying = musicPlaying)
                } else {
                    if (musicPlaying) musicSeen = true
                    val visible = if (targetPackage != null) targetIsMediaApp else musicSeen
                    MediaCardState(hasAccess = false, fallbackVisible = visible, anyMusicPlaying = musicPlaying)
                }
            }
            withTimeoutOrNull(POLL_INTERVAL_MS) { wake.receive() }
        }
    }

    val current = state
    val session = current.session
    if (session == null && !current.fallbackVisible) return

    fun send(action: MediaControlClient.Action) {
        scope.launch(Dispatchers.IO) {
            MediaControlClient.send(context, session?.packageName ?: targetPackage, action)
            delay(REFRESH_AFTER_COMMAND_MS)
            wake.trySend(Unit)
        }
    }

    val isPlaying = session?.isPlaying ?: current.anyMusicPlaying
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
                "🎵 " + (session?.appLabel ?: targetLabel ?: "재생 중인 음악"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val detail = if (session != null) {
                listOf(session.title, session.artist).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { if (session.isPlaying) "재생 중" else "일시정지됨" }
            } else {
                "앱을 열지 않고 재생을 제어할 수 있습니다."
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                IconButton(onClick = { send(MediaControlClient.Action.PREVIOUS) }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "이전 곡")
                }
                FilledIconButton(
                    onClick = { send(MediaControlClient.Action.PLAY_PAUSE) },
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "일시정지" else "재생"
                    )
                }
                IconButton(onClick = { send(MediaControlClient.Action.NEXT) }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "다음 곡")
                }
            }
            if (!current.hasAccess) {
                Text(
                    "알림 접근이 꺼져 있어 안드로이드가 고른 현재 미디어 앱으로 명령이 갑니다. " +
                        "켜면 정확히 이 앱만 제어하고 곡 정보도 보입니다." +
                        if (showAccessButton) "" else " (설정 > 권한 설정 가이드)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (showAccessButton) {
                    TextButton(onClick = { MediaControlClient.openAccessSettings(context) }) { Text("알림 접근 켜기") }
                }
            }
        }
    }
}
