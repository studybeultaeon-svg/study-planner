package com.phonelock.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import com.phonelock.app.R
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.ui.MainActivity
import com.phonelock.app.ui.theme.PhoneLockPalette
import com.phonelock.app.ui.theme.ThemeMode
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
 *
 * 121차(사용자 지적: "커스텀 테마인데 위젯만 초록색"): 아래 `when`의 `else ->`가 CUSTOM까지 삼켜서
 * 기본 라이트+그린 드로어블을 내보내고 있었다. 커스텀 색은 런타임에만 정해져 드로어블 리소스로 만들 수
 * 없으므로, 기본 3종은 기존 드로어블을 그대로 쓰고 CUSTOM만 [applyThemedBackground]/[themedCheckBitmap]
 * 로 실제 사용자 색을 칠한다(기존 3종의 생김새는 전혀 건드리지 않는다).
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
 * 배경을 테마에 맞게 칠한다 — 기본 3종은 기존 드로어블 그대로, CUSTOM만 팔레트 색으로 덮는다.
 * RemoteViews로 드로어블 색을 바꾸는 `setColorStateList`가 API 31부터라, 그 아래에서는 둥근 모서리를
 * 포기하고 단색으로 칠한다(색이 틀린 것보다는 낫다는 판단). 호스트 프로세스의 실제 View는 재사용되므로
 * CUSTOM → 기본 테마로 되돌릴 때 이전 틴트를 반드시 null로 지워줘야 한다.
 */
private fun RemoteViews.applyThemedBackground(viewId: Int, themeMode: String, drawableRes: Int, tintArgb: Int) {
    setInt(viewId, "setBackgroundResource", drawableRes)
    val isCustom = themeMode == ThemeMode.CUSTOM
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setColorStateList(viewId, "setBackgroundTintList", if (isCustom) ColorStateList.valueOf(tintArgb) else null)
    } else if (isCustom) {
        setInt(viewId, "setBackgroundColor", tintArgb)
    }
}

/**
 * CUSTOM 테마용 체크박스 아이콘 — 체크 상태 아이콘이 "포인트색 박스 + 배경색 체크" 두 색이라 단순
 * `setColorFilter`로는 칠할 수 없어서, 기존 벡터와 같은 모양을 런타임 비트맵으로 그려 넣는다
 * (`ic_widget_checked`/`ic_widget_unchecked`의 24dp 뷰포트 좌표를 그대로 옮긴 것).
 */
private fun themedCheckBitmap(palette: PhoneLockPalette, checked: Boolean): Bitmap {
    val size = 72
    val u = size / 24f // 원본 벡터의 24 뷰포트 → 픽셀 배율
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val box = RectF(3f * u, 3f * u, 21f * u, 21f * u)
    if (checked) {
        paint.style = Paint.Style.FILL
        paint.color = palette.primary.toArgb()
        canvas.drawRoundRect(box, 2f * u, 2f * u, paint)
        // 체크 표시는 배경색이 아니라 onPrimary를 쓴다 — 커스텀 테마에서 포인트색과 배경색이 비슷하게
        // 잡히면 체크가 안 보이는데, onPrimary는 포인트색 위에서 항상 읽히도록 계산된 값이다.
        paint.style = Paint.Style.STROKE
        paint.color = palette.onPrimary.toArgb()
        paint.strokeWidth = 2.2f * u
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val check = Path().apply {
            moveTo(6.6f * u, 12.2f * u)
            lineTo(10.2f * u, 15.8f * u)
            lineTo(17.4f * u, 8.6f * u)
        }
        canvas.drawPath(check, paint)
    } else {
        paint.style = Paint.Style.STROKE
        paint.color = palette.muted.toArgb()
        paint.strokeWidth = 1.5f * u
        canvas.drawRoundRect(box, 1.25f * u, 1.25f * u, paint)
    }
    return bitmap
}

/** 체크박스 아이콘을 테마에 맞게 꽂는다 — 기본 3종은 기존 벡터, CUSTOM만 [themedCheckBitmap]. */
internal fun RemoteViews.applyThemedCheckIcon(viewId: Int, themeMode: String, palette: PhoneLockPalette, checked: Boolean) {
    if (themeMode == ThemeMode.CUSTOM) {
        setImageViewBitmap(viewId, themedCheckBitmap(palette, checked))
    } else {
        setImageViewResource(viewId, if (checked) widgetCheckedRes(themeMode) else widgetUncheckedRes(themeMode))
    }
}

/** 위젯 리스트 항목([RoutineWidgetFactory])도 같은 규칙으로 배경을 칠하기 위한 공개 창구. */
internal fun RemoteViews.applyThemedItemRowBackground(viewId: Int, themeMode: String, palette: PhoneLockPalette) {
    applyThemedBackground(viewId, themeMode, widgetItemRowRes(themeMode), palette.surfaceAlt.toArgb())
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

            val prefs = AppPreferences(context)
            val themeMode = prefs.themeMode
            val palette = prefs.currentPalette()
            views.applyThemedBackground(R.id.widget_root, themeMode, widgetBackgroundRes(themeMode), palette.background.toArgb())
            views.setTextColor(R.id.widget_date, palette.primary.toArgb())
            views.setTextColor(R.id.widget_empty, palette.muted.toArgb())

            val today = LocalDate.now()
            val dateLabel = "📋 ${today.monthValue}월 ${today.dayOfMonth}일 (${today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"
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
