package com.nathanhanapps.appdual

object WorkspaceParsers {

    // Matches: UserInfo{15:Work2:1020} running
    private val USER_REGEX = Regex("""UserInfo\{(\d+):([^:}]*):([0-9a-fA-F]+)\}(.*)""")

    fun parseUsers(output: String): List<WorkspaceInfo> =
        output.lineSequence()
            .mapNotNull { line ->
                val m = USER_REGEX.find(line.trim()) ?: return@mapNotNull null
                val (idStr, name, flagsHex, rest) = m.destructured
                val userId = idStr.toIntOrNull() ?: return@mapNotNull null
                WorkspaceInfo(
                    userId  = userId,
                    name    = name.trim(),
                    flags   = flagsHex.toLongOrNull(16)?.toInt() ?: 0,
                    isRunning = rest.contains("running", ignoreCase = true)
                )
            }
            .sortedBy { it.userId }
            .toList()
    fun enrich(users: List<WorkspaceInfo>, details: String): List<WorkspaceInfo> = users.map { user ->
        val block = Regex("""(?m)^  UserInfo\{${user.userId}:[\s\S]*?(?=^  UserInfo\{|^Device properties:|\z)""").find(details)?.value.orEmpty()
        user.copy(serialNumber = Regex("serialNo=(\\d+)").find(block)?.groupValues?.get(1)?.toLongOrNull(),
            parentUserId = Regex("(?:parentId|profileGroupId)=(\\d+)").find(block)?.groupValues?.get(1)?.toIntOrNull(),
            userType = Regex("Type: (\\S+)").find(block)?.groupValues?.get(1),
            hasProfileOwner = Regex("Has profile owner: (true|false)").find(block)?.groupValues?.get(1)?.toBooleanStrictOrNull())
    }
}
