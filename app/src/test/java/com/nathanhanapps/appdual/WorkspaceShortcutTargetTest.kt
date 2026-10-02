package com.nathanhanapps.appdual

import org.junit.Assert.*
import org.junit.Test

class WorkspaceShortcutTargetTest {
    private val profile = WorkspaceInfo(17, "Private1", 0x1010, true,
        userType = "android.os.usertype.profile.PRIVATE", serialNumber = 1007, parentUserId = 0)
    private val target = WorkspaceShortcutTarget(17, 1007, "com.tencent.mm", "微信·Private1")

    @Test fun acceptsTheConfiguredPrivateProfile() { assertTrue(target.matches(profile, 0)) }
    @Test fun rejectsReusedUserIdAndAnotherParent() {
        assertFalse(target.matches(profile.copy(serialNumber = 1008), 0))
        assertFalse(target.matches(profile.copy(parentUserId = 14), 0))
        assertFalse(target.matches(profile, 14))
    }
    @Test fun rejectsAFullUserEvenWithTheSameId() {
        assertFalse(target.matches(profile.copy(userType = "android.os.usertype.full.SECONDARY"), 0))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsPackageNameThatCouldInjectAShellCommand() {
        WorkspaceShortcutTarget(17, 1007, "com.tencent.mm; id", "微信")
    }
    @Test fun extractsProfileIdentityFromDeviceDump() {
        val details = """  UserInfo{17:Private1:1010} serialNo=1007 isPrimary=false parentId=0
    Type: android.os.usertype.profile.PRIVATE
    Has profile owner: false
Device properties:
"""
        val actual = WorkspaceParsers.enrich(listOf(profile.copy(serialNumber = null, parentUserId = null)), details).single()
        assertTrue(target.matches(actual, 0))
    }
}
