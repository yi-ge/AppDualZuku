package com.nathanhanapps.appdual

import org.junit.Assert.*
import org.junit.Test

class PrivateStartupPolicyTest {
    private class Shell(private val answer: (String) -> ShellResult) : IShellExecutor {
        val commands = mutableListOf<String>()
        override fun execWhenReady(cmd: String, callback: (String) -> Unit) { commands += cmd; callback(answer(cmd).render()) }
        override fun isReady() = true
        override fun unbind() = Unit
    }
    private fun shell(hadPermission: Boolean = false, failSetter: Boolean = false, supported: Boolean = true): Shell = Shell { cmd ->
        ShellResult(cmd, 0, when {
            cmd == "am get-current-user" -> "8"
            cmd.contains("policy-capabilities") -> "Result: Bundle[{supported=$supported, canWriteSecure=$hadPermission}]"
            cmd.contains("private-autolock-after-restart") -> if (failSetter) "Result: Bundle[{status=FAILED}]" else "Result: Bundle[{status=APPLIED}]"
            cmd.startsWith("settings") -> "2"
            else -> ""
        }, "")
    }
    @Test fun configuresActiveParentAndRevokesTemporaryPermission() {
        val shell = shell()
        var ok = false
        PrivateStartupPolicy(shell, "test.app").configure { result, _ -> ok = result }
        assertTrue(ok)
        assertTrue(shell.commands.any { it.startsWith("pm grant --user 8 ") })
        assertTrue(shell.commands.last().startsWith("pm revoke --user 8 "))
        assertTrue(shell.commands.any { it.startsWith("settings --user 8 ") })
    }
    @Test fun setterFailureStillRevokesPermission() {
        val shell = shell(failSetter = true)
        var failed = false
        PrivateStartupPolicy(shell, "test.app").configure { ok, _ -> failed = !ok }
        assertTrue(failed)
        assertTrue(shell.commands.last().startsWith("pm revoke"))
        assertFalse(shell.commands.any { it.startsWith("settings") })
    }
    @Test fun retainsExistingPermission() {
        val shell = shell(hadPermission = true)
        PrivateStartupPolicy(shell, "test.app").configure { ok, _ -> assertTrue(ok) }
        assertFalse(shell.commands.any { it.startsWith("pm grant") || it.startsWith("pm revoke") })
    }
    @Test fun unsupportedContextDoesNotMutate() {
        val shell = shell(supported = false)
        PrivateStartupPolicy(shell, "test.app").configure { ok, _ -> assertFalse(ok) }
        assertEquals(2, shell.commands.size)
        assertFalse(shell.commands.any { it.startsWith("pm grant") || it.contains("--method private-autolock-after-restart") })
    }
}
