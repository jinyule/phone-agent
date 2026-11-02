package dev.phoneagent.core.tool

import dev.phoneagent.core.adb.AdbCommand

// Rejected: a tool contract that could hand the model an adb command line (AGENTS.md §3.4).
class UsesAdb(
    val command: AdbCommand,
)
