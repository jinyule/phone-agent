package dev.phoneagent.core.agent

// Rejected: code inside a string template runs like any other code. This file would compile as
// production code (FileStoragePort exists), so the rule cannot rely on strings being inert.
public fun templateBypass(path: java.nio.file.Path): String = "${dev.phoneagent.core.storage.FileStoragePort(path)}"
