package com.nathanhanapps.appdual

/** Read-only shell-side API probe. Missing methods are reported, never treated as support. */
object WorkspaceCompatibilityProbe {
    @JvmStatic fun main(args: Array<String>) {
        val missing = mutableListOf<String>()
        fun check(name: String, action: () -> Unit) { runCatching(action).onFailure { missing += "$name: ${it.javaClass.simpleName}" } }
        check("UserManager binder") {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            Class.forName("android.os.IUserManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java)
        }
        check("Current user") { Class.forName("android.app.ActivityManager").getMethod("getCurrentUser") }
        check("Profile identity") {
            Class.forName("android.content.pm.UserInfo").getMethod("isProfile")
            Class.forName("android.content.pm.UserInfo").getField("userType")
        }
        val api = runCatching { Class.forName("android.os.IUserManager") }.getOrNull()
        if (api == null) { println("unlockApi=UNSUPPORTED\nIUserManager missing"); return }
        for (name in listOf("getUserInfo", "getProfileParent", "isQuietModeEnabled", "isUserUnlocked", "isUserRunning")) {
            check(name) { api.getMethod(name, Int::class.javaPrimitiveType) }
        }
        check("Quiet-mode request") { api.getMethod("requestQuietModeEnabled", String::class.java,
            Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, android.content.IntentSender::class.java, Int::class.javaPrimitiveType) }
        println("sdk=${android.os.Build.VERSION.SDK_INT}")
        println("unlockApi=" + if (missing.isEmpty()) "AVAILABLE" else "UNSUPPORTED")
        missing.forEach(::println)
    }
}

object WorkspaceCompatibility {
    fun command(appPackage: String): String {
        val pkg = ShellResult.quote(appPackage)
        return "apk=\$(pm path $pkg | head -n 1); apk=\${apk#package:}; CLASSPATH=\"\$apk\" app_process /system/bin ${WorkspaceCompatibilityProbe::class.java.name}"
    }
}

object ShortcutMigration {
    fun resolve(target: WorkspaceShortcutTarget, workspace: WorkspaceInfo?, parent: Int): WorkspaceShortcutTarget? =
        workspace?.takeIf { target.matches(it, parent) }?.let { target.copy(userType = it.userType) }
}
