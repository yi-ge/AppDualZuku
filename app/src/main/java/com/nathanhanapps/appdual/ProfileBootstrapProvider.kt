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
