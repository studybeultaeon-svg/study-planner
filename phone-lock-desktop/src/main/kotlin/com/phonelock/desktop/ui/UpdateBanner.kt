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
 * 주기적으로 GitHub Releases를 확인해 남겨둔 배포물 URL을 그냥 보여주기만 하며, 실제 다운로드/교체는
 * 여기서 처리한다 — 실행 중인 프로세스가 자기 파일을 잠그고 있으므로 어느 경로든 이 앱 자신은
 * 종료한다. 135차부터 기본 경로는 app-image zip을 받아 설치 폴더를 통째로 바꿔치기하는 방식
 * ([applyAppImageUpdate])이고, exe/msi 설치파일 실행은 zip이 없던 옛 릴리스용 폴백으로만 남는다.
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
 *  네트워크가 멈추면 "누른 채로 아무 반응 없음"처럼 보였을 수 있어 연결/읽기 타임아웃을 명시했다.
 *
 *  135차: 받은 게 app-image zip이면 [applyAppImageUpdate]로 설치 폴더를 통째로 바꿔치기한다(지금 이
 *  호스트가 실제로 쓰는 설치 형태). exe/msi는 zip이 없던 옛 릴리스를 위한 폴백으로만 남는다. */
private suspend fun downloadAndRunInstaller(installerUrl: String): String? = withContext(Dispatchers.IO) {
    if (installerUrl.substringBefore('?').endsWith(".zip", ignoreCase = true)) {
        return@withContext applyAppImageUpdate(installerUrl)
    }
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

/**
 * app-image zip을 받아 지금 실행 중인 설치 폴더를 통째로 새 버전으로 바꾼다(135차).
 *
 * 실행 중인 앱은 자기 파일(exe·jar·runtime DLL)을 잠그고 있고 감시 프로세스(`--watchdog`)도 같은 폴더의
 * 같은 exe로 떠 있어서, 이 앱 안에서는 폴더를 덮어쓸 수 없다. 그래서 zip을 임시 폴더에 풀어두고, 교체는
 * 외부 스크립트(cmd)에 맡긴 뒤 이 프로세스는 빠진다:
 *   1) 이 앱이 정리하고 나갈 시간을 잠깐 준다 → 2) 남은 PhoneLockDesktop.exe(감시 프로세스 포함)를 정리
 *   → 3) robocopy로 설치 폴더를 새 내용으로 맞춘다 → 4) 새 exe를 다시 띄운다.
 * 호출부가 [com.phonelock.desktop.intentionalExitFlagFile]을 먼저 만들어 두므로 그 사이에 감시 프로세스나
 * 작업 스케줄러가 옛 버전을 되살리지 않고, 새로 뜬 앱이 시작하면서 그 표식을 스스로 지운다.
 * 교체가 실패해도 마지막에 exe를 다시 띄우므로 옛 버전으로 되돌아올 뿐이다(앱이 사라지지는 않는다).
 */
private fun applyAppImageUpdate(zipUrl: String): String? = runCatching {
    val appDir = com.phonelock.desktop.currentAppImageDir() ?: return@runCatching "설치 폴더를 찾을 수 없음"
    val exePath = com.phonelock.desktop.currentLauncherExePath() ?: return@runCatching "실행 파일 경로를 찾을 수 없음"

    val workDir = File(System.getProperty("java.io.tmpdir"), "PhoneLockDesktopUpdate")
    workDir.deleteRecursively()
    workDir.mkdirs()
    val zipFile = File(workDir, "app-image.zip")
    val newDir = File(workDir, "new")

    val conn = URI.create(zipUrl).toURL().openConnection().apply {
        connectTimeout = 15_000
        readTimeout = 60_000
    }
    conn.getInputStream().use { input ->
        zipFile.outputStream().use { output -> input.copyTo(output) }
    }
    unzipInto(zipFile, newDir)

    // 압축이 덜 풀렸는데 robocopy로 덮어쓰면 설치 폴더가 망가진다 — 런처 exe가 있는지부터 확인한다.
    val exeName = File(exePath).name
    if (!File(newDir, exeName).isFile) return@runCatching "내려받은 파일이 올바른 설치 이미지가 아님"

    val script = File(workDir, "apply-update.cmd")
    script.writeText(
        """
        |@echo off
        |ping -n 4 127.0.0.1 >nul
        |taskkill /F /IM "$exeName" >nul 2>&1
        |ping -n 3 127.0.0.1 >nul
        |robocopy "${newDir.absolutePath}" "${appDir.absolutePath}" /MIR /R:5 /W:1 >"${File(workDir, "update.log").absolutePath}" 2>&1
        |start "" "$exePath"
        |""".trimMargin(),
        Charsets.US_ASCII
    )
    ProcessBuilder("cmd.exe", "/c", script.absolutePath).start()
    null
}.getOrElse { it.message ?: it.javaClass.simpleName }

/** zip 안의 경로가 대상 폴더 밖(`..`)을 가리키면 건너뛴다(zip slip 방지) — 내려받은 파일이라 믿지 않는다. */
internal fun unzipInto(zipFile: File, targetDir: File) {
    targetDir.mkdirs()
    val targetPath = targetDir.canonicalFile.toPath()
    java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val out = File(targetDir, entry.name)
            if (!out.canonicalFile.toPath().startsWith(targetPath)) continue
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile?.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
            }
        }
    }
}
