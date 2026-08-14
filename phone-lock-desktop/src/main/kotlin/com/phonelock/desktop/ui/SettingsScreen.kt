package com.phonelock.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonelock.desktop.data.Repository
import com.phonelock.desktop.ui.components.SectionCard
import com.phonelock.desktop.ui.components.ToggleRow
import com.phonelock.desktop.ui.theme.Spacing
import java.io.File

/** 저장(내보내기) 파일 선택 창 — StatsScreen.exportUsageCsvToFile()과 동일한 java.awt.FileDialog 패턴. */
private fun pickSaveFile(title: String, defaultName: String): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    val fileName = if (name.endsWith(".json", ignoreCase = true)) name else "$name.json"
    return File(dir, fileName)
}

/** 열기(가져오기) 파일 선택 창. */
private fun pickOpenFile(title: String): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(dir, name)
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(repository: Repository, onThemeChange: (String) -> Unit = {}) {
    var themeMode by remember { mutableStateOf(repository.themeMode) }
    var dailyResetHourText by remember { mutableStateOf(repository.dailyResetHour.toString()) }
    var blockReels by remember { mutableStateOf(repository.blockReels) }
    var blockShorts by remember { mutableStateOf(repository.blockShorts) }
    var routineStreakNotifyEnabled by remember { mutableStateOf(repository.routineStreakNotifyEnabled) }
    var fbDatabaseUrlText by remember { mutableStateOf(repository.fbDatabaseUrl ?: "") }
    var fbApiKeyText by remember { mutableStateOf(repository.fbApiKey ?: "") }
    var fbUserText by remember { mutableStateOf(repository.fbUser) }
    var backups by remember { mutableStateOf(repository.listBackups()) }
    var pendingRestoreFile by remember { mutableStateOf<File?>(null) }
    var pendingImportFile by remember { mutableStateOf<File?>(null) }
    var pendingRoutineImportFile by remember { mutableStateOf<File?>(null) }

    pendingRestoreFile?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingRestoreFile = null },
            title = { Text("복원 확인") },
            text = { Text("복원하면 현재 그룹/기록이 ${file.name} 백업 내용으로 완전히 대체됩니다(되돌리기 없음). 계속할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    repository.restoreFromBackup(file)
                    pendingRestoreFile = null
                }) { Text("복원") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreFile = null }) { Text("취소") }
            }
        )
    }

    pendingImportFile?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingImportFile = null },
            title = { Text("가져오기 확인") },
            text = { Text("가져오면 현재 그룹/기록이 ${file.name} 파일 내용으로 완전히 대체됩니다(되돌리기 없음). 계속할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    repository.restoreFromBackup(file)
                    pendingImportFile = null
                }) { Text("가져오기") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportFile = null }) { Text("취소") }
            }
        )
    }

    pendingRoutineImportFile?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingRoutineImportFile = null },
            title = { Text("루틴 가져오기 확인") },
            text = { Text("가져오면 현재 루틴/체크 기록이 ${file.name} 파일 내용으로 완전히 대체됩니다(되돌리기 없음). 계속할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    repository.importRoutinesBackupJson(file.readText())
                    pendingRoutineImportFile = null
                }) { Text("가져오기") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRoutineImportFile = null }) { Text("취소") }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.md)
    ) {
        Text("설정", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(Spacing.md))

        SectionCard("테마") {
            Text(
                "앱 전체 배경/포인트 색과 차단/실행확인 화면 강조색, 브라우저 확장 색까지 함께 바뀝니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                com.phonelock.desktop.ui.theme.THEME_DISPLAY_NAMES.forEach { (mode, label) ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { themeMode = mode; repository.themeMode = mode; onThemeChange(mode) },
                        label = { Text(label) }
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.md))

        SectionCard("일일 사용 한도 초기화 시각") {
            OutlinedTextField(
                value = dailyResetHourText,
                onValueChange = { text ->
                    dailyResetHourText = text
                    text.toIntOrNull()?.let { if (it in 0..23) repository.dailyResetHour = it }
                },
                label = { Text("초기화 시각 (0~23시)") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "이 시각이 되면 그룹별 오늘 사용 시간이 초기화됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Spacing.md))

        SectionCard("릴스/쇼츠 차단") {
            ToggleRow(
                title = "릴스 차단 (인스타그램)",
                checked = blockReels,
                onCheckedChange = { checked ->
                    blockReels = checked
                    repository.blockReels = checked
                }
            )
            ToggleRow(
                title = "쇼츠 차단 (유튜브)",
                checked = blockShorts,
                onCheckedChange = { checked ->
                    blockShorts = checked
                    repository.blockShorts = checked
                }
            )
            Text(
                "브라우저 확장프로그램이 youtube.com/shorts, instagram.com/reels URL을 감지해서 차단합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Spacing.md))

        SectionCard("루틴 스트릭 알림") {
            ToggleRow(
                title = "스트릭 알림 받기",
                checked = routineStreakNotifyEnabled,
                onCheckedChange = { checked ->
                    routineStreakNotifyEnabled = checked
                    repository.routineStreakNotifyEnabled = checked
                }
            )
            Text(
                "매일 초기화 시각(위 \"일일 초기화\" 참고)에 어제 루틴 스트릭 상태를 트레이 알림으로 알려줍니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Spacing.md))

        SectionCard("공부앱 연동 / 모바일과 실행 확인 레벨 동기화") {
            Text(
                "공부앱(별도 웹앱)의 \"동기화 설정\"에 입력한 것과 동일한 Realtime Database URL / Web API Key / " +
                    "사용자 ID를 입력하세요. 이 설정은 두 가지에 쓰입니다 — ① 공부앱에서 뽀모도로 휴식이 시작될 때 " +
                    "\"뽀모도로 휴식 시 자동 해제\"를 켜둔 그룹만 휴식 시간 동안 임시로 잠금이 풀립니다. ② 모바일 앱과 " +
                    "동일한 값을 입력해두면 실행 확인 레벨이 Firebase를 통해 자동으로 동기화됩니다. 비워두면 두 기능 " +
                    "모두 꺼진 상태로 유지됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = fbDatabaseUrlText,
                onValueChange = { text ->
                    fbDatabaseUrlText = text
                    repository.fbDatabaseUrl = text.ifBlank { null }
                },
                label = { Text("Realtime Database URL") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = fbApiKeyText,
                onValueChange = { text ->
                    fbApiKeyText = text
                    repository.fbApiKey = text.ifBlank { null }
                },
                label = { Text("Web API Key") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.sm))
            OutlinedTextField(
                value = fbUserText,
                onValueChange = { text ->
                    fbUserText = text
                    repository.fbUser = text.ifBlank { "default" }
                },
                label = { Text("사용자 ID (공부앱과 동일하게, 비워두면 default)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(Spacing.md))

        SectionCard("일일 백업 / 복원") {
            Text(
                "앱 시작 시 하루 한 번 전체 데이터(그룹/사용시간/캘린더/계산기 등)를 자동 백업합니다. " +
                    "최근 7일치를 보관하며, 복원하면 현재 데이터가 완전히 대체됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            if (backups.isEmpty()) {
                Text("아직 백업이 없습니다(다음 앱 재시작 시 처음 만들어집니다).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                backups.forEach { file ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(file.name.removePrefix("backup_").removeSuffix(".json"), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { pendingRestoreFile = file }) { Text("이 시점으로 복원") }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Button(onClick = { backups = repository.listBackups() }) { Text("목록 새로고침") }
        }
        Spacer(Modifier.height(Spacing.md))

        SectionCard("설정/그룹 내보내기 · 가져오기") {
            Text(
                "기기 교체나 재설치 시 현재 데이터 전체(그룹/사용시간/캘린더/계산기 등)를 원하는 위치에 파일로 저장하거나, " +
                    "저장해둔 파일에서 그대로 불러올 수 있습니다. 위 자동 백업과 달리 파일 위치를 직접 고를 수 있어 다른 PC로 옮길 때 유용합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = {
                    pickSaveFile("설정/그룹 내보내기", "phonelock_export_${java.time.LocalDate.now()}.json")?.let { file ->
                        repository.exportDataToFile(file)
                    }
                }) { Text("📤 내보내기") }
                OutlinedButton(onClick = {
                    pickOpenFile("설정/그룹 가져오기")?.let { file -> pendingImportFile = file }
                }) { Text("📥 가져오기") }
            }
        }
        Spacer(Modifier.height(Spacing.md))

        SectionCard("루틴 내보내기 / 가져오기") {
            Text(
                "루틴 목록과 체크 기록만 파일로 저장하거나 불러옵니다. 루틴은 이미 Firebase로 기기 간 자동 " +
                    "동기화되지만, 위 전체 백업과 달리 루틴만 골라서 다른 계정으로 옮기거나 별도 보관할 때 씁니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = {
                    pickSaveFile("루틴 내보내기", "phonelock_routines_${java.time.LocalDate.now()}.json")?.let { file ->
                        file.writeText(repository.exportRoutinesBackupJson())
                    }
                }) { Text("📤 내보내기") }
                OutlinedButton(onClick = {
                    pickOpenFile("루틴 가져오기")?.let { file -> pendingRoutineImportFile = file }
                }) { Text("📥 가져오기") }
            }
        }
        Spacer(Modifier.height(Spacing.md))

        SectionCard("오래된 통계 데이터 정리") {
            var lastResult by remember { mutableStateOf<Int?>(null) }
            Text(
                "12개월 이상 지난 사용시간/재확인 통과 횟수/공부 기록을 영구 삭제합니다(되돌리기 없음). " +
                    "캘린더 일정과 스트릭 계산에는 영향을 주지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            Button(onClick = { lastResult = repository.pruneOldStats(12) }) { Text("🧹 12개월 이상 지난 기록 정리") }
            lastResult?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text("$it 건 삭제됨", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(Spacing.md))

        SectionCard("종료 방지") {
            Text(
                "감시 프로세스와 작업 스케줄러가 함께 지켜보다가, 작업 관리자로 강제종료해도 자동으로 다시 실행됩니다. " +
                    "트레이 메뉴의 \"종료\"로 10분 대기를 마치고 정식으로 나가야만 꺼진 상태가 유지됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
