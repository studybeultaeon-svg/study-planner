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

    // 2026-08-30 발견(안드로이드판과 동일): Row + SpaceBetween에 Text를 weight 없이 넣으면 문구가 길 때
    // 옆 버튼이 밀려나 안 보일 수 있어 Column으로 바꿔 버튼이 항상 자기 줄에서 보이게 했다.
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                if (downloading) "업데이트 다운로드 중..." else "새 버전이 있습니다. 업데이트를 진행하세요",
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(8.dp))
            Button(enabled = !downloading, modifier = Modifier.fillMaxWidth(), onClick = {
                downloading = true
                scope.launch {
                    val ok = downloadAndRunInstaller(installerUrl)
                    if (ok) {
                        repository.flushPendingUsage()
                        exitProcess(0)
                    }
                    downloading = false
                }
            }) {
                Text("업데이트")
            }
        }
    }
}

private suspend fun downloadAndRunInstaller(installerUrl: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val extension = installerUrl.substringAfterLast('.', "exe")
        val dest = File(System.getProperty("java.io.tmpdir"), "PhoneLockDesktopUpdate.$extension")
        URI.create(installerUrl).toURL().openStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        ProcessBuilder(dest.absolutePath).start()
        true
    }.getOrDefault(false)
}
