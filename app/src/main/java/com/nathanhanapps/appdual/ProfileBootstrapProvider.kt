package com.nathanhanapps.appdual

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/** Protected by DUMP: only shell/system can request cross-user bootstrap. */
class ProfileBootstrapProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // ContentProvider.call 不会自动替我们落实所有 URI 权限，必须再次检查 Binder 调用者。
        requireNotNull(context).enforceCallingPermission(android.Manifest.permission.DUMP, "Shell/system bootstrap only")
        if (method == "private-autolock-after-restart") return Bundle().apply {
            try {
                val ctx = requireNotNull(context)
                check(ctx.getSystemService(android.os.UserManager::class.java).isSystemUser)
                check(ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                // 仅使用 Android 的私人空间重启锁定选项，不修改全局 provisioning 或主用户密码。
                check(android.provider.Settings.Secure.putInt(ctx.contentResolver, "private_space_auto_lock", 2))
                // 此键是 @hide，普通应用不能读回；受保护的 shell 诊断端负责核验。
                putString("status", "APPLIED")
                putInt("value", 2)
            } catch (e: Exception) { putString("status", "FAILED"); putString("error", e.message) }
        }
        if (method == "pin-shortcut") return Bundle().apply {
            try {
                // 仅供已获 DUMP 权限的设备诊断工具创建独立图标；不改任何资料或应用数据。
                val ctx = requireNotNull(context)
                val users = ctx.getSystemService(android.os.UserManager::class.java)
                check(users.isSystemUser) { "Use the main app" }
                val id = requireNotNull(extras).getInt("userId", -1)
                require(id > 0 && id <= Int.MAX_VALUE / 100000)
                val requestedHandle = android.os.UserHandle.getUserHandleForUid(id * 100000)
                val handle = users.userProfiles.singleOrNull { it == requestedHandle }
                    ?: error("Target profile is not accessible from this user")
                check(handle != android.os.Process.myUserHandle())
                val serial = users.getSerialNumberForUser(handle)
                check(serial >= 0)
                val pkg = requireNotNull(extras.getString("package"))
                val info = ctx.packageManager.getApplicationInfo(pkg, 0)
                val space = WorkspaceInfo(id, extras.getString("name") ?: "Space $id", 0x10, false,
                    userType = extras.getString("userType") ?: "android.os.usertype.profile.UNKNOWN", serialNumber = serial)
                val item = AppItem(pkg, ctx.packageManager.getApplicationLabel(info).toString(), ctx.packageManager.getApplicationIcon(info))
                WorkspaceShortcuts.request(ctx, space, item)
                putString("status", "PIN_REQUESTED")
                putString("id", WorkspaceShortcutTarget(id, serial, pkg, "${item.label}·${space.displayName}").id)
            } catch (e: Exception) {
                putString("status", "FAILED")
                putString("error", "${e.javaClass.simpleName}: ${e.message}")
            }
        }
        require(method in setOf("initialize", "complete-raw-setup", "initialize-private", "complete-private-setup"))
        return Bundle().apply {
            try {
                val ctx = requireNotNull(context)
                val initialized = when (method) {
                    "initialize-private", "complete-private-setup" -> ProfileInitializer.initializePrivate(ctx)
                    else -> ProfileInitializer.initialize(ctx, if (method == "initialize") arg else null)
                }
                if (method == "complete-raw-setup" || method == "complete-private-setup") {
                    // Managed 必须有 Owner；PRIVATE 必须通过精确类型检查。只补齐目标资料的状态。
                    // 标准 Android provisioning 不经过此分支；不能用来跳过主用户的设置向导。
                    check(ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    check(android.provider.Settings.Secure.putInt(ctx.contentResolver, "user_setup_complete", 1)) { "Setup write rejected" }
                    check(android.provider.Settings.Secure.getInt(ctx.contentResolver, "user_setup_complete", 0) == 1) { "Setup readback failed" }
                }
                putString("status", initialized)
                putBoolean("canWriteSetup", ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                putBoolean("setupComplete", android.provider.Settings.Secure.getInt(
                    ctx.contentResolver, "user_setup_complete", 0) == 1)
            } catch (e: Exception) {
                putString("status", "FAILED")
                putString("error", "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
