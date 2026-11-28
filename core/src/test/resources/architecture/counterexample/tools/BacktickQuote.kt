package dev.phoneagent.core.tools

// Rejected: the quote in a backticked name starts no string, so the code after it counts.
// (Without the suppression the compiler warns about the quote, and -Werror stops the build.)
@Suppress("DANGEROUS_CHARACTERS")
internal class QuoteBox<`T"`>

internal val quoteConfig: Any =
    dev.phoneagent.core.assembly
        .ProductConfig(setOf("a.b"), setOf("A"))
