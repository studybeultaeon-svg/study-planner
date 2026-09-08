package com.phonelock.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "새 버전이 올라오면 업데이트 문구를 띄워달라"는 요청으로 신규 추가(2026-08-30).
 * [com.phonelock.app.data.PhoneLockRepository.checkForUpdateIfNeeded]가 짧은 주기로 GitHub Releases를
 * 확인해(2026-09-05: 하루 1회 → updateCheckIntervalMs 주기로 변경, 새 빌드가 다음 날 초기화까지 안
 * 기다리고 곧바로 뜨도록) 남겨둔 URL을 그냥 보여주기만 하며, 실제 다운로드/설치는 여기서
 * DownloadManager + 설치 인텐트로 처리한다("최종 설치 확인"만 사용자가 누르면 됨 — Android 정책상
 * 완전 무인 설치는 불가능).
 */
@Composable
fun UpdateBanner(apkUrl: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progressPercent by remember { mutableStateOf(-1) }
    // 85차: 다운로드/설치 인텐트 실패가 Toast(짧게 사라짐)로만 뜨고 있어 놓치기 쉬웠다는 제보 —
    // 배너 안에 계속 남는 텍스트도 함께 보여준다.
    var errorText by remember { mutableStateOf<String?>(null) }
    // 82차: GitHub Release body를 그대로 "이번 업데이트 내용"으로 보여준다(신규 API 호출 없음, 이미
    // 업데이트 확인 시점에 함께 받아 AppPreferences에 저장해둔 값을 읽기만 한다).
    // 91차(90차 지정 7번, 사용자 제보): 릴리스마다 `gh release create --notes`를 세션별로 영어/한글
    // 섞어서 채워온 탓에 영어 문구가 그대로 뜨는 경우가 있었다 — 과거 릴리스 본문 자체를 고칠 방법은
    // 없으므로, 한글(한글 음절)이 하나도 없는 값은 사용자에게 보여줄 만한 내용이 아니라고 보고 숨긴다.
    val releaseNotes = remember { com.phonelock.app.data.AppPreferences(context).updateAvailableReleaseNotes }
        .let { notes -> if (notes.any { it in '가'..'힣' }) notes else "" }

    // 2026-08-30 발견: Row + SpaceBetween에 Text를 weight 없이 넣으면 문구가 길 때 Text가 Row 폭을
    // 거의 다 차지해버려서 옆에 있던 버튼이 화면 밖으로 밀려나 안 보이는 문제가 있었다 — 문구가 항상
    // 자기 줄을 다 쓰고 버튼은 그 아래 새 줄에 오도록 Column으로 바꿔서 버튼이 항상 보이게 고쳤다.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            when {
                !downloading -> "새 버전이 있습니다. 업데이트를 진행하세요"
                progressPercent in 0..100 -> "업데이트 다운로드 중... $progressPercent%"
                else -> "업데이트 다운로드 중..."
            },
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        if (!downloading && releaseNotes.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                releaseNotes.trim().take(300),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        if (!downloading && errorText != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "업데이트 실패: $errorText — 데이터 절약 모드가 켜져 있으면 꺼보거나, 잠시 후 다시 시도해주세요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(enabled = !downloading, modifier = Modifier.fillMaxWidth(), onClick = {
            if (!context.packageManager.canRequestPackageInstalls()) {
                errorText = "설치 권한 없음"
                Toast.makeText(
                    context,
                    "설정에서 \"출처를 알 수 없는 앱 설치\"를 이 앱에 허용해주세요",
                    Toast.LENGTH_LONG
                ).show()
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    )
                )
                return@Button
            }
            downloading = true
            progressPercent = -1
            errorText = null
            scope.launch {
                val result = downloadAndInstallApk(context, apkUrl) { percent -> progressPercent = percent }
                downloading = false
                errorText = result
                if (result != null) {
                    Toast.makeText(context, "업데이트 다운로드에 실패했습니다($result). 데이터 절약 모드가 켜져 있으면 꺼보거나, 잠시 후 다시 시도해주세요", Toast.LENGTH_LONG).show()
                }
            }
        }) {
            Text("업데이트")
        }
    }
}

/**
 * 성공하면 null, 실패하면 원인을 짧게 담은 문자열을 반환한다(예전엔 Boolean만 돌려줘서 "왜" 실패했는지
 * 알 방법이 없었다 — 다운로드가 "다운로드 중"에서 멈춘 채 아무 반응이 없다는 제보를 받고 진단용으로 보강).
 *
 * **95차**: 시스템 `DownloadManager`에 맡기던 걸(81차에 PAUSED 처리/메터드 네트워크 허용 등을 계속
 * 보강했지만 "여전히 다운로드가 멈춘다"는 제보가 반복돼) 앱 자기 프로세스 안에서 직접 HTTP로 내려받는
 * 방식으로 교체했다 — DownloadManager는 별도 시스템 서비스라 일부 OEM(제조사) ROM의 배터리 최적화/
 * 백그라운드 서비스 제한에 걸리면 이 앱이 전혀 손댈 수 없는 지점에서 조용히 멈출 수 있는데, 직접 다운로드는
 * 이미 포그라운드에서 실행 중인 이 코루틴 안에서 끝나므로 그런 외부 제약을 받지 않는다. 저장 위치가
 * DownloadManager의 공개 다운로드 폴더에서 앱 전용 폴더로 바뀌어 다른 앱(설치 화면)에 파일을 보여주려면
 * `FileProvider`(매니페스트에 신규 등록, `update_file_paths.xml`)를 거쳐야 한다.
 */
private suspend fun downloadAndInstallApk(
    context: Context,
    apkUrl: String,
    onProgress: (Int) -> Unit
): String? = withContext(Dispatchers.IO) {
    runCatching {
        val destDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return@runCatching "저장 폴더를 찾을 수 없음"
        val destFile = java.io.File(destDir, "update.apk")

        val connection = (java.net.URL(apkUrl).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        connection.connect()
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            return@runCatching "HTTP $code"
        }
        val totalBytes = connection.contentLengthLong
        var downloadedBytes = 0L
        connection.inputStream.use { input ->
            java.io.FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    if (totalBytes > 0) onProgress((downloadedBytes * 100 / totalBytes).toInt())
                }
            }
        }
        connection.disconnect()
        if (totalBytes > 0 && downloadedBytes < totalBytes) {
            return@runCatching "다운로드 중단됨(${downloadedBytes}/${totalBytes} 바이트)"
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 85차 발견: 이 인텐트를 처리할 앱이 없으면(일부 커스텀 롬/관리형 기기) startActivity가 예외 없이
        // 그냥 아무 일도 안 일어난 것처럼 보인다("설치 창이 안 뜬다"는 제보) — resolveActivity로 미리
        // 확인해 실패를 명시적인 오류로 보고한다. **95차**: 매니페스트 <queries>에 이 VIEW+APK mimeType
        // 조합을 명시적으로 추가하지 않으면 API 30+ 패키지 가시성 제한 때문에 설치 프로그램이 실제로
        // 있어도 resolveActivity가 null을 돌려줄 수 있었다 — AndroidManifest.xml도 함께 수정.
        if (installIntent.resolveActivity(context.packageManager) == null) {
            return@runCatching "설치 프로그램을 열 수 없음"
        }
        withContext(Dispatchers.Main) {
            context.startActivity(installIntent)
        }
        null
    }.getOrElse { it.message ?: "알 수 없는 오류" }
}
