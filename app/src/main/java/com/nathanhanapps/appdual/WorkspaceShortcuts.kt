package com.nathanhanapps.appdual

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONObject

object WorkspaceShortcuts {
    const val EXTRA_ID = "workspace_shortcut_id"
    private const val FILE = "workspace_shortcuts"

    fun register(context: Context, workspace: WorkspaceInfo, item: AppItem): WorkspaceShortcutTarget {
        val serial = workspace.serialNumber ?: error(context.getString(R.string.shortcut_identity_missing))
        val target = WorkspaceShortcutTarget(workspace.userId, serial, item.packageName, "${item.label}·${workspace.displayName}", workspace.userType)
        val json = JSONObject().put("userId", target.userId).put("serial", serial)
            .put("package", target.packageName).put("label", target.label).put("userType", target.userType).toString()
        check(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(target.id, json).commit())
        return target
    }

    fun request(context: Context, workspace: WorkspaceInfo, item: AppItem) {
        val manager = context.getSystemService(ShortcutManager::class.java)
        check(manager.isRequestPinShortcutSupported) { context.getString(R.string.shortcut_unsupported) }
        val target = register(context, workspace, item)
        val launch = Intent(context, WorkspaceShortcutActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(android.net.Uri.parse("appdual-workspace://launch/${android.net.Uri.encode(target.id)}"))
            .putExtra(EXTRA_ID, target.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val drawable = item.icon?.let { icon ->
            if (workspace.isManaged) context.packageManager.getUserBadgedIcon(icon,
                android.os.UserHandle.getUserHandleForUid(workspace.userId * 100000)) else icon
        }
        val shortcut = ShortcutInfo.Builder(context, target.id)
            .setActivity(ComponentName(context, MainActivity::class.java))
            .setShortLabel(target.label).setLongLabel(target.label).setIntent(launch)
            .setIcon(drawable?.let { Icon.createWithBitmap(it.toBitmap(192, 192)) }
                ?: Icon.createWithResource(context, R.mipmap.ic_launcher))
            .build()
        // 相同应用/资料重复添加时保留相同 ID，已有快捷方式也更新名称和图标。
        manager.updateShortcuts(listOf(shortcut))
        check(manager.requestPinShortcut(shortcut, null)) { context.getString(R.string.shortcut_unsupported) }
    }

    fun migrate(context: Context, workspaces: List<WorkspaceInfo>): Int {
        val manager = context.getSystemService(ShortcutManager::class.java)
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val pinned = manager.pinnedShortcuts.associateBy { it.id }
        val versions = context.getSharedPreferences("workspace_shortcut_migrations", Context.MODE_PRIVATE)
        val parent = android.os.Process.myUid() / 100000
        var changed = 0
        for (id in preferences.all.keys) {
            val original = read(context, id) ?: continue
            val target = ShortcutMigration.resolve(original, workspaces.find { it.userId == original.userId }, parent) ?: continue
            val shortcut = pinned[id]
            val uri = android.net.Uri.parse("appdual-workspace://launch/${android.net.Uri.encode(id)}")
            if (versions.getBoolean(id, false) && original.userType == target.userType && (shortcut == null || shortcut.intent?.data == uri)) continue
            runCatching {
                if (shortcut != null) {
                    val launch = Intent(context, WorkspaceShortcutActivity::class.java).setAction(Intent.ACTION_VIEW)
                        .setData(uri).putExtra(EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val builder = ShortcutInfo.Builder(context, id).setActivity(ComponentName(context, MainActivity::class.java))
                        .setShortLabel(target.label).setLongLabel(target.label).setIntent(launch)
                    runCatching { context.packageManager.getApplicationIcon(target.packageName) }.getOrNull()?.let { icon ->
                        val drawable = if (target.userType == "android.os.usertype.profile.MANAGED") context.packageManager.getUserBadgedIcon(icon,
                            android.os.UserHandle.getUserHandleForUid(target.userId * 100000)) else icon
                        builder.setIcon(Icon.createWithBitmap(drawable.toBitmap(192, 192)))
                    }
                    check(manager.updateShortcuts(listOf(builder.build())))
                }
                val json = JSONObject().put("userId", target.userId).put("serial", target.serialNumber)
                    .put("package", target.packageName).put("label", target.label).put("userType", target.userType)
                check(preferences.edit().putString(id, json.toString()).commit())
                check(versions.edit().putBoolean(id, true).commit())
                changed++
            }.onFailure { DebugLog.trace(context, "Shortcut migration skipped $id: ${it.javaClass.simpleName}") }
        }
        return changed
    }

    fun read(context: Context, id: String?): WorkspaceShortcutTarget? = runCatching {
        require(!id.isNullOrBlank())
        val stored = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(id, null) ?: return null
        val json = JSONObject(stored)
        WorkspaceShortcutTarget(json.getInt("userId"), json.getLong("serial"), json.getString("package"), json.getString("label"), json.optString("userType").takeIf { it.isNotBlank() && it != "null" })
            .also { require(it.id == id) }
    }.getOrNull()
}
