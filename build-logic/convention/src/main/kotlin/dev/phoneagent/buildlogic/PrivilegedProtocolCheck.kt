package dev.phoneagent.buildlogic

import java.security.MessageDigest

/** One recorded protocol: [version] and the [fingerprint] of the AIDL sources it shipped with. */
data class ProtocolEntry(
    val version: Int,
    val fingerprint: String,
)

/**
 * "Changing the AIDL requires bumping `PrivilegedProtocol.VERSION`" (engineering-baseline §6).
 *
 * `privileged/protocol-history.txt` is an append-only list of `<version> <fingerprint>` lines.
 * The fingerprint is a SHA-256 over every `.aidl` file, by relative path and [AidlTokens] (so
 * comments and whitespace between tokens are not protocol, string constants are). The last line
 * must be the current VERSION with the current fingerprint.
 */
object PrivilegedProtocolCheck {
    private val VERSION_DECLARATION = Regex("""const\s+val\s+VERSION\s*:\s*Int\s*=\s*(\d+)""")
    private val HISTORY_LINE = Regex("""(0|[1-9]\d*)\s+([0-9a-f]{64})""")

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
