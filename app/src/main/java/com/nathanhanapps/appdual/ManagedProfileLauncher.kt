package com.nathanhanapps.appdual

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.os.UserManager

/** Standard managed-profile launch: no privileged shell is needed to open an unlocked work app. */
object ManagedProfileLauncher {
    fun start(context: Context, target: WorkspaceShortcutTarget): Boolean {
        if (target.userType != "android.os.usertype.profile.MANAGED") return false
        return runCatching {
            val users = context.getSystemService(UserManager::class.java)
            val handle = UserHandle.getUserHandleForUid(target.userId * 100000)
            check(users.userProfiles.contains(handle))
            check(users.getSerialNumberForUser(handle) == target.serialNumber)
            val launcher = context.getSystemService(LauncherApps::class.java)
            val activity = launcher.getActivityList(target.packageName, handle)
                .firstOrNull { it.user == handle && it.componentName.packageName == target.packageName }
                ?: error("No enabled work activity")
            // 显式 UserHandle，避免 OEM 桌面将“工作资料”误指向最后加入的私人资料。
            launcher.startMainActivity(activity.componentName, handle, null, null)
            true
        }.getOrDefault(false)
    }
}
