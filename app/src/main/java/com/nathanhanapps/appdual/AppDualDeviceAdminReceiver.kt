package com.nathanhanapps.appdual

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AppDualDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        runCatching { ProfileInitializer.initialize(context) }
            .onFailure { DebugLog.trace(context, "Profile initialization failed: ${it.message}") }
    }
}
