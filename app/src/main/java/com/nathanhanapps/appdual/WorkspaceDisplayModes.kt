package com.nathanhanapps.appdual

import android.content.Context
import kotlin.math.ceil

enum class WorkspaceDisplayMode { PHONE, TABLET }

data class TabletDisplaySize(val width: Int, val height: Int, val density: Int) {
    companion object {
        fun forDensity(density: Int): TabletDisplaySize {
            require(density in 120..1000)
            val width = ceil(640.0 * density / 160).toInt()
            return TabletDisplaySize(width, width * 4 / 3, density)
        }
    }
}

object WorkspaceDisplayModes {
    private const val FILE = "workspace_display_modes"
    fun get(context: Context, target: WorkspaceShortcutTarget): WorkspaceDisplayMode {
        val value = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(target.id, "PHONE")
        return runCatching { WorkspaceDisplayMode.valueOf(value ?: "PHONE") }.getOrDefault(WorkspaceDisplayMode.PHONE)
    }
    fun set(context: Context, target: WorkspaceShortcutTarget, mode: WorkspaceDisplayMode) {
        check(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(target.id, mode.name).commit())
    }
}
