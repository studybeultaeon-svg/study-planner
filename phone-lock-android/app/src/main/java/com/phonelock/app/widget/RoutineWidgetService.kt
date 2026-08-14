package com.phonelock.app.widget

import android.content.Intent
import android.widget.RemoteViewsService

/** RoutineWidgetProvider의 ListView가 쓰는 RemoteViewsFactory 진입점. */
class RoutineWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = RoutineWidgetFactory(applicationContext)
}
