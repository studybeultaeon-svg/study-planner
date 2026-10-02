package com.phonelock.app.ui.launcher

import com.phonelock.app.ui.components.LedgerAlertDialog
import com.phonelock.app.ui.components.ProgressLine
import com.phonelock.app.ui.components.Overline
import com.phonelock.app.ui.components.Hairline
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.Icons
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.enforcedLockTimer
import com.phonelock.app.data.getAllCalendarTasksOnce
import com.phonelock.app.data.getGrowthExpTotal
import com.phonelock.app.data.getRoutineLogsForDate
import com.phonelock.app.data.getRoutines
import com.phonelock.app.routine.RoutineEngine
import com.phonelock.app.service.AuthManager
import com.phonelock.app.service.EssentialApps
import com.phonelock.app.service.LockEvaluator
import com.phonelock.app.service.LockReason
import com.phonelock.app.ui.AppInfo
import com.phonelock.app.ui.MainActivity
import com.phonelock.app.ui.getLaunchableApps
import com.phonelock.app.ui.theme.Spacing
import com.phonelock.shared.GrowthSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 미니멀 런처 화면(130차, 132차 재설계). 홈과 앱 서랍 두 장뿐이고 앱 아이콘은 그리지 않는다.
 *
 * **132차 디자인 방향(사용자 요청)**: "minimalist launcher는 *모티브일 뿐* 미니멀할 필요는 없다.
 * 갓생살기종합세트의 디자인 컨셉을 그대로 따라가라." 그래서 131차의 맨 텍스트 화면(88sp Light 시계 +
 * 자간을 넓힌 작은 라벨 + 선만 두른 칩)을 걷어내고 **앱과 같은 디자인 언어**로 다시 세웠다:
 *  - 둥근 카드([LauncherCard] = 앱 홈 `PlantScreen.HomeCard`와 같은 20dp 반경 + 포인트색 22% 테두리)
 *  - 포인트색 알약 머리말([LauncherPill] = 앱 `SectionCard`의 "이모지 + 제목" 알약)
 *  - 아이콘 대신 이모지(탭 이모지는 `MainActivity`의 `Tab`과 같은 글자), 경험치바, 이모지 한 줄 정보
 *  - 굵기·자간을 직접 지정하지 않고 [MaterialTheme.typography] 스케일만 쓴다 — 앱 폰트(A2z)는 W600 한
 *    벌짜리라 다른 굵기를 요청하면 합성 굵기가 나오고, 한글에 자간을 넓히면 글자가 뚝뚝 떨어져 보인다
 *    (131차 화면이 "예쁘지 않다"고 지적받은 직접적인 원인, [com.phonelock.app.ui.theme.AppFontFamily] 참고).
 *
 * 홈에는 갓생살기종합세트의 현재 상태(레벨·칭호·경험치, 오늘 루틴, 공부 시간, 다음 일정)를 카드로 얹고,
 * 그 아래 **앱 하단 탭 5개(홈/루틴/공부/규칙/모임)** 바로가기를 둔다(서브탭까지 펼치지 않는다 — 132차 요청).
 * 미니멀 모드에서는 앱 다른 화면과 같은 기준으로 이모지만 뺀다(`MainActivity`의 서브탭 처리와 동일).
 *
 * 화면이 "절대 안 뜨는" 상황을 만들지 않는 게 최우선이라(홈 버튼이 안 먹는 폰이 된다) 설정 읽기,
 * 앱 목록 조회, 요약 조회를 전부 runCatching으로 감쌌다. 전부 실패해도 최소한 시계와 빈 목록의 홈은 뜬다.
 */
