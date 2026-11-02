package dev.phoneagent.core.adb

import dev.phoneagent.core.tool.Tool

// Rejected: the adb client must not become a tool, nor use any other core package.
abstract class ImplementsTool : Tool
