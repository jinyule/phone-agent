package dev.phoneagent.core.tools

// Rejected: a tool capability reaching the adb client, here fully qualified and without an import.
suspend fun runForModel(
    session: dev.phoneagent.core.adb.AdbSession,
    line: String,
) = session.run(
    dev.phoneagent.core.adb
        .AdbCommand(line),
)
