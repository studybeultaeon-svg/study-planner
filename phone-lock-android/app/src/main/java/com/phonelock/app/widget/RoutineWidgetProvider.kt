package com.phonelock.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.phonelock.app.R
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.ui.MainActivity
import com.phonelock.app.ui.theme.ThemeMode
import com.phonelock.app.ui.theme.paletteFor
import androidx.compose.ui.graphics.toArgb
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** 위젯 리스트 항목 클릭 시 토글할 루틴 id를 실어보내는 fill-in intent extra 키. */
const val EXTRA_ROUTINE_ID = "routine_id"

/**
 * 위젯은 RemoteViews라 Compose MaterialTheme을 못 쓴다 — 앱 본체가 50차에 테마 3종 선택제가 됐는데
 * 위젯 리소스(XML)는 라이트+그린 색상이 그대로 박혀있던 걸 사용자 요청으로 테마별 드로어블/텍스트
 * 색을 런타임에 갈아끼우는 방식으로 맞춤(2026-08-14). 팔레트 자체는 PhoneLockPalette(Color.kt) 그대로
 * 재사용해서 색상 값을 중복 정의하지 않는다.
 */
fun widgetBackgroundRes(themeMode: String): Int = when (themeMode) {
    ThemeMode.DARK_BLUE -> R.drawable.widget_background_dark_blue
    ThemeMode.LIGHT_ORANGE -> R.drawable.widget_background_light_orange
    else -> R.drawable.widget_background
}

fun widgetItemRowRes(themeMode: String): Int = when (themeMode) {
    ThemeMode.DARK_BLUE -> R.drawable.widget_item_row_dark_blue
    ThemeMode.LIGHT_ORANGE -> R.drawable.widget_item_row_light_orange
    else -> R.drawable.widget_item_row
}

fun widgetCheckedRes(themeMode: String): Int = when (themeMode) {
    ThemeMode.DARK_BLUE -> R.drawable.ic_widget_checked_dark_blue
    ThemeMode.LIGHT_ORANGE -> R.drawable.ic_widget_checked_light_orange
    else -> R.drawable.ic_widget_checked
}

fun widgetUncheckedRes(themeMode: String): Int = when (themeMode) {
    ThemeMode.DARK_BLUE -> R.drawable.ic_widget_unchecked_dark_blue
    ThemeMode.LIGHT_ORANGE -> R.drawable.ic_widget_unchecked_light_orange
    else -> R.drawable.ic_widget_unchecked
}

/**
 * 홈 화면 위젯(사용자 요청, 51차) — 루틴 "오늘" 탭의 체크리스트를 홈 화면에서 그대로 보고 체크할 수
 * 있게 한다. 항목 수가 가변이라 RemoteViewsService 기반 컬렉션 위젯(ListView)으로 구현 — Jetpack
 * Glance(Compose 위젯)는 새 의존성이 필요해 대신 표준 RemoteViews를 썼다.
 */
class RoutineWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id) }
    }

    companion object {
        /** 앱 쪽에서 루틴이 추가/수정/삭제/체크될 때마다 호출 — 위젯이 하나도 없으면 조용히 아무 일도 안 한다. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RoutineWidgetProvider::class.java))
            ids.forEach { id -> updateWidget(context, manager, id) }
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.routine_widget)

            val themeMode = AppPreferences(context).themeMode
            val palette = paletteFor(themeMode)
            views.setInt(R.id.widget_root, "setBackgroundResource", widgetBackgroundRes(themeMode))
            views.setTextColor(R.id.widget_date, palette.primary.toArgb())
            views.setTextColor(R.id.widget_empty, palette.muted.toArgb())

            val today = LocalDate.now()
            val dateLabel = "🌱 ${today.monthValue}월 ${today.dayOfMonth}일 (${today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"
            views.setTextViewText(R.id.widget_date, dateLabel)

            // 위젯마다 서로 다른 Intent로 구분돼야 시스템이 RemoteViewsFactory를 위젯별로 따로 관리한다.
            val serviceIntent = Intent(context, RoutineWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse("widget://routine/$appWidgetId")
            }
            views.setRemoteAdapter(R.id.widget_list, serviceIntent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)

            val toggleIntent = Intent(context, RoutineWidgetToggleReceiver::class.java)
            val togglePendingIntent = PendingIntent.getBroadcast(
                context, 0, toggleIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setPendingIntentTemplate(R.id.widget_list, togglePendingIntent)

            val openAppPendingIntent = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_date, openAppPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
        }
    }
}
