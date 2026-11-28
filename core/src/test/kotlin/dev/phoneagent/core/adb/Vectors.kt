package dev.phoneagent.core.adb

/**
 * Known-answer files under `src/test/resources/adb/` (provenance in their headers): blocks of
 * `name=hex` lines separated by blank lines, each after a `# note` line.
 */
object Vectors {
    class Case(
        val note: String,
        private val values: Map<String, String>,
    ) {
        fun text(name: String): String = checkNotNull(values[name]) { "$note: no $name" }

        fun hex(name: String): ByteArray = hexBytes(text(name))

        override fun toString(): String = note
    }

    fun load(resource: String): List<Case> {
        val text = checkNotNull(Vectors::class.java.getResource(resource)) { "missing $resource" }.readText()
        val cases = mutableListOf<Case>()
        var note = ""
        val values = LinkedHashMap<String, String>()
        for (line in text.lines() + "") {
            when {
                line.isBlank() && values.isNotEmpty() -> {
                    cases += Case(note, values.toMap())
                    values.clear()
                }

                line.isBlank() -> {
                    continue
                }

                line.startsWith("#") -> {
                    note = line.removePrefix("#").trim()
                }

                else -> {
                    values[line.substringBefore('=')] = line.substringAfter('=')
                }
            }
        }
        return cases
    }

    val spake2: List<Case> by lazy { load("/adb/spake2-vectors.txt") }
    val adbPublicKeys: List<Case> by lazy { load("/adb/adb-pubkey-vectors.txt") }
}
