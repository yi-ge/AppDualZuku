package com.nathanhanapps.appdual

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.UserManager

object ProfileInitializer {
    /** PRIVATE 是独立资料，不是工作资料，不能为了初始化给它错误设置 DPC Owner。 */
    fun initializePrivate(context: Context): String {
        check(android.os.Build.VERSION.SDK_INT >= 35) { "Private profiles require Android 15+" }
        val users = context.getSystemService(UserManager::class.java)
        check(users.isProfile && !users.isManagedProfile) { "Requires private profile, never the parent user" }
        // isPrivateProfile 是只读 System API；普通 SDK 不暴露。不可查询时失败关闭，不能猜测类型。
        val privateProfile = runCatching {
            UserManager::class.java.getMethod("isPrivateProfile").invoke(users) as Boolean
        }.getOrElse { throw IllegalStateException("Private profile type query unavailable", it) }
        check(privateProfile) { "Requires android.os.usertype.profile.PRIVATE" }
        check(users.isUserUnlocked) { "Private profile must be unlocked" }
        return "INITIALIZED PRIVATE"
    }

    fun initialize(context: Context, name: String? = null): String {
        val users = context.getSystemService(UserManager::class.java)
        val policy = context.getSystemService(DevicePolicyManager::class.java)
        // 必须在目标 Managed Profile 内执行，不能把主用户或其他 DPC 的资料当作自己的。
        check(users.isManagedProfile && policy.isProfileOwnerApp(context.packageName)) {
            "Requires managed profile and AppDual Profile Owner"
        }
        val admin = ComponentName(context, AppDualDeviceAdminReceiver::class.java)
        if (!name.isNullOrBlank()) policy.setProfileName(admin, name)
        // 仅启用镜像中的必要 Google 系统组件；不清除数据、不取消用户安全限制。
        val enabled = mutableListOf<String>()
        for (pkg in listOf("com.google.android.gms", "com.android.vending", "com.google.android.gsf", "com.google.android.gsf.login")) {
            val info = runCatching { context.packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES) }.getOrNull()
            if (info != null && info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && info.enabled && info.flags and ApplicationInfo.FLAG_INSTALLED == 0) {
                policy.enableSystemApp(admin, pkg)
                enabled += pkg
            }
        }
        // Profile Owner 与激活是必要生命周期步骤，但不等于 Secure setup 已完成。
        policy.setProfileEnabled(admin)
        return "INITIALIZED; systemApps=${enabled.joinToString()}"
    }
}
