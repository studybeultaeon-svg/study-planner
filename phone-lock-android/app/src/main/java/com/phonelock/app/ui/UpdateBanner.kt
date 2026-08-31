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
    var progressPercent by remember { mutableStateOf(-1) }

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
            progressPercent = -1
            scope.launch {
                val result = downloadAndInstallApk(context, apkUrl) { percent -> progressPercent = percent }
                downloading = false
                if (result != null) {
                    Toast.makeText(context, "업데이트 다운로드에 실패했습니다($result). 데이터 절약 모드가 켜져 있으면 꺼보거나, 잠시 후 다시 시도해주세요", Toast.LENGTH_LONG).show()
                }
            }
        }) {
            Text("업데이트")
        }
    }
}

/** 성공하면 null, 실패하면 원인을 짧게 담은 문자열을 반환한다(예전엔 Boolean만 돌려줘서 "왜" 실패했는지
 *  알 방법이 없었다 — 다운로드가 "다운로드 중"에서 멈춘 채 아무 반응이 없다는 제보를 받고 진단용으로 보강). */
private suspend fun downloadAndInstallApk(
    context: Context,
    apkUrl: String,
    onProgress: (Int) -> Unit
): String? = withContext(Dispatchers.IO) {
    runCatching {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("갓생살기종합세트 업데이트")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "update.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // 데이터 절약 모드/모바일 데이터 제한 때문에 다운로드가 PAUSED 상태로 무기한 멈춰있던 게
            // "다운로드 중"만 뜨고 아무 일도 안 일어나는 것처럼 보인 원인 중 하나로 의심돼(2026-09-01
            // 사용자 제보) 명시적으로 허용한다.
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val id = dm.enqueue(request)

        var status = DownloadManager.STATUS_PENDING
        var attempts = 0
        while (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED) {
            if (attempts++ > 300) return@runCatching "5분 초과" // 최대 5분(1초 간격) 대기 후 포기
            delay(1000)
            dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (cursor.moveToFirst()) {
                    status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            }
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            val reason = dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)) else -1
            }
            return@runCatching "상태 $status, 사유 $reason"
        }

        val uri = dm.getUriForDownloadedFile(id)
        withContext(Dispatchers.Main) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }
        null
    }.getOrElse { it.message ?: "알 수 없는 오류" }
}
