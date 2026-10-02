package com.nathanhanapps.appdual

import org.junit.Assert.*
import org.junit.Test

class WorkspaceProvisioningTest {
    private class Shell(val respond: (String) -> ShellResult) : IShellExecutor {
        val commands = mutableListOf<String>()
        override fun execWhenReady(cmd: String, callback: (String) -> Unit) {
            commands += cmd
            callback(respond(cmd).render())
        }
        override fun isReady() = true
        override fun unbind() {}
    }
    @Test fun preservesSeparateStreamsAndExit() {
        val result = ShellResult("pm create-user 'Work'", 1, "", "Cannot add more profiles")
        assertEquals(result, ShellResult.parse(result.command, result.render()))
        assertFalse(result.successful)
    }
    @Test fun quotesShellMetacharacters() {
        assertEquals("'a'\"'\"'b\$()'", ShellResult.quote("a'b\$()"))
    }
    @Test fun cleansOnlyNewProfileOnOwnerFailure() {
        val shell = Shell { cmd -> when {
            cmd == "am get-current-user" -> ShellResult(cmd, 0, "0", "")
            cmd.startsWith("pm create-user") -> ShellResult(cmd, 0, "Success: created user id 42", "")
            cmd.startsWith("dpm set-profile-owner") -> ShellResult(cmd, 1, "", "Accounts prevent profile owner")
            else -> ShellResult(cmd, 0, "Success", "")
        } }
        var success = true
        WorkspaceRepository(shell, "test.app").createWorkspace("Work") { ok, _, output ->
            success = ok; assertTrue(output.contains("Accounts prevent profile owner"))
        }
        assertFalse(success)
        assertEquals("pm remove-user 42", shell.commands.last())
        assertFalse(shell.commands.any { it.startsWith("content call") })
    }
    @Test fun neverDeletesLegacyOnFailure() {
        val shell = Shell { cmd -> ShellResult(cmd, if (cmd.startsWith("dpm")) 1 else 0, "", "denied") }
        WorkspaceRepository(shell, "test.app").repairWorkspace(
            WorkspaceInfo(13, "Work", 0x1030, true, "android.os.usertype.profile.MANAGED", false)
        ) { ok, _, _ -> assertFalse(ok) }
        assertFalse(shell.commands.any { it.startsWith("pm remove-user") })
    }
    @Test fun handlesOemUnsignedFlags() {
        val user = WorkspaceParsers.parseUsers("UserInfo{666:XSpace User:80001010} running").single()
        assertEquals(0x80001010.toInt(), user.flags)
    }
    @Test fun finalizesRawSetupAndRevokesTemporaryGrant() {
        var finalized = false
        val admin = "test.app/${AppDualDeviceAdminReceiver::class.java.name}"
        val shell = Shell { cmd ->
            val body = when {
                cmd == "am get-current-user" -> "0"
                cmd.startsWith("pm create-user") -> "Success: created user id 42"
                cmd == "dumpsys device_policy" -> "Profile Owner (User 42):\n    admin=ComponentInfo{$admin}"
                cmd.startsWith("settings") -> if (finalized) "1" else "null"
                cmd.contains("--method complete-raw-setup") -> { finalized = true; "Result: Bundle[{setupComplete=true, status=INITIALIZED}]" }
                cmd.startsWith("content") -> "Result: Bundle[{status=INITIALIZED, setupComplete=false, canWriteSetup=false}]"
                else -> "Success"
            }
            ShellResult(cmd, 0, body, "")
        }
        var ready = false
        WorkspaceRepository(shell, "test.app").createWorkspace("Work") { ok, _, output -> ready = ok; assertTrue(output.endsWith("READY")) }
        assertTrue(ready)
        assertTrue(shell.commands.any { it.startsWith("pm grant --user 42") })
        assertTrue(shell.commands.any { it.startsWith("pm revoke --user 42") })
        assertFalse(shell.commands.any { it.startsWith("pm remove-user") })
    }
    @Test fun bootstrapFailureNeverWritesSetup() {
        val admin = "test.app/${AppDualDeviceAdminReceiver::class.java.name}"
        val shell = Shell { cmd -> ShellResult(cmd, 0, when {
            cmd == "am get-current-user" -> "0"
            cmd.startsWith("pm create-user") -> "Success: created user id 42"
            cmd == "dumpsys device_policy" -> "Profile Owner (User 42):\n    admin=ComponentInfo{$admin}"
            cmd.startsWith("content") -> "Result: Bundle[{status=FAILED, error=Not owner}]"
            else -> "Success"
        }, "") }
        WorkspaceRepository(shell, "test.app").createWorkspace("Work") { ok, _, _ -> assertFalse(ok) }
        assertFalse(shell.commands.any { it.startsWith("pm grant") || it.contains("complete-raw-setup") })
        assertEquals("pm remove-user 42", shell.commands.last())
    }
    @Test fun privateProvisioningNeverSetsProfileOwner() {
        var finalized = false
        val shell = Shell { cmd ->
            check(!cmd.startsWith("dpm set-profile-owner") && cmd != "dumpsys device_policy")
            val body = when {
                cmd == "am get-current-user" -> "0"
                cmd.startsWith("pm create-user") -> "Success: created user id 42"
                cmd.startsWith("settings") -> if (finalized) "1" else "null"
                cmd.contains("--method complete-private-setup") -> { finalized = true; "Result: Bundle[{status=INITIALIZED PRIVATE, setupComplete=true}]" }
                cmd.contains("--method initialize-private") -> "Result: Bundle[{status=INITIALIZED PRIVATE, setupComplete=false, canWriteSetup=false}]"
                else -> "Success"
            }
            ShellResult(cmd, 0, body, "")
        }
        var ready = false
        WorkspaceRepository(shell, "test.app").createWorkspace("Private1", "private") { ok, _, _ -> ready = ok }
        assertTrue(ready)
        assertTrue(shell.commands.any { it.contains("android.os.usertype.profile.PRIVATE") })
        assertTrue(shell.commands.any { it.contains("--method complete-private-setup") })
        assertTrue(shell.commands.any { it.startsWith("pm revoke --user 42") })
        assertFalse(shell.commands.any { it.startsWith("pm remove-user") })
    }
    @Test fun rejectsUnknownTypeWithoutCreatingAUser() {
        val shell = Shell { cmd -> error("Unexpected command: $cmd") }
        WorkspaceRepository(shell, "test.app").createWorkspace("Space", "unknown") { ok, _, _ -> assertFalse(ok) }
        assertTrue(shell.commands.isEmpty())
    }
}
