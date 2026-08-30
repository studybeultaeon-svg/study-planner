package com.phonelock.app.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.phonelock.app.R
import com.phonelock.app.data.PhoneLockRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 위젯 리스트 항목을 탭했을 때 그 루틴의 오늘 완료 체크를 토글한다(RoutineScreen의 toggleRoutineLog와 동일 동작). */
class RoutineWidgetToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val routineId = intent.getLongExtra(EXTRA_ROUTINE_ID, -1L)
        if (routineId < 0) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = PhoneLockRepository(appContext)
                repository.toggleRoutineLog(routineId, LocalDate.now().toString())
                val manager = AppWidgetManager.getInstance(appContext)
                val ids = manager.getAppWidgetIds(ComponentName(appContext, RoutineWidgetProvider::class.java))
                ids.forEach { manager.notifyAppWidgetViewDataChanged(it, R.id.widget_list) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
