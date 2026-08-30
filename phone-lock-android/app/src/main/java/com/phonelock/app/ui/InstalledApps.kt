package com.phonelock.app.ui

import android.content.Context
import android.content.Intent

data class AppInfo(val label: String, val packageName: String)

fun getLaunchableApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = pm.queryIntentActivities(intent, 0)
    return resolved
        .filter { it.activityInfo.packageName != context.packageName }
        .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