@Composable
fun LauncherRoot(showDrawer: MutableState<Boolean>, refreshTick: Int) {
    val context = LocalContext.current
    val preferences = remember { runCatching { AppPreferences(context) }.getOrNull() }

    var favorites by remember { mutableStateOf(preferences?.launcherFavorites ?: emptyList()) }
    var hidden by remember { mutableStateOf(preferences?.launcherHiddenPackages ?: emptySet()) }
    var renames by remember { mutableStateOf(preferences?.launcherRenames() ?: emptyMap()) }

    // PackageManager 조회는 앱이 많으면 수백 ms가 걸린다 — 첫 프레임을 막지 않도록 IO에서 읽는다.
    val installed by produceState(initialValue = emptyList<AppInfo>(), refreshTick) {
        value = withContext(Dispatchers.IO) {
            runCatching { getLaunchableApps(context) }.getOrElse { emptyList() }
        }
    }
    val status by produceState(initialValue = LauncherStatus.EMPTY, refreshTick) {
        value = loadGodsaengStatus(context)
    }
    // 미니멀 모드에서는 앱의 다른 화면과 같은 기준으로 이모지를 뺀다(색은 MonoPalette가 이미 흑백으로 준다).
    // 133차: 테마와 함께 화면에 돌아올 때마다 다시 읽는다 — 앱에서 켜고 끈 미니멀 모드가 홈에만 늦게
    // 반영되면 색은 흑백인데 이모지는 남는 식으로 어긋난다([LauncherActivity]의 테마 재적용과 같은 이유).
    val minimalMode = remember(refreshTick) { preferences?.minimalMode == true }
    // 홈에 고정한 디데이(133차, 사용자 요청) — 후보는 여럿이어도 홈에 뜨는 건 고른 하나뿐이다.
    val pinnedDday = remember(refreshTick) {
        runCatching {
            pinnedLauncherDday(
                parseLauncherDdays(preferences?.launcherDdaysText.orEmpty()),
                preferences?.launcherPinnedDdayId
            )
        }.getOrNull()
    }
    // 승인 범위 밖 기능은 바로가기도 보여주지 않는다(MainActivity.visibleTabs와 같은 기준).
    val shortcuts = remember(preferences) {
        val social = preferences?.permSocial == true &&
            runCatching { AuthManager.currentUser?.isAnonymous != true }.getOrDefault(true)
        godsaengShortcuts(
            routine = preferences?.permRoutine == true,
            study = preferences?.permStudy == true,
            manage = preferences?.permManage == true,
            social = social
        )
    }
    // 차단 시간대는 화면을 켜둔 채로도 시작·종료되므로 주기적으로 다시 판정한다(화면에 머무는 동안만 도는 루프).
    val launcherLock by produceState(initialValue = LauncherLock.NONE, refreshTick) {
        while (true) {
            value = loadLauncherLock(context)
            delay(LOCK_REFRESH_INTERVAL_MS)
        }
    }

    val visibleApps = remember(installed, hidden, renames, launcherLock) {
        visibleLauncherApps(installed, hidden, renames, launcherLock.locked, launcherLock.allowOnly)
    }
    val lockedCount = remember(installed, hidden, launcherLock) {
        lockedAppCount(installed, hidden, launcherLock.locked, launcherLock.allowOnly)
    }

    // 홈에서 뒤로가기는 아무 일도 하지 않고(런처의 기본 동작), 앱 서랍에서는 홈으로 돌아간다.
    BackHandler(enabled = true) { if (showDrawer.value) showDrawer.value = false }

    if (showDrawer.value) {
        LauncherAppDrawer(
            apps = visibleApps,
            favorites = favorites,
            lockedCount = lockedCount,
            minimalMode = minimalMode,
            onLaunch = { launchApp(context, it) },
            onToggleFavorite = { packageName ->
                val next = toggleFavorite(favorites, packageName)
                if (next.size == favorites.size && packageName !in favorites) {
                    Toast.makeText(
                        context,
                        "즐겨찾기는 최대 ${LAUNCHER_FAVORITE_MAX}개까지입니다",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    favorites = next
                    preferences?.launcherFavorites = next
                }
            },
            onRename = { packageName, newName ->
                preferences?.setLauncherRename(packageName, newName)
                renames = preferences?.launcherRenames() ?: renames
            },
            onHide = { packageName ->
                val next = hidden + packageName
                hidden = next
                preferences?.launcherHiddenPackages = next
                Toast.makeText(context, "설정 > 화면 > 미니멀 런처에서 되돌릴 수 있습니다", Toast.LENGTH_SHORT).show()
            },
            onOpenMainApp = { openMainApp(context) }
        )
    } else {
        LauncherHome(
            favorites = remember(visibleApps, favorites) { favoriteApps(visibleApps, favorites) },
            status = status,
            pinnedDday = pinnedDday,
            shortcuts = shortcuts,
            lockedCount = lockedCount,
            minimalMode = minimalMode,
            onLaunch = { launchApp(context, it) },
            onOpenShortcut = { openShortcut(context, it) },
            onOpenDrawer = { showDrawer.value = true }
        )
    }
}

/**
 * 런처 홈. 위에서부터 시계 → 갓생 카드(오늘 현황) → 5탭 바로가기 → 즐겨찾기 카드 순이고,
 * "모든 앱"만 화면 맨 아래에 고정된다(목록이 길어져도 서랍 진입점이 늘 같은 자리에 있어야 한다).
 */
@Composable
private fun LauncherHome(
    favorites: List<AppInfo>,
    status: LauncherStatus,
    pinnedDday: LauncherDday?,
    shortcuts: List<GodsaengShortcut>,
    lockedCount: Int,
    minimalMode: Boolean,
    onLaunch: (String) -> Unit,
    onOpenShortcut: (GodsaengShortcut) -> Unit,
    onOpenDrawer: () -> Unit
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        // 분 단위 표시라 1초마다 깨울 이유가 없다 — 다음 분이 시작될 때까지만 잔다.
        while (true) {
            now = LocalDateTime.now()
            delay(1000L * (60 - now.second).coerceAtLeast(1))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LAUNCHER_EDGE)
        ) {
            Spacer(Modifier.height(Spacing.xl))
            // 144차: 화면의 주인공은 시계 — 아주 크게, 날짜·디데이는 알약 대신 그 아래 글자 두 줄.
            Text(
                now.format(TIME_FORMAT),
                style = MaterialTheme.typography.displayLarge,
                fontSize = 96.sp,
                lineHeight = 96.sp,
                letterSpacing = (-4).sp,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                softWrap = false
            )
            Text(now.format(DATE_FORMAT), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 디데이는 날짜 바로 아래에 붙여 "오늘이 언제인가" 한 덩어리로 읽히게 한다. 문구는 화면이 들고 있는 시계(now)로
            // 계산해서 자정을 넘겨도 하루 밀리지 않는다.
            pinnedDday?.let { dday ->
                launcherDdayText(dday, now.toLocalDate())?.let { text ->
                    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.height(Spacing.xl))
            GodsaengCard(status, minimalMode)

            if (shortcuts.isNotEmpty()) {
                Hairline()
                // 앱 하단 탭과 같은 5칸·같은 아이콘 — 앱을 열었을 때 만나는 탭 바와 자리를 같이 외울 수 있다.
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                    shortcuts.forEach { shortcut ->
                        ShortcutTile(shortcut, minimalMode, Modifier.weight(1f)) { onOpenShortcut(shortcut) }
                    }
                }
                Hairline()
            }

            Spacer(Modifier.height(Spacing.lg))
            FavoritesCard(favorites, minimalMode, onLaunch)
            Spacer(Modifier.height(Spacing.md))
        }

        LauncherBottomBar(
            label = "모든 앱",
            emoji = null,
            lockedCount = lockedCount,
            onClick = onOpenDrawer
        )
    }
}

