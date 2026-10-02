package com.nathanhanapps.appdual

/** Keeps command diagnostics intact across Shizuku and root transports. */
data class ShellResult(val command: String, val exitCode: Int?, val stdout: String, val stderr: String) {
    val successful get() = exitCode == 0
    fun render() = "exitCode=${exitCode ?: "UNKNOWN"}\ncommand=$command\nstdout:\n$stdout\nstderr:\n$stderr"
    companion object {
        fun parse(command: String, output: String): ShellResult {
            val code = Regex("(?m)^exitCode=(\\d+)$").find(output)?.groupValues?.get(1)?.toIntOrNull()
            val body = output.substringAfter("stdout:\n", "")
            return ShellResult(command, code, body.substringBefore("\nstderr:"),
                output.substringAfter("stderr:\n", if (code == null) output else ""))
        }
        fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
