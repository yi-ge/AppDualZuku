package com.nathanhanapps.appdual

data class WorkspaceInfo(
    val userId: Int,
    val name: String,
    val flags: Int,
    val isRunning: Boolean,
    val userType: String? = null,
    val hasProfileOwner: Boolean? = null,
    val ownerComponent: String? = null,
    val setupComplete: Boolean? = null,
    val serialNumber: Long? = null,
    val parentUserId: Int? = null
) {
    val isProfile: Boolean get() = userType?.contains(".profile.") == true
    val isQuietMode: Boolean get() = flags and 0x80 != 0
    val isManaged: Boolean get() = userType == "android.os.usertype.profile.MANAGED"
    val isPrivate: Boolean get() = userType == "android.os.usertype.profile.PRIVATE"
    val isLegacy: Boolean get() = (isManaged && (hasProfileOwner == false || setupComplete == false)) || (isPrivate && setupComplete == false)
    val isMainUser: Boolean get() = userId == 0
    val displayName: String get() = name.ifBlank { "User $userId" }
}
