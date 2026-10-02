package com.nathanhanapps.appdual

/** User IDs can be reused after deletion; a shortcut must bind to the original serial number. */
data class WorkspaceShortcutTarget(val userId: Int, val serialNumber: Long, val packageName: String, val label: String, val userType: String? = null) {
    init {
        require(userId > 0 && serialNumber >= 0)
        require(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(packageName))
        require(label.isNotBlank())
    }
    val id get() = "workspace_${serialNumber}_$packageName"
    fun matches(workspace: WorkspaceInfo, parentUserId: Int): Boolean =
        workspace.userId == userId && workspace.serialNumber == serialNumber &&
            workspace.isProfile && workspace.parentUserId == parentUserId
}
