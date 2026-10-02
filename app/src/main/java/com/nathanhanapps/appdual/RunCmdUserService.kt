package com.nathanhanapps.appdual

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * IMPORTANT: Shizuku user services must NOT extend Service.
 * They should be plain classes that extend the AIDL Stub.
 */
class RunCmdUserService : IRunCmdService.Stub() {

    override fun run(cmd: String): String {
        return runShell(cmd)
    }

    /**
     * This method is called by Shizuku to destroy the service
     */
    override fun destroy() {
        // Cleanup if needed
        System.exit(0)
    }

    private fun runShell(cmd: String): String {
        return try {
            val p = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(false)
                .start()

            // 同时排空两个管道，避免 dumpsys 等大输出填满管道而假超时。
            val readers = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val out = readers.submit<String> { p.inputStream.bufferedReader().use { it.readText() } }
                val err = readers.submit<String> { p.errorStream.bufferedReader().use { it.readText() } }
                if (!p.waitFor(45, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    return ShellResult(cmd, null, "", "Command timed out after 45s").render()
                }
                ShellResult(cmd, p.exitValue(), out.get(2, TimeUnit.SECONDS).trim(),
                    err.get(2, TimeUnit.SECONDS).trim()).render()
            } finally {
                readers.shutdownNow()
            }
        } catch (t: Throwable) {
            "ERROR: ${t.javaClass.simpleName}: ${t.message}"
        }
    }
}