package com.nathanhanapps.appdual

class WorkspaceRepository(
    private val shell: IShellExecutor,
    private val appPackage: String,
    private val scheduleUnlockCheck: (() -> Unit) -> Unit = { task ->
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ task() }, 1000)
    }
) {

    enum class Stage { CREATING_PROFILE, STARTING_PROFILE, INSTALLING_DPC, INSTALLING_BOOTSTRAP_APP, SETTING_PROFILE_OWNER,
        VERIFYING_PROFILE_OWNER, INITIALIZING_PROFILE, VERIFYING_SETUP, FINALIZING_RAW_PROFILE, REVOKING_TEMP_PERMISSION, READY, FAILED, ROLLING_BACK }

    fun listWorkspaces(callback: (List<WorkspaceInfo>) -> Unit) {
        shell.execWhenReady("pm list users") { output ->
            val users = WorkspaceParsers.parseUsers(output)
            shell.execWhenReady("dumpsys user") { details ->
                val enriched = WorkspaceParsers.enrich(users, details)
                shell.execWhenReady("dumpsys device_policy") { policy ->
                    val owners = Regex("""Profile Owner \(User (\d+)\):\s*admin=ComponentInfo\{([^}]+)\}""")
                        .findAll(policy).associate { it.groupValues[1].toInt() to it.groupValues[2] }
                    val authoritative = ShellResult.parse("dumpsys device_policy", policy).successful
                    val result = enriched.map { ws -> ws.copy(
                        hasProfileOwner = if (authoritative) owners.containsKey(ws.userId) else null,
                        ownerComponent = owners[ws.userId]) }.toMutableList()
                    fun readSetup(index: Int) {
                        if (index >= result.size) { callback(result.toList()); return }
                        val ws = result[index]
                        if (!ws.isManaged && !ws.isPrivate) { readSetup(index + 1); return }
                        shell.execWhenReady("settings --user ${ws.userId} get secure user_setup_complete") { setup ->
                            val state = ShellResult.parse("", setup)
                            result[index] = ws.copy(setupComplete = if (state.successful) state.stdout.trim() == "1" else null)
                            readSetup(index + 1)
                        }
                    }
                    readSetup(0)
                }
            }
        }
    }

    fun createWorkspace(name: String, type: String = "managed", callback: (Boolean, Int, String) -> Unit) {
        if (type !in setOf("managed", "clone", "private")) {
            callback(false, -1, "FAILED: Unsupported workspace type: $type")
            return
        }
        val currentUserCommand = "am get-current-user"
        shell.execWhenReady(currentUserCommand) { current ->
            val parent = ShellResult.parse(currentUserCommand, current)
            val parentUserId = parent.stdout.trim().toIntOrNull()
            if (!parent.successful || parentUserId == null) {
                callback(false, -1, "FAILED\n${parent.render()}")
                return@execWhenReady
            }
            createProfile(name, type, parentUserId, callback)
        }
    }

    private fun createProfile(name: String, type: String, parentUserId: Int, callback: (Boolean, Int, String) -> Unit) {
        val profileType = when (type) { "clone" -> "CLONE"; "private" -> "PRIVATE"; else -> "MANAGED" }
        val cmd = "pm create-user --profileOf $parentUserId --user-type android.os.usertype.profile.$profileType ${ShellResult.quote(name.trim())}"
        shell.execWhenReady(cmd) { output ->
            val result = ShellResult.parse(cmd, output)
            val id = Regex("created user id (\\d+)", RegexOption.IGNORE_CASE).find(result.stdout)
                ?.groupValues?.get(1)?.toIntOrNull() ?: -1
            if (!result.successful || id < 0) callback(false, id, "${Stage.FAILED}\n${result.render()}")
            else if (type == "clone") callback(true, id, result.render())
            else provision(id, name, true, "${Stage.CREATING_PROFILE}\n${result.render()}\n", callback, privateProfile = type == "private")
        }
    }

    /** 旧资料只尝试无损修复：绝不能因为失败而删除已有资料或账户。 */
    fun repairWorkspace(workspace: WorkspaceInfo, callback: (Boolean, Int, String) -> Unit) {
        val ownAdmin = "$appPackage/${AppDualDeviceAdminReceiver::class.java.name}"
        if ((!workspace.isManaged && !workspace.isPrivate) || (workspace.hasProfileOwner == true && workspace.ownerComponent != ownAdmin)) {
            callback(false, workspace.userId, "Repair requires a managed/private profile without a foreign owner")
            return
        }
        val id = workspace.userId
        // 只收集账户数量，不把账户名或令牌放入错误弹窗/日志。
        val countCmd = "dumpsys account | awk '/^User UserInfo/ { target = (\$0 ~ /UserInfo\\{$id:/) } target && /Accounts:/ { print \$0 }'"
        shell.execWhenReady("settings --user $id get secure user_setup_complete") { setup ->
            shell.execWhenReady(countCmd) { accounts ->
                provision(id, workspace.name, false, "Legacy preflight\n$setup\n$accounts\n", callback, workspace.ownerComponent == ownAdmin, workspace.isPrivate)
            }
        }
    }

    private fun provision(id: Int, name: String, newlyCreated: Boolean, initial: String,
                          callback: (Boolean, Int, String) -> Unit, alreadyOwned: Boolean = false, privateProfile: Boolean = false) {
        val admin = "$appPackage/${AppDualDeviceAdminReceiver::class.java.name}"
        val steps = listOf(
            Stage.STARTING_PROFILE to "am start-user -w $id",
            (if (privateProfile) Stage.INSTALLING_BOOTSTRAP_APP else Stage.INSTALLING_DPC) to "pm install-existing --user $id $appPackage",
            Stage.SETTING_PROFILE_OWNER to "dpm set-profile-owner --user $id $admin",
            Stage.VERIFYING_PROFILE_OWNER to "dumpsys device_policy",
            Stage.INITIALIZING_PROFILE to "content call --user $id --uri content://$appPackage.bootstrap --method ${if (privateProfile) "initialize-private" else "initialize"} --arg ${ShellResult.quote(name)}",
            Stage.VERIFYING_SETUP to "settings --user $id get secure user_setup_complete"
        ).filterNot {
            (alreadyOwned && it.first == Stage.SETTING_PROFILE_OWNER) ||
                (privateProfile && it.first in setOf(Stage.SETTING_PROFILE_OWNER, Stage.VERIFYING_PROFILE_OWNER))
        }
        val transcript = StringBuilder(initial)
        var finalizationAttempted = false
        var alreadyCanWriteSetup = false
        fun fail() {
            transcript.append("\nFAILED: Profile is not ready. Standard Android provisioning may be required.\n")
            if (!newlyCreated) { callback(false, id, transcript.toString()); return }
            // 只有本次创建的空资料允许回滚；repair 从不进入此分支。
            val remove = "pm remove-user $id"
            shell.execWhenReady(remove) { output ->
                transcript.append("ROLLING_BACK\n").append(output)
                callback(false, id, transcript.toString())
            }
        }
        fun finalizeRawProfile(done: (Boolean) -> Unit) {
            finalizationAttempted = true
            val grant = "pm grant --user $id $appPackage android.permission.WRITE_SECURE_SETTINGS"
            val finalize = "content call --user $id --uri content://$appPackage.bootstrap --method ${if (privateProfile) "complete-private-setup" else "complete-raw-setup"}"
            val revoke = "pm revoke --user $id $appPackage android.permission.WRITE_SECURE_SETTINGS"
            fun complete() {
                shell.execWhenReady(finalize) { output ->
                    transcript.append("FINALIZING_RAW_PROFILE\n$output\n")
                    val success = ShellResult.parse(finalize, output).successful && output.contains("setupComplete=true") && !output.contains("status=FAILED")
                    // 临时权限即使初始化失败也撤销；已有用户主动授予的权限不动。
                    if (alreadyCanWriteSetup) done(success)
                    else shell.execWhenReady(revoke) { revoked ->
                        transcript.append("REVOKING_TEMP_PERMISSION\n$revoked\n")
                        done(success && ShellResult.parse(revoke, revoked).successful)
                    }
                }
            }
            if (alreadyCanWriteSetup) complete()
            else shell.execWhenReady(grant) { output ->
                transcript.append("FINALIZING_RAW_PROFILE\n$output\n")
                if (ShellResult.parse(grant, output).successful) complete() else done(false)
            }
        }
        fun next(index: Int) {
            if (index == steps.size) { transcript.append("READY"); callback(true, id, transcript.toString()); return }
            val (stage, cmd) = steps[index]
            shell.execWhenReady(cmd) { output ->
                val result = ShellResult.parse(cmd, output)
                // device_policy 全量可能含其他用户策略；只记录目标 Owner 验证结果。
                val valid = result.successful && when (stage) {
                    Stage.VERIFYING_PROFILE_OWNER -> Regex("""Profile Owner \(User $id\):\s*admin=ComponentInfo\{${Regex.escape(admin)}\}""").containsMatchIn(result.stdout)
                    Stage.INITIALIZING_PROFILE -> result.stdout.contains("status=INITIALIZED") && !result.stdout.contains("FAILED")
                    Stage.VERIFYING_SETUP -> result.stdout.trim() == "1"
                    else -> !result.stdout.contains("Error:")
                }
                if (stage == Stage.VERIFYING_PROFILE_OWNER) transcript.append("$stage: verified=$valid\n")
                else transcript.append("$stage\n${result.render()}\n")
                if (stage == Stage.INITIALIZING_PROFILE) alreadyCanWriteSetup = result.stdout.contains("canWriteSetup=true")
                if (!valid && stage == Stage.VERIFYING_SETUP && result.successful && !finalizationAttempted) {
                    // 真机已验证：raw 创建 + Owner/激活仍不写 setup；Recents 会过滤此类任务。
                    // 仅对刚刚通过 Owner 和 bootstrap 校验的资料补齐，不触及主用户或全局设置。
                    finalizeRawProfile { completed -> if (completed) next(index) else fail() }
                } else if (!valid) fail() else next(index + 1)
            }
        }
        next(0)
    }

    fun removeWorkspace(userId: Int, callback: (Boolean, String) -> Unit) {
        shell.execWhenReady("pm remove-user $userId") { out ->
            callback(ShellResult.parse("", out).successful && out.contains("Success", ignoreCase = true), out)
        }
    }

    fun startWorkspace(userId: Int, callback: (Boolean, String) -> Unit) {
        if (userId <= 0) { callback(false, "请选择工作空间"); return }
        val unlock = ProfileUnlockCommand.build(appPackage, userId)
        shell.execWhenReady(unlock) { output ->
            val result = ShellResult.parse(unlock, output)
            if (!result.successful) { callback(false, result.render()); return@execWhenReady }
            if (result.stdout.contains("CREDENTIAL_REQUIRED")) {
                // 系统验证与资料启动是异步的。返回 false 只代表需要验证，不能立即判为失败。
                waitForCredentialUnlock(userId, 0, callback)
                return@execWhenReady
            }
            val start = "am start-user $userId"
            shell.execWhenReady(start) { started ->
                val state = ShellResult.parse(start, started)
                if (!state.successful || state.stdout.contains("failed", true) || state.stdout.contains("Error:", true)) {
                    callback(false, result.render() + "\n" + state.render())
                } else {
                    val wait = "i=0; while [ \"\$(am get-started-user-state $userId)\" != RUNNING_UNLOCKED ] && [ \"\$i\" -lt 20 ]; do sleep 0.5; i=\$((i+1)); done; am get-started-user-state $userId"
                    shell.execWhenReady(wait) { status ->
                    val checked = ShellResult.parse(wait, status)
                    val unlocked = checked.successful && checked.stdout.trim() == "RUNNING_UNLOCKED"
                    callback(unlocked, if (unlocked) state.render() else
                        "空间尚未解锁，请完成系统身份验证后重试。\n" + checked.render())
                    }
                }
            }
        }
    }

    private fun waitForCredentialUnlock(userId: Int, attempt: Int, callback: (Boolean, String) -> Unit) {
        if (!shell.isReady()) { callback(false, "执行服务已断开，请重新启动 Shizuku 后重试。"); return }
        val cmd = "am get-started-user-state $userId"
        shell.execWhenReady(cmd) { output ->
            val state = ShellResult.parse(cmd, output)
            if (state.successful && state.stdout.trim() == "RUNNING_UNLOCKED") {
                callback(true, state.render())
            } else if (attempt >= 120 || (!state.successful &&
                    !(state.stdout + state.stderr).contains("User is not started"))) {
                callback(false, "空间尚未解锁。请完成系统身份验证后重试；不会清除数据。\n" + state.render())
            } else scheduleUnlockCheck { waitForCredentialUnlock(userId, attempt + 1, callback) }
        }
    }

    fun stopWorkspace(userId: Int, callback: (Boolean, String) -> Unit) {
        shell.execWhenReady("am stop-user -f $userId") { out ->
            callback(ShellResult.parse("", out).successful && !out.contains("failed", ignoreCase = true), out)
        }
    }

    fun getInstalledPackages(userId: Int, callback: (Set<String>) -> Unit) {
        shell.execWhenReady("pm list packages --user $userId") { output ->
            callback(PmParsers.parsePmListPackages(output))
        }
    }

    fun installToWorkspace(userId: Int, packageName: String, callback: (Boolean, String) -> Unit) {
        shell.execWhenReady("pm install-existing --user $userId $packageName") { out ->
            val ok = ShellResult.parse("", out).successful && out.contains("Package", ignoreCase = true) &&
                    out.contains("installed", ignoreCase = true)
            callback(ok, out)
        }
    }

    fun uninstallFromWorkspace(userId: Int, packageName: String, keepData: Boolean, callback: (Boolean, String) -> Unit) {
        val keepFlag = if (keepData) "-k " else ""
        shell.execWhenReady("pm uninstall $keepFlag--user $userId $packageName") { out ->
            callback(ShellResult.parse("", out).successful && out.contains("Success", ignoreCase = true), out)
        }
    }

    fun launchInWorkspace(userId: Int, component: String, callback: (Boolean, String) -> Unit) {
        fun launch() {
            val cmd = "am start --user $userId -n ${ShellResult.quote(component)}"
            shell.execWhenReady(cmd) { out ->
                val result = ShellResult.parse(cmd, out)
                callback(result.successful && !result.stdout.contains("Error", true), result.render())
            }
        }
        if (userId == 0) launch()
        else startWorkspace(userId) { ok, detail -> if (ok) launch() else callback(false, detail) }
    }

    fun openAppInfoInWorkspace(userId: Int, packageName: String, callback: (Boolean, String) -> Unit) {
        val cmd = "am start --user $userId -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$packageName"
        shell.execWhenReady(cmd) { out ->
            callback(ShellResult.parse("", out).successful && !out.contains("failed", ignoreCase = true), out)
        }
    }

    /** Suggests "Work1", "Work2", etc. avoiding names already taken. */
    fun suggestName(existing: List<WorkspaceInfo>, prefix: String = "Work"): String {
        val taken = existing.filter { !it.isMainUser }.map { it.name }.toSet()
        var i = 1
        while ("$prefix$i" in taken) i++
        return "$prefix$i"
    }

}
