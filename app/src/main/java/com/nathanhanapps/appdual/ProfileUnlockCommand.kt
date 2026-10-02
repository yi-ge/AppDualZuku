package com.nathanhanapps.appdual

/** Runs in the selected shell identity, for both Shizuku and existing Root mode. */
object ProfileUnlockCommand {
    fun build(appPackage: String, userId: Int): String {
        require(userId > 0)
        // 使用当前 APK 的代码，避免硬编码 Binder transaction 或依赖 OEM shell 子命令。
        val pkg = ShellResult.quote(appPackage)
        return "apk=\$(pm path $pkg | head -n 1); apk=\${apk#package:}; " +
            "CLASSPATH=\"\$apk\" app_process /system/bin ${ProfileUnlockProcess::class.java.name} $userId"
    }
}

object ProfileUnlockProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val id = args.first().toInt()
            require(args.size == 1 || (BuildConfig.DEBUG && args.size == 2 && args[1] == "--pause"))
            require(id > 0) { "Cannot unlock the main user" }
            val managerClass = Class.forName("android.os.ServiceManager")
            val binder = managerClass.getMethod("getService", String::class.java).invoke(null, "user")
            val stub = Class.forName("android.os.IUserManager\$Stub")
            val service = stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            val api = Class.forName("android.os.IUserManager")
            val info = api.getMethod("getUserInfo", Int::class.javaPrimitiveType).invoke(service, id)
                ?: error("Workspace no longer exists")
            val profile = info.javaClass.getMethod("isProfile").invoke(info) == true
            if (!profile) { println("NOT_PROFILE"); return }
            val parent = api.getMethod("getProfileParent", Int::class.javaPrimitiveType).invoke(service, id)
                ?: error("Profile parent unavailable")
            val current = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
            require(parent.javaClass.getField("id").getInt(parent) == current) { "Workspace belongs to another user" }
            val quiet = api.getMethod("isQuietModeEnabled", Int::class.javaPrimitiveType).invoke(service, id) == true
            val unlocked = api.getMethod("isUserUnlocked", Int::class.javaPrimitiveType).invoke(service, id) == true
            val request = api.getMethod("requestQuietModeEnabled", String::class.java,
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                android.content.IntentSender::class.java, Int::class.javaPrimitiveType)
            if (BuildConfig.DEBUG && args.getOrNull(1) == "--pause") {
                request.invoke(service, "com.android.shell", true, id, null, 0)
                println("PAUSED"); return
            }
            if (!quiet && unlocked) { println("ACTIVE"); return }
            val privateProfile = info.javaClass.getField("userType").get(info) == "android.os.usertype.profile.PRIVATE"
            // 私人资料的统一锁必须在资料运行时完成验证；重试不能再次暂停资料。
            if (privateProfile && !unlocked) {
                val start = ProcessBuilder("am", "start-user", id.toString()).redirectErrorStream(true).start()
                val output = start.inputStream.bufferedReader().use { it.readText() }
                check(start.waitFor() == 0 && !output.contains("Error:") && !output.contains("failed", true)) {
                    "Profile start failed: $output"
                }
                val running = api.getMethod("isUserRunning", Int::class.javaPrimitiveType)
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (running.invoke(service, id) != true && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                check(running.invoke(service, id) == true) { "Profile did not start" }
            }
            // flags=0 保留系统凭据校验，交由系统使用合法的统一锁缓存或弹出验证。
            val accepted = request.invoke(service, "com.android.shell", false, id, null, 0) == true
            println(if (accepted) "RESUMED" else "CREDENTIAL_REQUIRED")
        } catch (error: Throwable) {
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            System.err.println("Profile unlock failed: ${cause.javaClass.simpleName}: ${cause.message}")
            kotlin.system.exitProcess(1)
        }
    }
}
