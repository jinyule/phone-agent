package dev.phoneagent.core.tools

// Rejected: a template in a raw string, with a nested string and a char literal around it.
public val quote: Char = '"'

public fun rawTemplate(path: java.nio.file.Path): String =
    """
    ${"(" +
        dev.phoneagent.core.storage
            .FileStoragePort(path) + ")"} $quote
    """

// Not counted: plain text after a simple template, "$quote dev.phoneagent.core.screen.Formatter".
public val text: String = "$quote dev.phoneagent.core.screen.Formatter"
