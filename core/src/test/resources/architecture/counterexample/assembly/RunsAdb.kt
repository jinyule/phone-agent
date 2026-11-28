package dev.phoneagent.core.assembly

import dev.phoneagent.core.adb.AdbSession

// Rejected: the assembly wires capabilities, but the adb client is no part of the agent.
class RunsAdb(
    val session: AdbSession,
)
