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
        val target = WorkspaceShortcutTarget(workspace.userId, serial, item.packageName, "${item.label}·${workspace.displayName}")
        val json = JSONObject().put("userId", target.userId).put("serial", serial)
            .put("package", target.packageName).put("label", target.label).toString()
        check(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(target.id, json).commit())
        return target
    }

    fun request(context: Context, workspace: WorkspaceInfo, item: AppItem) {
        val manager = context.getSystemService(ShortcutManager::class.java)
        check(manager.isRequestPinShortcutSupported) { context.getString(R.string.shortcut_unsupported) }
        val target = register(context, workspace, item)
        val launch = Intent(context, WorkspaceShortcutActivity::class.java).setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_ID, target.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val shortcut = ShortcutInfo.Builder(context, target.id)
            .setActivity(ComponentName(context, MainActivity::class.java))
            .setShortLabel(target.label).setLongLabel(target.label).setIntent(launch)
            .setIcon(item.icon?.let { Icon.createWithBitmap(it.toBitmap(192, 192)) }
                ?: Icon.createWithResource(context, R.mipmap.ic_launcher))
            .build()
        // 相同应用/资料重复添加时保留相同 ID，已有快捷方式也更新名称和图标。
        manager.updateShortcuts(listOf(shortcut))
        check(manager.requestPinShortcut(shortcut, null)) { context.getString(R.string.shortcut_unsupported) }
    }

    fun read(context: Context, id: String?): WorkspaceShortcutTarget? = runCatching {
        require(!id.isNullOrBlank())
        val stored = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(id, null) ?: return null
        val json = JSONObject(stored)
        WorkspaceShortcutTarget(json.getInt("userId"), json.getLong("serial"), json.getString("package"), json.getString("label"))
            .also { require(it.id == id) }
    }.getOrNull()
}
