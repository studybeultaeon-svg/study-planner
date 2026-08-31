package com.phonelock.app.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "설정탭 초기화 시간이 지나면 그날 새 버전이 올라왔는지 확인해서 업데이트 문구를 띄워달라"는 요청으로
 * 신규 추가(2026-08-30). [com.phonelock.app.data.PhoneLockRepository.checkForUpdateIfNeeded]가 하루
 * 1회 GitHub Releases를 확인해 남겨둔 URL을 그냥 보여주기만 하며, 실제 다운로드/설치는 여기서
 * DownloadManager + 설치 인텐트로 처리한다("최종 설치 확인"만 사용자가 누르면 됨 — Android 정책상
 * 완전 무인 설치는 불가능).
 */
@Composable
fun UpdateBanner(apkUrl: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }

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
            if (downloading) "업데이트 다운로드 중..." else "새 버전이 있습니다. 업데이트를 진행하세요",
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(Modifier.height(8.dp))
        Button(enabled = !downloading, modifier = Modifier.fillMaxWidth(), onClick = {
            if (!context.packageManager.canRequestPackageInstalls()) {
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
            scope.launch {
                val ok = downloadAndInstallApk(context, apkUrl)
                downloading = false
                if (!ok) {
                    Toast.makeText(context, "업데이트 다운로드에 실패했습니다. 잠시 후 다시 시도해주세요", Toast.LENGTH_LONG).show()
                }
            }
        }) {
            Text("업데이트")
        }
    }
}

private suspend fun downloadAndInstallApk(context: Context, apkUrl: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("갓생살기종합세트 업데이트")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "update.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val id = dm.enqueue(request)

        var status = DownloadManager.STATUS_RUNNING
        var attempts = 0
        while (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
            if (attempts++ > 300) return@runCatching false // 최대 5분(1초 간격) 대기 후 포기
            delay(1000)
            dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (cursor.moveToFirst()) {
                    status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
            }
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL) return@runCatching false

        val uri = dm.getUriForDownloadedFile(id)
        withContext(Dispatchers.Main) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }
        true
    }.getOrDefault(false)
}
