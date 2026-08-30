package com.phonelock.app.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.phonelock.app.R
import com.phonelock.app.data.AppPreferences
import com.phonelock.app.data.PhoneLockRepository
import com.phonelock.app.data.Routine
import com.phonelock.app.ui.theme.paletteFor
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

private fun isScheduledOn(routine: Routine, date: LocalDate): Boolean {
    routine.startDate?.let { if (date.isBefore(LocalDate.parse(it))) return false }
    routine.endDate?.let { if (date.isAfter(LocalDate.parse(it))) return false }
    val bitIndex = date.dayOfWeek.value - 1
    return (routine.daysMask shr bitIndex) and 1 == 1
}

/**
 * 위젯 ListView의 데이터 소스 — 오늘 예정된 루틴만, 시간대 있는 순서로(RoutineScreen.kt의 "오늘" 탭
 * 정렬과 동일 규칙). onDataSetChanged()는 바인더 스레드에서 동기 호출되므로 runBlocking으로 suspend
 * Repository 함수를 그 자리에서 기다린다(위젯 갱신 빈도가 낮아 부담 없음).
 */
class RoutineWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private val repository = PhoneLockRepository(context)
    private var items: List<Pair<Routine, Boolean>> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val today = LocalDate.now()
        val dateKey = today.toString()
        items = runBlocking {
            repository.getRoutines()
                .filter { isScheduledOn(it, today) }
                .sortedWith(compareBy(nullsLast()) { it.timeSlot })
                .map { it to repository.isRoutineCompleted(it.id, dateKey) }
        }
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = items[position].first.id
    override fun hasStableIds(): Boolean = true
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val (routine, done) = items[position]
        val themeMode = AppPreferences(context).themeMode
        val palette = paletteFor(themeMode)
        val views = RemoteViews(context.packageName, R.layout.routine_widget_item)
        views.setInt(R.id.item_row, "setBackgroundResource", widgetItemRowRes(themeMode))
        views.setTextColor(R.id.item_title, palette.onBackground.toArgb())
        views.setTextViewText(R.id.item_title, if (routine.icon.isNotBlank()) "${routine.icon} ${routine.title}" else routine.title)
        views.setImageViewResource(
            R.id.item_check,
            if (done) widgetCheckedRes(themeMode) else widgetUncheckedRes(themeMode)
        )
        val fillInIntent = Intent().apply {
            putExtra(EXTRA_ROUTINE_ID, routine.id)
        }
        views.setOnClickFillInIntent(R.id.item_row, fillInIntent)
        return views
    }
}
