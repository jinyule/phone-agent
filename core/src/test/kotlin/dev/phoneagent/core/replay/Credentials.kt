package dev.phoneagent.core.replay

import kotlinx.serialization.json.JsonPrimitive

/**
 * Keys never reach a fixture (engineering-baseline §3.2, AGENTS §6). Record mode replaces every key
 * it sent with a `{{credential:n}}` placeholder ([redact]); every load, in every mode, refuses a
 * fixture file with text shaped like a key ([checkShapes]), so CI checks it on each run.
 */
object Credentials {
    /** The placeholder of the [n]-th key (from 1). */
    fun placeholder(n: Int): String = "{{credential:$n}}"

    /**
     * [text] with each of [secrets] replaced by its placeholder, numbered by its position in the
     * list. Each key is matched as written, JSON-escaped once (a string in a log line) and twice (a
     * string inside `argsJson`, itself a string in the line), longest form first. Blank keys are
     * rejected.
     */
    fun redact(
        text: String,
        secrets: List<String>,
    ): Redacted {
        require(secrets.none(String::isBlank)) { "a key to redact must not be blank" }
        var result = text
        val hits = mutableMapOf<Int, Int>()
        secrets.forEachIndexed { index, secret ->
            val forms =
                listOf(
                    secret,
                    escaped(secret),
                    escaped(escaped(secret)),
                ).distinct().sortedByDescending { it.length }
            for (form in forms) {
                val count = occurrences(result, form)
                if (count > 0) {
                    result = result.replace(form, placeholder(index + 1))
                    hits.merge(index + 1, count, Int::plus)
                }
            }
        }
        return Redacted(result, hits)
    }

    /**
     * Fails with [IllegalArgumentException] when [text] (the content of fixture file [file]) holds
     * text shaped like a credential: an OpenAI-style `sk-` key, a JWT, or a `Bearer` token. The
     * message names the file, the line and the shape, never the text.
     */
    fun checkShapes(
        file: String,
        text: String,
    ) {
        text.lineSequence().forEachIndexed { index, line ->
            for ((shape, pattern) in SHAPES) {
                require(!pattern.containsMatchIn(line)) {
                    "$file line ${index + 1}: text shaped like a $shape; " +
                        "keys never go into a fixture (use ${placeholder(1)})"
                }
            }
        }
    }

    private fun occurrences(
        text: String,
        form: String,
    ): Int {
        var count = 0
        var at = text.indexOf(form)
        while (at >= 0) {
            count++
            at = text.indexOf(form, at + form.length)
        }
        return count
    }

    /** [value] as it appears inside a JSON string, without the quotes. */
    private fun escaped(value: String): String = JsonPrimitive(value).toString().let { it.substring(1, it.length - 1) }

    private val SHAPES: List<Pair<String, Regex>> =
        listOf(
            // At a word start only: "risk-assessment" and "mask-sensitive" are prose, not keys.
            "sk- key" to Regex("""\bsk-[A-Za-z0-9_-]{8,}"""),
            "JWT" to Regex("""eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}"""),
            "Bearer token" to Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/-]{8,}"""),
        )
}

/**
 * Text after [Credentials.redact].
 *
 * @property text the redacted text.
 * @property hits how many replacements each key needed, by placeholder number; any hit means a key
 *   reached the log, which the privacy checklist must explain.
 */
data class Redacted(
    val text: String,
    val hits: Map<Int, Int>,
)
