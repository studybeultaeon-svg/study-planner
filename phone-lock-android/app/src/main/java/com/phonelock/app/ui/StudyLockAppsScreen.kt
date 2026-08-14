package com.phonelock.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.ui.theme.Spacing

/**
 * 공부 잠금 중 예외로 허용할 앱을 설치된 앱 목록에서 고르는 화면. GroupEditScreen의 앱 선택 UI와
 * 같은 패턴(검색 + 체크박스 + 선택된 항목 위로 정렬)을 따른다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyLockAppsScreen() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var allowedSitesText by remember { mutableStateOf(prefs.studyLockAllowedSites.joinToString("\n")) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("공부 잠금 허용 앱") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
            Text(
                "선택한 앱은 공부앱 타이머가 켜져 있는 동안에도 항상 열 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = allowedSitesText,
                onValueChange = { text ->
                    allowedSitesText = text
                    prefs.studyLockAllowedSites = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                },
                label = { Text("허용 사이트 (한 줄에 하나씩, 예: google.com)") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "허용된 앱(브라우저)이 열려 있어도 여기 등록 안 된 사이트는 따로 차단됩니다. 이 기기에만 적용되며, " +
                    "데스크탑에는 데스크탑 앱의 타이머 탭에서 따로 등록해야 합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            AllowedAppsPickerBody(prefs = prefs, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 허용 앱 검색+체크박스 목록(설치된 앱 조회 → 검색 필터 → 선택된 항목 우선 정렬)만 뽑아낸 재사용
 * 조각. [StudyLockAppsScreen](전체화면)과 [com.phonelock.app.ui.StudyTimerScreen](타이머 탭 인라인
 * 접이식 섹션) 양쪽에서 쓴다 — 목록 렌더링 로직을 중복시키지 않기 위함.
 */
@Composable
fun AllowedAppsPickerBody(prefs: AppPreferences, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selectedPackages by remember { mutableStateOf(prefs.studyLockAllowedPackages) }
    var searchQuery by remember { mutableStateOf("") }

    val installedApps = remember { getLaunchableApps(context) }
    val filteredApps = remember(installedApps, searchQuery, selectedPackages) {
        val base = if (searchQuery.isBlank()) installedApps
        else installedApps.filter { it.label.contains(searchQuery, ignoreCase = true) }
        base.sortedByDescending { selectedPackages.contains(it.packageName) }
    }

    Column(modifier) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("앱 검색") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.xs))
        // 이 컴포넌트가 타이머 탭의 verticalScroll(Column) 안에서도 쓰이는데(AllowedAppsCollapsibleSection),
        // 그런 무한 높이 부모 안에서 LazyColumn에 weight()를 쓰면 세로 스크롤이 서로 부딪혀 목록이
        // 아예 렌더링되지 않는다(GroupEditScreen은 이 목록을 최상위 LazyColumn의 items()로 바로
        // 펼치는 방식이라 이 문제가 없었다). weight 없이 heightIn(max)만으로 고정 높이를 줘서
        // 어느 부모 안에서도 항상 뜨게 한다.
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(filteredApps, key = { it.packageName }) { app ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = selectedPackages.contains(app.packageName),
                        onCheckedChange = { checked ->
                            selectedPackages = if (checked) selectedPackages + app.packageName
                            else selectedPackages - app.packageName
                            prefs.studyLockAllowedPackages = selectedPackages
                        }
                    )
                    AppIcon(app.packageName, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(app.label)
                }
            }
        }
    }
}
