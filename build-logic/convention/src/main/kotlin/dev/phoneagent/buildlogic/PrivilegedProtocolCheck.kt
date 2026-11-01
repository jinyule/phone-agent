package dev.phoneagent.buildlogic

import java.security.MessageDigest

/** One recorded protocol: [version] and the [fingerprint] of the AIDL sources it shipped with. */
data class ProtocolEntry(
    val version: Int,
    val fingerprint: String,
)

/**
 * "Changing the AIDL requires bumping `PrivilegedProtocol.VERSION`" (engineering-baseline §6),
 * plus the two rules a bump cannot bend: transactions 1-4 are [FROZEN], and the history only grows
 * ([verifyAppendOnly], against the CI base commit).
 *
 * `privileged/protocol-history.txt` is an append-only list of `<version> <fingerprint>` lines.
 * The fingerprint is a SHA-256 over every `.aidl` file, by relative path and [AidlTokens] (so
 * comments and whitespace between tokens are not protocol, string constants are). The last line
 * must be the current VERSION with the current fingerprint.
 */
object PrivilegedProtocolCheck {
    private val VERSION_DECLARATION = Regex("""const\s+val\s+VERSION\s*:\s*Int\s*=\s*(\d+)""")
    private val HISTORY_LINE = Regex("""(0|[1-9]\d*)\s+([0-9a-f]{64})""")
    private val INTERFACE_HEAD = listOf("interface", "IPrivilegedCore", "{")

    /** The value of `const val VERSION: Int = <n>` in [kotlinSource]; it must be declared exactly once. */
    fun parseVersion(kotlinSource: String): Int {
        val matches = VERSION_DECLARATION.findAll(kotlinSource).toList()
        require(matches.size == 1) {
            "expected exactly one `const val VERSION: Int = <n>` in PrivilegedProtocol.kt, found ${matches.size}"
        }
        return matches.single().groupValues[1].toInt()
    }

    /** [AidlTokens] of [aidl] joined by single spaces. */
    fun normalize(aidl: String): String = AidlTokens.of(aidl).joinToString(" ")

    /** Fingerprint of [files] (relative path with `/` separators → content). */
    fun fingerprint(files: Map<String, String>): String {
        require(files.isNotEmpty()) { "no .aidl files to fingerprint" }
        val digest = MessageDigest.getInstance("SHA-256")
        files.toSortedMap().forEach { (path, text) ->
            digest.update("$path\n${normalize(text)}\n".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Parses the history; `#` starts a comment. Throws [IllegalArgumentException] naming a malformed line. */
    fun parseHistory(text: String): List<ProtocolEntry> =
        text.lines().mapIndexedNotNull { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@mapIndexedNotNull null
            val match =
                requireNotNull(HISTORY_LINE.matchEntire(line)) {
                    "protocol-history.txt line ${index + 1} is not `<version> <sha256>`: '$raw'"
                }
            ProtocolEntry(match.groupValues[1].toInt(), match.groupValues[2])
        }

    /** The interface whose transactions 1-4 are frozen, relative to the AIDL source root. */
    const val FROZEN_FILE: String = "dev/phoneagent/privileged/IPrivilegedCore.aidl"

    /**
     * Transactions 1-4 of `IPrivilegedCore` as [AidlTokens]. Any app build relies on them to read a
     * helper's version and identity and to make it release and exit, whatever protocol the helper
     * runs (engineering-baseline §6), so they never change, whatever VERSION says.
     */
    val FROZEN: Map<Int, String> =
        mapOf(
            1 to "int protocolVersion ( ) = 1 ;",
            2 to "int [ ] identity ( ) = 2 ;",
            3 to "void releaseAll ( ) = 3 ;",
            4 to "oneway void shutdown ( ) = 4 ;",
        )

    /** Null when [FROZEN_FILE] in [files] declares exactly the [FROZEN] transactions 1-4; otherwise what differs. */
    fun checkFrozen(files: Map<String, String>): String? {
        val methods = files[FROZEN_FILE]?.let { methodsOf(AidlTokens.of(it)) }
        val problems =
            if (methods == null) {
                listOf("$FROZEN_FILE must declare `interface IPrivilegedCore`")
            } else {
                FROZEN.mapNotNull { (id, expected) ->
                    val found = methods.filter { it.endsWith(" = $id ;") }
                    "transaction $id must be `$expected`, found ${found.map { "`$it`" }.ifEmpty { listOf("none") }}"
                        .takeUnless { found == listOf(expected) }
                }
            }
        return problems.takeIf { it.isNotEmpty() }?.joinToString(
            separator = "; ",
            prefix = "IPrivilegedCore transactions 1-4 never change, whatever PrivilegedProtocol.VERSION says: ",
            postfix = ".",
        )
    }

    /** Method declarations of `interface IPrivilegedCore` in [tokens], tokens joined by spaces; null without it. */
    private fun methodsOf(tokens: List<String>): List<String>? {
        val start = tokens.windowed(INTERFACE_HEAD.size).indexOf(INTERFACE_HEAD)
        if (start < 0) return null
        val declarations = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (token in tokens.drop(start + INTERFACE_HEAD.size).takeWhile { it != "}" }) {
            current += token
            if (token == ";") {
                declarations += current
                current = mutableListOf()
            }
        }
        return declarations.filter { "(" in it && it.first() != "const" }.map { it.joinToString(" ") }
    }

    /**
     * Null when [current] starts with every entry of [baseline] (the history at the CI base commit);
     * otherwise which recorded line was rewritten or removed.
     */
    fun verifyAppendOnly(
        baseline: List<ProtocolEntry>,
        current: List<ProtocolEntry>,
    ): String? {
        val index = baseline.indices.firstOrNull { it >= current.size || current[it] != baseline[it] } ?: return null
        val lost = baseline[index]
        return "protocol-history.txt is append-only, but entry ${index + 1} of the base commit, " +
            "`${lost.version} ${lost.fingerprint}`, was rewritten or removed. Restore it; " +
            "a protocol change bumps PrivilegedProtocol.VERSION and appends a line."
    }

    /** Null when [history] ends with ([version], [fingerprint]); otherwise what to fix. */
    fun verify(
        history: List<ProtocolEntry>,
        version: Int,
        fingerprint: String,
    ): String? {
        val latest =
            history.lastOrNull() ?: return "protocol-history.txt is empty; add the line `$version $fingerprint`."
        val unordered = history.zipWithNext().firstOrNull { (a, b) -> b.version <= a.version }
        return when {
            unordered != null -> {
                "protocol-history.txt must list strictly increasing versions; ${unordered.second.version} follows ${unordered.first.version}."
            }

            version < latest.version -> {
                "PrivilegedProtocol.VERSION ($version) is lower than the last recorded protocol (${latest.version})."
            }

            version > latest.version -> {
                "PrivilegedProtocol.VERSION is $version but protocol-history.txt ends at ${latest.version}; " +
                    "append the line `$version $fingerprint`."
            }

            fingerprint != latest.fingerprint -> {
                "The privileged AIDL changed but PrivilegedProtocol.VERSION is still $version. " +
                    "Bump it to ${version + 1} and append the line `${version + 1} $fingerprint` " +
                    "to privileged/protocol-history.txt " +
                    "(a helper started from the old APK keeps running the old protocol)."
            }

            else -> {
                null
            }
        }
    }
}
