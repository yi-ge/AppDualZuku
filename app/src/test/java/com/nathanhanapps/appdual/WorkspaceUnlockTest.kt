package com.nathanhanapps.appdual

import org.junit.Assert.*
import org.junit.Test

class WorkspaceUnlockTest {
    private class Shell(val answer: (String) -> ShellResult) : IShellExecutor {
        val commands = mutableListOf<String>()
        override fun execWhenReady(cmd: String, callback: (String) -> Unit) {
            commands.add(cmd); callback(answer(cmd).render())
        }
        override fun isReady() = true
        override fun unbind() = Unit
    }
    @Test fun waitsForActualProfileUnlockAfterCredentials() {
        val pending = mutableListOf<() -> Unit>()
        var unlocked = false
        val shell = Shell { ShellResult(it, 0, when {
            it.contains("app_process") -> "CREDENTIAL_REQUIRED"
            it.contains("get-started-user-state") -> if (unlocked) "RUNNING_UNLOCKED" else "RUNNING_LOCKED"
            else -> "Success"
        }, "") }
        var called = false
        WorkspaceRepository(shell, "test.app", { pending.add(it) }).launchInWorkspace(10, "test.app/.Main") { ok, _ ->
            called = true; assertTrue(ok)
        }
        assertFalse(called)
        assertFalse(shell.commands.any { it.startsWith("am start") })
        unlocked = true
        pending.removeAt(0).invoke()
        assertTrue(called)
        assertTrue(shell.commands.last().startsWith("am start --user 10"))
    }
    @Test fun timesOutWithoutLaunchingWhenVerificationNeverCompletes() {
        val pending = mutableListOf<() -> Unit>()
        val shell = Shell { ShellResult(it, 0, if (it.contains("app_process")) "CREDENTIAL_REQUIRED" else "RUNNING_LOCKED", "") }
        var failed = false
        WorkspaceRepository(shell, "test.app", { pending.add(it) }).launchInWorkspace(10, "test.app/.Main") { ok, _ -> failed = !ok }
        while (pending.isNotEmpty()) pending.removeAt(0).invoke()
        assertTrue(failed)
        assertFalse(shell.commands.any { it.startsWith("am start") })
    }
    @Test fun stoppedProfileDuringAuthenticationIsNotAnImmediateFailure() {
        val pending = mutableListOf<() -> Unit>()
        var unlocked = false
        val shell = Shell { cmd ->
            when {
                cmd.contains("app_process") -> ShellResult(cmd, 0, "CREDENTIAL_REQUIRED", "")
                cmd.contains("get-started-user-state") && !unlocked -> ShellResult(cmd, 1, "User is not started: 10", "")
                cmd.contains("get-started-user-state") -> ShellResult(cmd, 0, "RUNNING_UNLOCKED", "")
                else -> ShellResult(cmd, 0, "Success", "")
            }
        }
        var called = false
        WorkspaceRepository(shell, "test.app", { pending.add(it) }).launchInWorkspace(10, "test.app/.Main") { ok, _ -> called = true; assertTrue(ok) }
        assertFalse(called)
        unlocked = true
        pending.removeAt(0).invoke()
        assertTrue(called)
    }
    @Test fun doesNotClaimSuccessForLockedUser() {
        val shell = Shell { ShellResult(it, 0, when {
            it.contains("app_process") -> "ACTIVE"
            it.startsWith("am start-user") -> "Success: user started"
            else -> "RUNNING_LOCKED"
        }, "") }
        WorkspaceRepository(shell, "test.app").startWorkspace(10) { ok, _ -> assertFalse(ok) }
    }
    @Test fun launchesOnlyAfterUnlocked() {
        val shell = Shell { ShellResult(it, 0, when {
            it.contains("app_process") -> "RESUMED"
            it.contains("get-started-user-state") -> "RUNNING_UNLOCKED"
            else -> "Success"
        }, "") }
        var called = false
        WorkspaceRepository(shell, "test.app").launchInWorkspace(10, "test.app/.Main") { ok, _ -> called = true; assertTrue(ok) }
        assertTrue(called)
        assertTrue(shell.commands.last().startsWith("am start --user 10"))
    }
    @Test fun preservesUnlockError() {
        val shell = Shell { ShellResult(it, 1, "", "SecurityException: denied") }
        WorkspaceRepository(shell, "test.app").startWorkspace(10) { ok, detail ->
            assertFalse(ok); assertTrue(detail.contains("SecurityException: denied"))
        }
        assertEquals(1, shell.commands.size)
    }
}
