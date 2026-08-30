package com.phonelock.app.service

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

object AccessibilityServiceChecker {
    fun isEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${AppMonitorAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
