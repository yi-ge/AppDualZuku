package com.nathanhanapps.appdual

/** Applies the selected parent's private-space restart policy with bounded temporary permission. */
class PrivateStartupPolicy(private val shell: IShellExecutor, private val appPackage: String) {
    fun configure(callback: (Boolean, String) -> Unit) {
        val log = StringBuilder()
        fun run(cmd: String, next: (ShellResult) -> Unit) {
            shell.execWhenReady(cmd) { output ->
                val result = ShellResult.parse(cmd, output)
                log.append(result.render()).append('\n')
                next(result)
            }
        }
        run("am get-current-user") parent@ { current ->
            val parent = current.stdout.trim().toIntOrNull()
            if (!current.successful || parent == null || parent < 0) { callback(false, log.toString()); return@parent }
            val uri = ShellResult.quote("content://$appPackage.bootstrap")
            val pkg = ShellResult.quote(appPackage)
            run("content call --user $parent --uri $uri --method policy-capabilities") capabilities@ { capabilities ->
                if (!capabilities.successful || !capabilities.stdout.contains("supported=true")) {
                    callback(false, "当前 Android／用户上下文不支持此策略。\n$log"); return@capabilities
                }
                val hadPermission = capabilities.stdout.contains("canWriteSecure=true")
                fun cleanup(success: Boolean) {
                    // 只撤销本次授予的权限；不撤销用户原有授权。
                    if (hadPermission) callback(success, log.toString())
                    else run("pm revoke --user $parent $pkg android.permission.WRITE_SECURE_SETTINGS") { revoked ->
                        callback(success && revoked.successful, log.toString())
                    }
                }
                fun apply() {
                    run("content call --user $parent --uri $uri --method private-autolock-after-restart") applied@ { applied ->
                        if (!applied.successful || !applied.stdout.contains("status=APPLIED")) { cleanup(false); return@applied }
                        run("settings --user $parent get secure private_space_auto_lock") { checked ->
                            cleanup(checked.successful && checked.stdout.trim() == "2")
                        }
                    }
                }
                if (hadPermission) apply()
                else run("pm grant --user $parent $pkg android.permission.WRITE_SECURE_SETTINGS") { granted ->
                    if (granted.successful) apply() else callback(false, log.toString())
                }
            }
        }
    }
}
