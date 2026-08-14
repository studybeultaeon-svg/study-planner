package com.phonelock.app.service

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName

class PhoneLockDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun componentName(context: android.content.Context): ComponentName =
            ComponentName(context, PhoneLockDeviceAdminReceiver::class.java)
    }
}
