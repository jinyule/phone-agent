package dev.phoneagent.core.policy.rules

/** Why an app is on the hard-refusal list. */
internal enum class AppCategory {
    BANK,
    BROKER,
    PAYMENT,
    SYSTEM,
}

/**
 * The hard-refusal app list (phase1 §6), loaded from `blocked-apps.txt` next to this class. The
 * format and the update procedure are in that file's header and policy-rules.md §1.
 */
internal class BlockedApps private constructor(
    private val categories: Map<String, AppCategory>,
    private val byLabel: Map<String, String>,
) {
    fun categoryOf(pkg: String): AppCategory? = categories[pkg]

    /** The package whose label is [name] ([Text.compact] equality), or `null`. */
    fun packageNamed(name: String): String? = byLabel[Text.compact(name)]

    companion object {
        private const val RESOURCE = "blocked-apps.txt"
        private const val FIELDS = 3
        private val SEPARATOR = Regex("\\s+")

        /** The list shipped with core. Fails loudly when the resource is missing or malformed. */
        val DEFAULT: BlockedApps by lazy { load(RESOURCE) }

        /** Loads the resource [name] next to this class; [IllegalArgumentException] when missing or malformed. */
        fun load(name: String): BlockedApps {
            val url = BlockedApps::class.java.getResource(name)
            requireNotNull(url) { "Missing resource $name" }
            return parse(url.readText(), name)
        }

        /** Parses the list format; throws [IllegalArgumentException] naming the first bad line of [source]. */
        fun parse(
            text: String,
            source: String,
        ): BlockedApps {
            val entries =
                text
                    .lines()
                    .withIndex()
                    .filter { (_, line) -> line.isNotBlank() && !line.trimStart().startsWith("#") }
                    .map { (index, line) -> entry("$source line ${index + 1}", line.trim()) }
            val duplicate = entries.groupBy { it.first }.filterValues { it.size > 1 }.keys
            require(duplicate.isEmpty()) { "$source: duplicate packages $duplicate" }
            return BlockedApps(
                entries.associate { it.first to it.second },
                entries.flatMap { (pkg, _, labels) -> labels.map { Text.compact(it) to pkg } }.toMap(),
            )
        }

        private fun entry(
            where: String,
            line: String,
        ): Triple<String, AppCategory, List<String>> {
            val fields = line.split(SEPARATOR, limit = FIELDS)
            require(fields.size == FIELDS) { "$where: expected '<package> <category> <labels>', got '$line'" }
            val (pkg, category, labels) = fields
            require(Text.PACKAGE.matches(pkg)) { "$where: invalid package '$pkg'" }
            val parsed = AppCategory.entries.firstOrNull { it.name.equals(category, ignoreCase = true) }
            requireNotNull(parsed) { "$where: unknown category '$category'" }
            val names = labels.split('|').map(String::trim)
            require(names.none(String::isEmpty)) { "$where: empty label in '$labels'" }
            return Triple(pkg, parsed, names)
        }
    }
}
