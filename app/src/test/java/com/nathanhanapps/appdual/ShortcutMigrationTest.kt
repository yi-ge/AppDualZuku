package com.nathanhanapps.appdual

import org.junit.Assert.*
import org.junit.Test

class ShortcutMigrationTest {
    private val legacy = WorkspaceShortcutTarget(21, 4001, "test.app", "Work app")
    private val workspace = WorkspaceInfo(21, "Work", 0x1030, true,
        userType = "android.os.usertype.profile.MANAGED", serialNumber = 4001, parentUserId = 8)
    @Test fun upgradesVerifiedLegacyIdentityWithoutRetargeting() {
        val migrated = ShortcutMigration.resolve(legacy, workspace, 8)!!
        assertEquals(legacy.id, migrated.id)
        assertEquals(legacy.userId, migrated.userId)
        assertEquals("android.os.usertype.profile.MANAGED", migrated.userType)
    }
    @Test fun reusedUserIdCannotAdoptOldShortcut() {
        assertNull(ShortcutMigration.resolve(legacy, workspace.copy(serialNumber = 4002), 8))
    }
    @Test fun differentParentCannotMigrateShortcut() {
        assertNull(ShortcutMigration.resolve(legacy, workspace, 0))
    }
}
