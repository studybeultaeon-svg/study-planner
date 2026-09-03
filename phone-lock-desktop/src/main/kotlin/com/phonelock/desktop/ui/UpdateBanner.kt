package com.phonelock.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import kotlin.system.exitProcess

/**
 * "설정탭 초기화 시간이 지나면 그날 새 버전이 올라왔는지 확인해서 업데이트 문구를 띄워달라"는 요청으로
 * 신규 추가(2026-08-30, 안드로이드판 ui/UpdateBanner.kt와 대칭). [Repository.checkForUpdateIfNeeded]가
 * 하루 1회 GitHub Releases를 확인해 남겨둔 설치파일(exe/msi) URL을 그냥 보여주기만 하며, 실제
 * 다운로드/실행은 여기서 처리한다 — 실행 중인 프로세스의 파일을 installer가 덮어써야 하므로, 설치파일을
 * 띄운 직후 이 앱 자신은 종료한다(사용자가 설치 마법사에서 마저 진행).
 */
@Composable
fun UpdateBanner(repository: Repository, installerUrl: String) {
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    // 85차: 다운로드/설치 실행이 실패해도 아무 피드백 없이 버튼만 다시 눌리게 돼있던 문제(사용자 제보
    // "업데이트 버튼을 눌러도 반응이 없다")를 고치기 위해, 실패 사유를 배너 안에 계속 보이게 남긴다
    // (데스크탑엔 안드로이드의 Toast 같은 표준 컴포넌트가 없어 인라인 텍스트로 대신함).
    var errorText by remember { mutableStateOf<String?>(null) }

    // 2026-08-30 발견(안드로이드판과 동일): Row + SpaceBetween에 Text를 weight 없이 넣으면 문구가 길 때
    // 옆 버튼이 밀려나 안 보일 수 있어 Column으로 바꿔 버튼이 항상 자기 줄에서 보이게 했다.
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                if (downloading) "업데이트 다운로드 중..." else "새 버전이 있습니다. 업데이트를 진행하세요",
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (!downloading && errorText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "업데이트 실패: $errorText — 잠시 후 다시 시도해주세요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(enabled = !downloading, modifier = Modifier.fillMaxWidth(), onClick = {
                downloading = true
                errorText = null
                scope.launch {
                    val failure = downloadAndRunInstaller(installerUrl)
                    if (failure == null) {
                        // 79차 버그 수정: 이 표식 없이 exitProcess(0)만 하면, 감시 프로세스(Watchdog)가
                        // 2초 안에 "죽었다"고 보고 옛 버전 exe를 즉시 다시 띄운다 — 설치 마법사가 파일을
                        // 덮어쓰기도 전에 옛 버전이 살아나 파일을 다시 잠그고, 그 옛 버전이 "새 버전 있음"
                        // 배너를 또 띄우면서 업데이트를 눌러도 영원히 반복되는 버그였다("트레이 종료"와
                        // 동일한 표식을 남겨 감시 프로세스가 되살리지 않게 한다 — Main.kt의 정식 종료
                        // 절차와 동일 패턴, 새 버전이 켜지면 시작 시점에 이 표식이 자동으로 지워진다).
                        runCatching { com.phonelock.desktop.intentionalExitFlagFile().createNewFile() }
                        repository.flushPendingUsage()
                        exitProcess(0)
                    }
                    errorText = failure
                    downloading = false
                }
            }) {
                Text("업데이트")
            }
        }
    }
}

/** 성공하면 null, 실패하면 원인을 짧게 담은 문자열(85차 — 예전엔 Boolean만 돌려줘서 실패해도 사용자에게
 *  아무 정보 없이 조용히 버튼만 다시 눌리는 상태가 됐다). 다운로드에 타임아웃도 없었어서(무기한 대기)
 *  네트워크가 멈추면 "누른 채로 아무 반응 없음"처럼 보였을 수 있어 연결/읽기 타임아웃을 명시했다. */
private suspend fun downloadAndRunInstaller(installerUrl: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val extension = installerUrl.substringAfterLast('.', "exe")
        val dest = File(System.getProperty("java.io.tmpdir"), "PhoneLockDesktopUpdate.$extension")
        val conn = URI.create(installerUrl).toURL().openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        conn.getInputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        ProcessBuilder(dest.absolutePath).start()
        null
    }.getOrElse { it.message ?: it.javaClass.simpleName }
}