/**
 * 갓생 묶음 — 144차: 카드 대신 편집형 묶음(작은 라벨 → 레벨·칭호 → 진행 막대 → 라벨-값 줄). 앱 홈의 레벨 히어로와
 * 같은 정보 순서라 런처가 앱의 연장선처럼 읽힌다.
 *
 * **133차(사용자 요청): 눌러도 앱이 열리지 않는다** — 바로 아래 바로가기에 "홈"이 이미 있다. 앱 홈 탭에서 뺀 오늘
 * 현황(루틴/집중/일정)을 이 묶음이 넘겨받았다(홈 버튼마다 지나가는 화면이라 "지금 뭘 해야 하는지"가 여기 있어야 한다).
 * 움직이는 식물 씬은 여전히 옮겨오지 않는다 — 홈 버튼마다 애니메이션이 도는 건 이 화면이 없애려던 자극이다(131차).
 */
@Composable
private fun GodsaengCard(status: LauncherStatus, @Suppress("UNUSED_PARAMETER") minimalMode: Boolean) {
    Column(Modifier.fillMaxWidth().padding(bottom = Spacing.md)) {
        Overline("갓생")
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                status.level ?: "앱 열기",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            status.stageTitle?.let { title ->
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
        if (status.level != null) {
            Spacer(Modifier.height(Spacing.sm))
            ProgressLine(status.progress)
        }
        Spacer(Modifier.height(Spacing.xs))
        status.lines.forEach { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                // 줄의 종류(루틴/집중/일정)는 이모지 대신 고정폭 라벨로 — 기기마다 이모지 기준선이 달라 줄이 흔들렸다.
                Text(
                    when (line.emoji) { "✅" -> "루틴"; "⏱️" -> "집중"; "📅" -> "일정"; else -> "" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(44.dp)
                )
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 바로가기 한 칸 — 앱 하단 탭과 같은 외곽선 아이콘 + 탭 이름. 다섯 칸이 같은 폭으로 나뉜다. */
@Composable
private fun ShortcutTile(
    shortcut: GodsaengShortcut,
    @Suppress("UNUSED_PARAMETER") minimalMode: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val icon = when (shortcut.route) {
        MainActivity.ROUTE_ROUTINE -> Icons.Outlined.TaskAlt
        MainActivity.ROUTE_STUDY -> Icons.Outlined.Timer
        MainActivity.ROUTE_MANAGE -> Icons.Outlined.Shield
        MainActivity.ROUTE_GROUP -> Icons.Outlined.Groups
        else -> Icons.Outlined.StarOutline
    }
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable { onClick() }.padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(shortcut.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, softWrap = false)
    }
}

/** 즐겨찾기 — 아이콘은 여전히 안 그리고(자극 줄이기), 이름을 두 칸 격자로 크게. */
@Composable
private fun FavoritesCard(favorites: List<AppInfo>, @Suppress("UNUSED_PARAMETER") minimalMode: Boolean, onLaunch: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Overline("즐겨찾기")
        Spacer(Modifier.height(Spacing.sm))
        if (favorites.isEmpty()) {
            Text(
                "아래 \"모든 앱\"에서 앱을 길게 눌러 추가하세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // 상한이 6개라 최대 세 줄이고, 홀수로 끝나면 마지막 칸은 빈 자리로 남겨 폭을 맞춘다.
            favorites.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    row.forEach { app ->
                        FavoriteTile(app.label, Modifier.weight(1f)) { onLaunch(app.packageName) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FavoriteTile(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = 16.dp)
        )
    }
}

/**
 * 홈/앱 서랍 맨 아래에 고정되는 이동 줄(홈에서는 "모든 앱", 서랍에서는 "갓생살기종합세트").
 * 두 화면이 같은 모양이라 어느 쪽에 있든 맨 아랫줄이 "반대쪽으로 가는 자리"로 읽힌다. 144차: 알약 → 가는 선 + 글자 + 화살표.
 */
@Composable
private fun LauncherBottomBar(label: String, @Suppress("UNUSED_PARAMETER") emoji: String?, lockedCount: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = LAUNCHER_EDGE, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.weight(1f))
            if (lockedCount > 0) {
                Text(
                    LOCKED_COUNT_LABEL.format(lockedCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LauncherAppDrawer(
    apps: List<AppInfo>,
    favorites: List<String>,
    lockedCount: Int,
    minimalMode: Boolean,
    onLaunch: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRename: (String, String?) -> Unit,
    onHide: (String) -> Unit,
    onOpenMainApp: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var menuTarget by remember { mutableStateOf<AppInfo?>(null) }
    var renameTarget by remember { mutableStateOf<AppInfo?>(null) }

    val filtered = remember(apps, query) { searchLauncherApps(apps, query) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = LAUNCHER_EDGE)) {
            Spacer(Modifier.height(Spacing.xl))
            Text("모든 앱", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(Spacing.md))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("검색") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = LAUNCHER_EDGE)) {
            items(filtered, key = { it.packageName }) { app ->
                AppRow(
                    label = app.label,
                    onClick = { onLaunch(app.packageName) },
                    onLongClick = { menuTarget = app }
                )
            }
        }
        LauncherBottomBar(
            label = "갓생살기종합세트",
            emoji = null,
            lockedCount = lockedCount,
            onClick = onOpenMainApp
        )
    }

    menuTarget?.let { app ->
        val isFavorite = app.packageName in favorites
        LedgerAlertDialog(
            onDismissRequest = { menuTarget = null },
            title = { Text(app.label) },
            text = {
                Column {
                    MenuRow(if (isFavorite) "즐겨찾기에서 빼기" else "즐겨찾기에 추가") {
                        onToggleFavorite(app.packageName); menuTarget = null
                    }
                    MenuRow("이름 바꾸기") { renameTarget = app; menuTarget = null }
                    MenuRow("목록에서 숨기기") { onHide(app.packageName); menuTarget = null }
                    MenuRow("앱 정보") { openAppInfo(context, app.packageName); menuTarget = null }
                }
            },
            confirmButton = { TextButton(onClick = { menuTarget = null }) { Text("닫기") } }
        )
    }

    renameTarget?.let { app ->
        var text by remember(app.packageName) { mutableStateOf(app.label) }
        LedgerAlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("이름 바꾸기") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "비우면 원래 이름으로. 이 런처 안에서만 바뀝니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onRename(app.packageName, text); renameTarget = null }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("취소") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(label: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.titleLarge,
        fontSize = 19.sp,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 14.dp)
    )
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.sm)
    )
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm", Locale.KOREAN)
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)

/** 좌우 여백 — 카드가 화면 폭을 거의 다 쓰되 가장자리에 붙지 않을 만큼(132차, 카드 기반 재설계). */
private val LAUNCHER_EDGE = 20.dp

/** 차단 시간대는 화면을 보고 있는 도중에도 시작·종료되므로 이 주기로 다시 판정한다. */
private const val LOCK_REFRESH_INTERVAL_MS = 30_000L
private const val LOCKED_COUNT_LABEL = "지금 잠긴 앱 %d개"

/** 캘린더 일정이 "완료"임을 뜻하는 status 값 — 앱 캘린더 화면(`CalendarScreen`)이 쓰는 값 그대로. */
private const val CALENDAR_DONE = "O"

/**
 * 지금 차단 규칙(시간대/일일 사용한도)에 걸려 목록에서 감출 앱의 패키지명(130차 2단계).
 *
 * **판정은 [LockEvaluator]를 그대로 부르고 여기서 다시 구현하지 않는다** — 재확인/escalation을 포함한
 * 판정 로직은 이 프로젝트에서 복제 금지 구역이다([[HANDOFF.md]] "현재 주의사항"). 런처는 그 결과를
 * 읽어 목록에서 빼기만 한다.
 *
 * 숨기는 대상은 사용자가 요청한 두 가지, **시간대 차단([LockReason.SCHEDULE])과 일일 사용한도
 * ([LockReason.LIMIT])**뿐이다. 실행 확인만 걸린 앱은 목록에 그대로 두고(누르면 기존 실행 확인
 * 절차가 돈다), 공부 잠금은 전용 전체화면([com.phonelock.app.ui.StudyLockActivity])이 따로 담당한다.
 *
 * **142차: 전체 잠금**(전체 잠금 방식 규칙 · 타이머의 전체 잠금)이 걸려 있으면 반대로 "허용한 앱만" 남긴다 —
 * 홈 화면은 그대로지만 앱 목록에는 허용한 앱과 전화·시계 같은 필수 앱만 보인다.
 */
private suspend fun loadLauncherLock(context: Context): LauncherLock = withContext(Dispatchers.IO) {
    runCatching {
        val repository = PhoneLockRepository(context.applicationContext)
        val evaluator = LockEvaluator(repository)
        val locked = mutableSetOf<String>()
        var allowOnly: Set<String>? = null
        // 전체 잠금이 둘 이상 겹치면 모두가 허용한 앱만 남는다.
        fun restrictTo(allowed: Set<String>) { allowOnly = allowOnly?.intersect(allowed) ?: allowed }
        for (group in repository.getAllGroupsOnce()) {
            val result = evaluator.evaluate(group)
            if (!result.locked) continue
            if (result.reason != LockReason.SCHEDULE && result.reason != LockReason.LIMIT) continue
            val members = repository.getMembers(group.id).map { it.packageName }.toSet()
            if (group.allowlistMode) restrictTo(members) else locked += members
        }
        // 관리 > 타이머(142차)가 잠금 단계면 같은 방식으로 반영한다.
        // 143차: 뽀모도로 휴식 중엔 잠금이 풀린 약속이므로 목록에서도 감추지 않는다(enforcedLockTimer).
        repository.enforcedLockTimer(AppPreferences(context.applicationContext).lockTimer)
            ?.let { timer -> if (timer.wholeDevice) restrictTo(timer.apps) else locked += timer.apps }
        LauncherLock(locked.toSet(), allowOnly?.plus(EssentialApps.packages(context.applicationContext)))
    }.getOrDefault(LauncherLock.NONE)
}

/** 런처가 목록에서 감출 기준 — [locked]는 막힌 앱, [allowOnly]는 전체 잠금 중일 때만 있는 "이것만 보여라" 목록. */
private data class LauncherLock(val locked: Set<String>, val allowOnly: Set<String>?) {
    companion object { val NONE = LauncherLock(emptySet(), null) }
}

/**
 * 런처 홈에 얹는 갓생살기종합세트 현재 상태(131차, 132차에 경험치/칭호를 따로 분리). 레벨·칭호·경험치와
 * 보조 줄 몇 개뿐이라 앱 홈의 움직이는 식물 씬을 옮겨오지 않는다 — 이 화면의 목적이 "자극 없이 현재
 * 상태만 보이기"이기 때문이다.
 *
 * 조회마다 따로 runCatching을 걸어 **한 줄이 실패해도 나머지 줄과 화면은 그대로 뜨게** 한다.
 */
private data class LauncherStatus(
    val level: String?,
    val stageTitle: String?,
    val progress: Float,
    val lines: List<LauncherStatusLine>
) {
    companion object { val EMPTY = LauncherStatus(null, null, 0f, emptyList()) }
}

/**
 * 갓생 카드의 정보 한 줄. 133차부터 세 줄 모두 "지금 뭘 해야 하는가"를 앞에 쓰고 진척(n/m)을 뒤에
 * 붙인다 — ✅ 밀린 루틴 중 가장 먼저 할 것 / ⏱️ 오늘 공부 시간 / 📅 오늘 일정 중 가장 먼저 할 것.
 */
private data class LauncherStatusLine(val emoji: String, val text: String)

private suspend fun loadGodsaengStatus(context: Context): LauncherStatus = withContext(Dispatchers.IO) {
    val repository = runCatching { PhoneLockRepository(context.applicationContext) }.getOrNull()
        ?: return@withContext LauncherStatus.EMPTY
    val today = LocalDate.now()
    val dateKey = today.toString()

    var level: String? = null
    var stageTitle: String? = null
    var progress = 0f
    runCatching {
        val totalExp = repository.getGrowthExpTotal()
        val levelValue = GrowthSystem.levelForExp(totalExp)
        level = "Lv.$levelValue"
        stageTitle = GrowthSystem.stageForLevel(levelValue).title
        progress = GrowthSystem.progressToNextLevel(totalExp)
    }

    val lines = mutableListOf<LauncherStatusLine>()
    runCatching {
        val scheduled = repository.getRoutines().filter { RoutineEngine.isScheduledOn(it, today) }
        if (scheduled.isEmpty()) {
            lines += LauncherStatusLine("✅", "오늘 예정된 루틴 없음")
        } else {
            val completedIds = repository.getRoutineLogsForDate(dateKey).map { it.routineId }.toSet()
            val done = scheduled.count { it.id in completedIds }
            val next = nextPendingRoutine(scheduled, completedIds)
            lines += LauncherStatusLine("✅", "${next?.title ?: "루틴 다 했어요"} $done/${scheduled.size}")
        }
    }
    runCatching {
        // 0분이어도 줄을 빼지 않는다 — 공부를 안 한 날 줄이 통째로 사라지면 카드 높이가 들쭉날쭉해진다.
        val seconds = repository.getTodayStudyLog().sumOf { it.seconds }
        lines += LauncherStatusLine("⏱️", "오늘 집중 ${formatStudySeconds(seconds)}")
    }
    runCatching {
        // 141차: 일정의 "오늘"은 루틴(자정)과 달리 "하루 시작 기준" — 캘린더 화면과 같은 날을 보여준다.
        val calendarDateKey = repository.todayCalendarDateKey()
        val all = repository.getAllCalendarTasksOnce()
        val todays = todayCalendarTasks(all, calendarDateKey)
        if (todays.isNotEmpty()) {
            val done = todays.count { it.status == CALENDAR_DONE }
            val next = todays.firstOrNull { it.status != CALENDAR_DONE }
            lines += LauncherStatusLine("📅", "${next?.name ?: "오늘 일정 완료"} $done/${todays.size}")
        } else {
            // 오늘 일정이 아예 없을 때만 예전처럼 "가장 가까운 다음 일정"을 D-day로 알려준다.
            all.filter { it.status != CALENDAR_DONE && it.dateKey > calendarDateKey }
                .minByOrNull { it.dateKey }
                ?.let { task ->
                    val days = ChronoUnit.DAYS.between(LocalDate.parse(calendarDateKey), LocalDate.parse(task.dateKey))
                    lines += LauncherStatusLine("📅", "${task.name} D-$days")
                }
        }
    }
    LauncherStatus(level, stageTitle, progress, lines)
}

private fun formatStudySeconds(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}시간 ${minutes}분" else "${minutes}분"
}

private fun launchApp(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent == null) {
        Toast.makeText(context, "이 앱을 열 수 없습니다", Toast.LENGTH_SHORT).show()
        return
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** 런처 홈의 바로가기 — 앱을 열면서 곧바로 그 탭으로 들어간다(서브탭은 앱이 마지막에 보던 자리를 그대로 쓴다). */
private fun openShortcut(context: Context, shortcut: GodsaengShortcut) {
    runCatching { context.startActivity(MainActivity.openIntent(context, shortcut.route)) }
}

private fun openMainApp(context: Context) {
    runCatching {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openAppInfo(context: Context, packageName: String) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
